---- MODULE CouponLifecycle ----
EXTENDS Naturals

VARIABLES
  stock,              \* Redis coupon:{id}:stock (0..1)
  claimedRedis,       \* Redis coupon:{id}:claims[user] (0..1)
  token,              \* "none" | "raw" | "proc" | "cons"
  rollbackGuard,      \* BOOLEAN: coupon:{templateId}:rollback:{userCouponId}
  pendingSend,        \* BOOLEAN: claim() between Lua deduct and Kafka ack/timeout
  mqClaim,            \* BOOLEAN: CouponClaimMessage in Kafka
  dbCoupon,           \* "none" | "unused" | "used" | "expired"
  dbReceived,         \* 0..1: coupon_template.received
  orderStatus,        \* "unpaid" | "paid" | "cancelled"
  feignCheckOk,       \* BOOLEAN: useCoupon() pre-check saw order status=0
  usePendingRecheck,  \* BOOLEAN: useCoupon() committed DB status=1, awaiting post-use getOrder recheck
  mqCancelRefund,     \* BOOLEAN: seckill-cancel-topic event pending for OrderCancelCouponRefundConsumer
  couponExpired       \* BOOLEAN: wall-clock passed coupon.expireTime

vars == <<stock, claimedRedis, token, rollbackGuard, pendingSend, mqClaim, dbCoupon,
          dbReceived, orderStatus, feignCheckOk, usePendingRecheck, mqCancelRefund, couponExpired>>

Init ==
  /\ stock = 1
  /\ claimedRedis = 0
  /\ token = "none"
  /\ rollbackGuard = FALSE
  /\ pendingSend = FALSE
  /\ mqClaim = FALSE
  /\ dbCoupon = "none"
  /\ dbReceived = 0
  /\ orderStatus = "unpaid"
  /\ feignCheckOk = FALSE
  /\ usePendingRecheck = FALSE
  /\ mqCancelRefund = FALSE
  /\ couponExpired = FALSE

\* 1. CouponServiceImpl.claim(): coupon_claim.lua + per-userCouponId token (fresh ID when previous claim drained)
ClaimLua ==
  /\ ~pendingSend
  /\ ~mqClaim
  /\ claimedRedis < 1
  /\ stock > 0
  /\ stock' = stock - 1
  /\ claimedRedis' = claimedRedis + 1
  /\ token' = "raw"
  /\ rollbackGuard' = FALSE
  /\ pendingSend' = TRUE
  /\ UNCHANGED <<mqClaim, dbCoupon, dbReceived, orderStatus, feignCheckOk, usePendingRecheck, mqCancelRefund, couponExpired>>

\* 2a. Kafka ack
ClaimKafkaAck ==
  /\ pendingSend
  /\ mqClaim' = TRUE
  /\ pendingSend' = FALSE
  /\ UNCHANGED <<stock, claimedRedis, token, rollbackGuard, dbCoupon, dbReceived, orderStatus, feignCheckOk, usePendingRecheck, mqCancelRefund, couponExpired>>

\* 2b. Kafka timeout after broker got message -> coupon_rollback.lua (with rollbackGuard NX)
ClaimKafkaTimeoutRollback ==
  /\ pendingSend
  /\ mqClaim' = TRUE
  /\ pendingSend' = FALSE
  /\ IF token = "raw"
     THEN /\ token' = "none"
          /\ IF ~rollbackGuard
             THEN /\ rollbackGuard' = TRUE
                  /\ stock' = stock + 1
                  /\ claimedRedis' = claimedRedis - 1
             ELSE UNCHANGED <<rollbackGuard, stock, claimedRedis>>
     ELSE UNCHANGED <<stock, claimedRedis, token, rollbackGuard>>
  /\ UNCHANGED <<dbCoupon, dbReceived, orderStatus, feignCheckOk, usePendingRecheck, mqCancelRefund, couponExpired>>

\* 3. Redis token TTL (30 min) expires while Kafka consumer lags
TokenTtlExpire ==
  /\ token = "raw"
  /\ ~pendingSend
  /\ token' = "none"
  /\ UNCHANGED <<stock, claimedRedis, rollbackGuard, pendingSend, mqClaim, dbCoupon, dbReceived, orderStatus, feignCheckOk, usePendingRecheck, mqCancelRefund, couponExpired>>

\* 4. CouponClaimConsumer.onMessage (token_claim.lua, or coupon_expire_compensate.lua when token expired & DB none)
ConsumeClaim ==
  /\ mqClaim
  /\ mqClaim' = FALSE
  /\ IF dbCoupon = "none" /\ token \in {"raw", "proc"}
     THEN /\ dbCoupon' = "unused"
          /\ dbReceived' = dbReceived + 1
          /\ token' = "cons"
          /\ UNCHANGED <<stock, claimedRedis, rollbackGuard>>
     ELSE IF dbCoupon = "none" /\ token = "none" /\ ~rollbackGuard /\ claimedRedis > 0
     THEN /\ rollbackGuard' = TRUE
          /\ stock' = stock + 1
          /\ claimedRedis' = claimedRedis - 1
          /\ UNCHANGED <<dbCoupon, dbReceived, token>>
     ELSE UNCHANGED <<stock, claimedRedis, rollbackGuard, dbCoupon, dbReceived, token>>
  /\ UNCHANGED <<pendingSend, orderStatus, feignCheckOk, usePendingRecheck, mqCancelRefund, couponExpired>>

\* 4b. CouponStockReconcileJob fallback in quiescent window (no active tokens)
ReconcileStock ==
  /\ ~pendingSend
  /\ ~mqClaim
  /\ token = "none"
  /\ (stock + dbReceived # 1 \/ claimedRedis # dbReceived)
  /\ stock' = 1 - dbReceived
  /\ claimedRedis' = dbReceived
  /\ UNCHANGED <<token, rollbackGuard, pendingSend, mqClaim, dbCoupon, dbReceived, orderStatus, feignCheckOk, usePendingRecheck, mqCancelRefund, couponExpired>>

\* 5. CouponServiceImpl.useCoupon Step A: Feign getOrder sees status=0 (unpaid)
UseCheckOrderUnpaid ==
  /\ dbCoupon = "unused"
  /\ ~couponExpired
  /\ ~feignCheckOk
  /\ ~usePendingRecheck
  /\ orderStatus = "unpaid"
  /\ feignCheckOk' = TRUE
  /\ UNCHANGED <<stock, claimedRedis, token, rollbackGuard, pendingSend, mqClaim, dbCoupon, dbReceived, orderStatus, usePendingRecheck, mqCancelRefund, couponExpired>>

\* 6a. CouponServiceImpl.useCoupon Step B: userCouponMapper.useCoupon updates DB status=1
UseCommitCoupon ==
  /\ feignCheckOk
  /\ dbCoupon = "unused"
  /\ ~couponExpired
  /\ ~usePendingRecheck
  /\ dbCoupon' = "used"
  /\ feignCheckOk' = FALSE
  /\ usePendingRecheck' = TRUE
  /\ UNCHANGED <<stock, claimedRedis, token, rollbackGuard, pendingSend, mqClaim, dbReceived, orderStatus, mqCancelRefund, couponExpired>>

\* 6b. CouponServiceImpl.useCoupon Step C: post-use Feign recheck; releaseCoupon if order no longer unpaid
UseRecheckOrder ==
  /\ usePendingRecheck
  /\ usePendingRecheck' = FALSE
  /\ IF orderStatus = "unpaid"
     THEN UNCHANGED dbCoupon
     ELSE IF dbCoupon = "used"
          THEN dbCoupon' = IF couponExpired THEN "expired" ELSE "unused"
          ELSE UNCHANGED dbCoupon
  /\ UNCHANGED <<stock, claimedRedis, token, rollbackGuard, pendingSend, mqClaim, dbReceived, orderStatus, feignCheckOk, mqCancelRefund, couponExpired>>

\* 7. OrderServiceImpl.pay / cancel (cancel publishes seckill-cancel-topic for coupon refund!)
PayOrder ==
  /\ orderStatus = "unpaid"
  /\ orderStatus' = "paid"
  /\ UNCHANGED <<stock, claimedRedis, token, rollbackGuard, pendingSend, mqClaim, dbCoupon, dbReceived, feignCheckOk, usePendingRecheck, mqCancelRefund, couponExpired>>

CancelOrder ==
  /\ orderStatus = "unpaid"
  /\ orderStatus' = "cancelled"
  /\ mqCancelRefund' = TRUE
  /\ UNCHANGED <<stock, claimedRedis, token, rollbackGuard, pendingSend, mqClaim, dbCoupon, dbReceived, feignCheckOk, usePendingRecheck, couponExpired>>

\* 7b. OrderCancelCouponRefundConsumer.onOrderCancelled: refunds used coupon bound to cancelled order
CancelRefundCoupon ==
  /\ mqCancelRefund
  /\ mqCancelRefund' = FALSE
  /\ IF dbCoupon = "used"
     THEN dbCoupon' = IF couponExpired THEN "expired" ELSE "unused"
     ELSE UNCHANGED dbCoupon
  /\ UNCHANGED <<stock, claimedRedis, token, rollbackGuard, pendingSend, mqClaim, dbReceived, orderStatus, feignCheckOk, usePendingRecheck, couponExpired>>

\* 8. Coupon expires & CouponExpireJob runs
CouponTimePass ==
  /\ ~couponExpired
  /\ couponExpired' = TRUE
  /\ UNCHANGED <<stock, claimedRedis, token, rollbackGuard, pendingSend, mqClaim, dbCoupon, dbReceived, orderStatus, feignCheckOk, usePendingRecheck, mqCancelRefund>>

ExpireJob ==
  /\ couponExpired
  /\ dbCoupon = "unused"
  /\ dbCoupon' = "expired"
  /\ UNCHANGED <<stock, claimedRedis, token, rollbackGuard, pendingSend, mqClaim, dbReceived, orderStatus, feignCheckOk, usePendingRecheck, mqCancelRefund, couponExpired>>

Done ==
  /\ ~pendingSend
  /\ ~mqClaim
  /\ ~usePendingRecheck
  /\ ~mqCancelRefund
  /\ ~(couponExpired /\ dbCoupon = "unused")
  /\ UNCHANGED vars

Next ==
  \/ ClaimLua
  \/ ClaimKafkaAck
  \/ ClaimKafkaTimeoutRollback
  \/ TokenTtlExpire
  \/ ConsumeClaim
  \/ ReconcileStock
  \/ UseCheckOrderUnpaid
  \/ UseCommitCoupon
  \/ UseRecheckOrder
  \/ PayOrder
  \/ CancelOrder
  \/ CancelRefundCoupon
  \/ CouponTimePass
  \/ ExpireJob
  \/ Done

\* ===== Invariants =====

\* 1. At rest (no pending send/MQ), Redis stock + DB received == 1 and Redis claims == DB received
CouponStockConserved ==
  (~pendingSend /\ ~mqClaim) => (stock + dbReceived = 1 /\ claimedRedis = dbReceived)

\* 2. Once useCoupon recheck & async cancel-refund settle, a used coupon is NEVER bound to a cancelled order
UsedCouponNotOnCancelledOrder ==
  (~mqCancelRefund /\ ~usePendingRecheck) => ((dbCoupon = "used") => (orderStatus # "cancelled"))

\* 3. useCoupon will never finish committed against an order that was already cancelled/paid
UsedCouponOrderNotPaidBeforeUse ==
  ~(feignCheckOk /\ orderStatus = "paid" /\ dbCoupon = "unused")

====
