export const title = "Coupon Lifecycle (Fixed: Expire Compensate + Reconcile + Recheck + Cancel Refund)";

export const state = (v) => ({
  Claim: `stock=${v.stock}, db=${v.dbCoupon}, tok=${v.token}, guard=${v.rollbackGuard ? "1" : "0"}`,
  Order: `order=${v.orderStatus}, recheck=${v.usePendingRecheck ? "yes" : "no"}, refundMQ=${v.mqCancelRefund ? "1" : "0"}`,
});

export const step = (action) => {
  switch (action) {
    case "ClaimLua": return "user claims (Lua)";
    case "ClaimKafkaAck": return "Kafka ack";
    case "ClaimKafkaTimeoutRollback": return "Kafka timeout -> guarded rollback";
    case "TokenTtlExpire": return "token TTL expires (lag)";
    case "ConsumeClaim": return "consumer inserts or expire-compensates";
    case "ReconcileStock": return "reconcile job repairs drift";
    case "UseCheckOrderUnpaid": return "Feign pre-check order unpaid";
    case "UseCommitCoupon": return "DB marks coupon used";
    case "UseRecheckOrder": return "Feign post-recheck (or release)";
    case "PayOrder": return "order paid";
    case "CancelOrder": return "order cancelled (+ emit cancel refund)";
    case "CancelRefundCoupon": return "cancel consumer refunds coupon";
    case "CouponTimePass": return "coupon expires";
    case "ExpireJob": return "expire job runs";
    case "Done": return null;
    default: return action;
  }
};
