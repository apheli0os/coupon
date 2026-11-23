---- MODULE CouponLifecycle ----
EXTENDS Naturals

VARIABLES
  stock,              \* Redis coupon:{id}:stock (0..1)
  claimedRedis,       \* Redis coupon:{id}:claims[user] (0..1)
  token,              \* "none" | "raw" | "proc" | "cons"
  rollbackGuard,      \* BOOLEAN: coupon:{templateId}:rollback:{userCouponId}
  pendingSend,        \* BOOLEAN: claim() between Lua deduct and Kafka ack/timeout
  mqClaim,            \* BOOLEAN: CouponClaimMessage in Kafka
  dbCoupon,
  dbReceived,         \* 0..1: coupon_template.received
  settlement,
  orderBound,
  orderStatus,        \* "unpaid" | "paid" | "cancelled"
  lockPending,
  mqOrderPaid,
  mqCancelRefund,     \* BOOLEAN: seckill-cancel-topic event pending for OrderCancelCouponRefundConsumer
  couponExpired       \* BOOLEAN: wall-clock passed coupon.expireTime

vars == <<stock, claimedRedis, token, rollbackGuard, pendingSend, mqClaim, dbCoupon,
          dbReceived, settlement, orderBound, orderStatus, lockPending, mqOrderPaid,
          mqCancelRefund, couponExpired>>

Init ==
  /\ stock = 1
  /\ claimedRedis = 0
  /\ token = "none"
  /\ rollbackGuard = FALSE
  /\ pendingSend = FALSE
  /\ mqClaim = FALSE
  /\ dbCoupon = "none"
  /\ dbReceived = 0
  /\ settlement = "none"
  /\ orderBound = FALSE
  /\ orderStatus = "unpaid"
  /\ lockPending = FALSE
  /\ mqOrderPaid = FALSE
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
  /\ UNCHANGED <<mqClaim, dbCoupon, dbReceived, settlement, orderBound, orderStatus, lockPending, mqOrderPaid, mqCancelRefund, couponExpired>>

\* 2a. Kafka ack
ClaimKafkaAck ==
  /\ pendingSend
  /\ mqClaim' = TRUE
  /\ pendingSend' = FALSE
  /\ UNCHANGED <<stock, claimedRedis, token, rollbackGuard, dbCoupon, dbReceived, settlement, orderBound, orderStatus, lockPending, mqOrderPaid, mqCancelRefund, couponExpired>>

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
  /\ UNCHANGED <<dbCoupon, dbReceived, settlement, orderBound, orderStatus, lockPending, mqOrderPaid, mqCancelRefund, couponExpired>>

\* 3. Redis token TTL (30 min) expires while Kafka consumer lags
TokenTtlExpire ==
  /\ token = "raw"
  /\ ~pendingSend
  /\ token' = "none"
  /\ UNCHANGED <<stock, claimedRedis, rollbackGuard, pendingSend, mqClaim, dbCoupon, dbReceived, settlement, orderBound, orderStatus, lockPending, mqOrderPaid, mqCancelRefund, couponExpired>>

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
  /\ UNCHANGED <<pendingSend, settlement, orderBound, orderStatus, lockPending, mqOrderPaid, mqCancelRefund, couponExpired>>

\* 4b. CouponStockReconcileJob fallback in quiescent window (no active tokens)
ReconcileStock ==
  /\ ~pendingSend
  /\ ~mqClaim
  /\ token = "none"
  /\ (stock + dbReceived # 1 \/ claimedRedis # dbReceived)
  /\ stock' = 1 - dbReceived
  /\ claimedRedis' = dbReceived
  /\ UNCHANGED <<token, rollbackGuard, pendingSend, mqClaim, dbCoupon, dbReceived, settlement, orderBound, orderStatus, lockPending, mqOrderPaid, mqCancelRefund, couponExpired>>

LockCoupon ==
  /\ dbCoupon = "unused"
  /\ ~couponExpired
  /\ settlement = "none"
  /\ orderStatus = "unpaid"
  /\ ~orderBound
  /\ ~lockPending
  /\ dbCoupon' = "locked"
  /\ settlement' = "locked"
  /\ lockPending' = TRUE
  /\ UNCHANGED <<stock, claimedRedis, token, rollbackGuard, pendingSend, mqClaim, dbReceived, orderBound, orderStatus, mqOrderPaid, mqCancelRefund, couponExpired>>

BindOrderSuccess ==
  /\ lockPending
  /\ orderStatus = "unpaid"
  /\ ~orderBound
  /\ orderBound' = TRUE
  /\ lockPending' = FALSE
  /\ UNCHANGED <<stock, claimedRedis, token, rollbackGuard, pendingSend, mqClaim, dbCoupon, dbReceived, settlement, orderStatus, mqOrderPaid, mqCancelRefund, couponExpired>>

BindOrderFailCompensateUnlock ==
  /\ lockPending
  /\ lockPending' = FALSE
  /\ IF settlement = "locked"
     THEN /\ settlement' = "cancelled"
          /\ dbCoupon' = IF couponExpired THEN "expired" ELSE "unused"
     ELSE UNCHANGED <<settlement, dbCoupon>>
  /\ UNCHANGED <<stock, claimedRedis, token, rollbackGuard, pendingSend, mqClaim, dbReceived, orderBound, orderStatus, mqOrderPaid, mqCancelRefund, couponExpired>>

BindOrderFailCompensationLost ==
  /\ lockPending
  /\ lockPending' = FALSE
  /\ UNCHANGED <<stock, claimedRedis, token, rollbackGuard, pendingSend, mqClaim, dbCoupon, dbReceived, settlement, orderBound, orderStatus, mqOrderPaid, mqCancelRefund, couponExpired>>

PayOrder ==
  /\ orderStatus = "unpaid"
  /\ orderStatus' = "paid"
  /\ mqOrderPaid' = IF orderBound THEN TRUE ELSE mqOrderPaid
  /\ UNCHANGED <<stock, claimedRedis, token, rollbackGuard, pendingSend, mqClaim, dbCoupon, dbReceived, settlement, orderBound, lockPending, mqCancelRefund, couponExpired>>

ConsumeOrderPaid ==
  /\ mqOrderPaid
  /\ mqOrderPaid' = FALSE
  /\ IF settlement = "locked" /\ dbCoupon = "locked"
     THEN /\ settlement' = "settled"
          /\ dbCoupon' = "used"
     ELSE UNCHANGED <<settlement, dbCoupon>>
  /\ UNCHANGED <<stock, claimedRedis, token, rollbackGuard, pendingSend, mqClaim, dbReceived, orderBound, orderStatus, lockPending, mqCancelRefund, couponExpired>>

DropOrderPaidEvent ==
  /\ mqOrderPaid
  /\ mqOrderPaid' = FALSE
  /\ UNCHANGED <<stock, claimedRedis, token, rollbackGuard, pendingSend, mqClaim, dbCoupon, dbReceived, settlement, orderBound, orderStatus, lockPending, mqCancelRefund, couponExpired>>

CancelOrder ==
  /\ orderStatus = "unpaid"
  /\ orderStatus' = "cancelled"
  /\ mqCancelRefund' = TRUE
  /\ UNCHANGED <<stock, claimedRedis, token, rollbackGuard, pendingSend, mqClaim, dbCoupon, dbReceived, settlement, orderBound, lockPending, mqOrderPaid, couponExpired>>

ConsumeOrderCancelled ==
  /\ mqCancelRefund
  /\ mqCancelRefund' = FALSE
  /\ IF settlement = "locked" /\ dbCoupon = "locked"
     THEN /\ settlement' = "cancelled"
          /\ dbCoupon' = IF couponExpired THEN "expired" ELSE "unused"
     ELSE UNCHANGED <<settlement, dbCoupon>>
  /\ UNCHANGED <<stock, claimedRedis, token, rollbackGuard, pendingSend, mqClaim, dbReceived, orderBound, orderStatus, lockPending, mqOrderPaid, couponExpired>>

DropCancelEvent ==
  /\ mqCancelRefund
  /\ mqCancelRefund' = FALSE
  /\ UNCHANGED <<stock, claimedRedis, token, rollbackGuard, pendingSend, mqClaim, dbCoupon, dbReceived, settlement, orderBound, orderStatus, lockPending, mqOrderPaid, couponExpired>>

SweepExpiredLock ==
  /\ settlement = "locked"
  /\ dbCoupon = "locked"
  /\ orderStatus # "unpaid"
  /\ IF orderStatus = "paid" /\ orderBound
     THEN /\ settlement' = "settled"
          /\ dbCoupon' = "used"
     ELSE /\ settlement' = "cancelled"
          /\ dbCoupon' = IF couponExpired THEN "expired" ELSE "unused"
  /\ UNCHANGED <<stock, claimedRedis, token, rollbackGuard, pendingSend, mqClaim, dbReceived, orderBound, orderStatus, lockPending, mqOrderPaid, mqCancelRefund, couponExpired>>

CouponTimePass ==
  /\ ~couponExpired
  /\ couponExpired' = TRUE
  /\ UNCHANGED <<stock, claimedRedis, token, rollbackGuard, pendingSend, mqClaim, dbCoupon, dbReceived, settlement, orderBound, orderStatus, lockPending, mqOrderPaid, mqCancelRefund>>

ExpireJob ==
  /\ couponExpired
  /\ dbCoupon = "unused"
  /\ dbCoupon' = "expired"
  /\ UNCHANGED <<stock, claimedRedis, token, rollbackGuard, pendingSend, mqClaim, dbReceived, settlement, orderBound, orderStatus, lockPending, mqOrderPaid, mqCancelRefund, couponExpired>>

Done ==
  /\ ~pendingSend
  /\ ~mqClaim
  /\ ~lockPending
  /\ ~mqOrderPaid
  /\ ~mqCancelRefund
  /\ ~(settlement = "locked" /\ orderStatus # "unpaid")
  /\ ~(couponExpired /\ dbCoupon = "unused")
  /\ UNCHANGED vars

Next ==
  \/ ClaimLua
  \/ ClaimKafkaAck
  \/ ClaimKafkaTimeoutRollback
  \/ TokenTtlExpire
  \/ ConsumeClaim
  \/ ReconcileStock
  \/ LockCoupon
  \/ BindOrderSuccess
  \/ BindOrderFailCompensateUnlock
  \/ BindOrderFailCompensationLost
  \/ PayOrder
  \/ ConsumeOrderPaid
  \/ DropOrderPaidEvent
  \/ CancelOrder
  \/ ConsumeOrderCancelled
  \/ DropCancelEvent
  \/ SweepExpiredLock
  \/ CouponTimePass
  \/ ExpireJob
  \/ Done

\* ===== Invariants =====

\* 1. At rest (no pending send/MQ), Redis stock + DB received == 1 and Redis claims == DB received
CouponStockConserved ==
  (~pendingSend /\ ~mqClaim) => (stock + dbReceived = 1 /\ claimedRedis = dbReceived)

SettlementCouponConsistency ==
  /\ (settlement = "locked") <=> (dbCoupon = "locked")
  /\ (settlement = "settled") <=> (dbCoupon = "used")
  /\ (settlement = "cancelled") => (dbCoupon \in {"unused", "expired"})

NoUnlockedCouponWhileBoundUnpaid ==
  (orderBound /\ orderStatus = "unpaid") => (settlement = "locked" /\ dbCoupon = "locked")

NoUsedCouponOnCancelledOrUnboundOrder ==
  (dbCoupon = "used" \/ settlement = "settled") => (orderBound /\ orderStatus = "paid")

PaidBoundOrderNeverCancelsSettlement ==
  (orderStatus = "paid" /\ orderBound) => (settlement # "cancelled")

====
