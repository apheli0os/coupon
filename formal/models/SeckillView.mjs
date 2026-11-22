export const title = "Seckill Full Lifecycle (Fixed: Per-Order Token + OrderCreated Event + Sold State Machine)";

export const state = (v) => ({
  Redis: `stock=${v.stock}, users=${v.inUsers ? "1" : "0"}, tok=[${v.tokens[1]},${v.tokens[2]}]`,
  Orders: `o1=${v.orders[1]}, o2=${v.orders[2]}`,
  Sold: `soldCount=${v.soldCount}, st=[${v.soldState[1]},${v.soldState[2]}]`,
});

export const step = (action, from, to) => {
  switch (action) {
    case "UserBuy": return "user buys (Lua deduct + per-order token)";
    case "KafkaSendAck": return "Kafka ack";
    case "KafkaTimeoutAfterBroker": return "Kafka timeout -> guarded rollback";
    case "TokenTtlExpire": return "order token TTL expires";
    case "OrderConsumeCommit": return "order created (+ emit order-created) or expire-compensate";
    case "OrderConsumeClaimCrash": return "consumer crash after token claim";
    case "KafkaRedeliver": return "Kafka redelivers old order";
    case "OrderConsumeRedeliver": return "duplicate order skipped safely";
    case "PayOrder": return "user pays";
    case "CancelOrder": return "order cancelled";
    case "RestoreStockSuccess": return "restore Redis stock";
    case "RestoreStockCrashBeforeDb": return "crash after Lua restore";
    case "SoldConsume": return "sold state machine (SOLD)";
    case "CancelSoldConsume": return "sold state machine (CANCEL)";
    case "Done": return null;
    default: return action;
  }
};
