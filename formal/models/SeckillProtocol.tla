---- MODULE SeckillProtocol ----
EXTENDS Naturals, FiniteSets

CONSTANTS TotalStock, MaxOrders

VARIABLES
  stock,          \* Redis stock bucket (0..TotalStock)
  inUsers,        \* BOOLEAN: userId in seckill:{id}:users Set
  tokens,         \* [1..MaxOrders -> "none" | "raw" | "proc" | "cons"] (per-orderId key!)
  rollbackGuard,  \* subset of 1..MaxOrders: seckill:{id}:rollback:{oid}
  orders,         \* [1..MaxOrders -> "none" | "unpaid" | "paid" | "cancelled"]
  restored,       \* [1..MaxOrders -> BOOLEAN]
  pendingSend,    \* 0..MaxOrders (producer in-flight after Lua deduct)
  mqOrder,        \* subset of 1..MaxOrders: pending in seckill-topic for OrderService
  redeliver,      \* subset of 1..MaxOrders: already-committed order redelivered by Kafka
  mqSold,         \* subset of 1..MaxOrders: pending in seckill-order-created-topic (published AFTER DB insert!)
  mqCancel,       \* subset of 1..MaxOrders: pending in seckill-cancel-topic for SoldConsumer
  soldState,      \* [1..MaxOrders -> "none" | "sold" | "cancelled"] (seckill_sold_state.lua under per-activity lock)
  soldCount,      \* 0..3: DB seckill_activity.sold_count
  nextId          \* 1..MaxOrders+1

vars == <<stock, inUsers, tokens, rollbackGuard, orders, restored, pendingSend,
          mqOrder, redeliver, mqSold, mqCancel, soldState, soldCount, nextId>>

ActiveOrders == Cardinality({o \in 1..MaxOrders : orders[o] \in {"unpaid", "paid"}})
HasActiveOrder == \E o \in 1..MaxOrders : orders[o] \in {"unpaid", "paid"}

Init ==
  /\ stock = TotalStock
  /\ inUsers = FALSE
  /\ tokens = [o \in 1..MaxOrders |-> "none"]
  /\ rollbackGuard = {}
  /\ orders = [o \in 1..MaxOrders |-> "none"]
  /\ restored = [o \in 1..MaxOrders |-> FALSE]
  /\ pendingSend = 0
  /\ mqOrder = {}
  /\ redeliver = {}
  /\ mqSold = {}
  /\ mqCancel = {}
  /\ soldState = [o \in 1..MaxOrders |-> "none"]
  /\ soldCount = 0
  /\ nextId = 1

\* 1. User calls seckill(): seckill.lua deducts stock + SADD users + set per-orderId token
UserBuy ==
  /\ pendingSend = 0
  /\ nextId <= MaxOrders
  /\ ~inUsers
  /\ stock > 0
  /\ stock' = stock - 1
  /\ inUsers' = TRUE
  /\ tokens' = [tokens EXCEPT ![nextId] = "raw"]
  /\ pendingSend' = nextId
  /\ nextId' = nextId + 1
  /\ UNCHANGED <<rollbackGuard, orders, restored, mqOrder, redeliver, mqSold, mqCancel, soldState, soldCount>>

\* 2a. Kafka send ack succeeds (NOTE: mqSold is NOT emitted here anymore!)
KafkaSendAck ==
  /\ pendingSend # 0
  /\ mqOrder' = mqOrder \cup {pendingSend}
  /\ pendingSend' = 0
  /\ UNCHANGED <<stock, inUsers, tokens, rollbackGuard, orders, restored, redeliver, mqSold, mqCancel, soldState, soldCount, nextId>>

\* 2b. Kafka send times out AFTER broker already received message -> producer runs seckill_rollback.lua (with rollbackGuard)
KafkaTimeoutAfterBroker ==
  /\ pendingSend # 0
  /\ LET oid == pendingSend IN
     /\ mqOrder' = mqOrder \cup {oid}
     /\ pendingSend' = 0
     /\ IF tokens[oid] = "raw"
        THEN /\ tokens' = [tokens EXCEPT ![oid] = "none"]
             /\ IF oid \notin rollbackGuard
                THEN /\ rollbackGuard' = rollbackGuard \cup {oid}
                     /\ IF inUsers
                        THEN /\ inUsers' = FALSE
                             /\ stock' = stock + 1
                        ELSE UNCHANGED <<inUsers, stock>>
                ELSE UNCHANGED <<rollbackGuard, inUsers, stock>>
        ELSE UNCHANGED <<stock, inUsers, tokens, rollbackGuard>>
  /\ UNCHANGED <<orders, restored, redeliver, mqSold, mqCancel, soldState, soldCount, nextId>>

\* 2c. Per-order token TTL expires while message sits in Kafka backlog
TokenTtlExpire(oid) ==
  /\ tokens[oid] = "raw"
  /\ pendingSend # oid
  /\ tokens' = [tokens EXCEPT ![oid] = "none"]
  /\ UNCHANGED <<stock, inUsers, rollbackGuard, orders, restored, pendingSend, mqOrder, redeliver, mqSold, mqCancel, soldState, soldCount, nextId>>

\* 3a. OrderServiceImpl.createSeckillOrder: normal consume & commit (or expired-token compensation)
OrderConsumeCommit(oid) ==
  /\ oid \in mqOrder
  /\ orders[oid] = "none"
  /\ mqOrder' = mqOrder \ {oid}
  /\ IF tokens[oid] \in {"raw", "proc"}
     THEN IF ~HasActiveOrder
          THEN /\ orders' = [orders EXCEPT ![oid] = "unpaid"]
               /\ tokens' = [tokens EXCEPT ![oid] = "cons"]
               /\ mqSold' = mqSold \cup {oid}
               /\ UNCHANGED <<stock, inUsers, rollbackGuard>>
          ELSE /\ tokens' = [tokens EXCEPT ![oid] = "cons"]
               /\ UNCHANGED <<orders, mqSold, stock, inUsers, rollbackGuard>>
     ELSE \* token missing (producer rollback or TTL expiry): run seckill_expire_compensate.lua
          /\ UNCHANGED <<orders, tokens, mqSold>>
          /\ IF tokens[oid] = "none" /\ oid \notin rollbackGuard
             THEN /\ rollbackGuard' = rollbackGuard \cup {oid}
                  /\ IF inUsers
                     THEN /\ inUsers' = FALSE
                          /\ stock' = stock + 1
                     ELSE UNCHANGED <<inUsers, stock>>
             ELSE UNCHANGED <<stock, inUsers, rollbackGuard>>
  /\ UNCHANGED <<restored, pendingSend, redeliver, mqCancel, soldState, soldCount, nextId>>

\* 3b. OrderServiceImpl.createSeckillOrder: crashes after token_claim.lua (PROCESSING) before DB commit
OrderConsumeClaimCrash(oid) ==
  /\ oid \in mqOrder
  /\ orders[oid] = "none"
  /\ tokens[oid] = "raw"
  /\ tokens' = [tokens EXCEPT ![oid] = "proc"]
  /\ UNCHANGED <<stock, inUsers, rollbackGuard, orders, restored, pendingSend, mqOrder, redeliver, mqSold, mqCancel, soldState, soldCount, nextId>>

\* 3c. Kafka at-least-once redelivery of an already-inserted order
KafkaRedeliver(oid) ==
  /\ orders[oid] # "none"
  /\ oid \notin redeliver
  /\ redeliver' = redeliver \cup {oid}
  /\ UNCHANGED <<stock, inUsers, tokens, rollbackGuard, orders, restored, pendingSend, mqOrder, mqSold, mqCancel, soldState, soldCount, nextId>>

OrderConsumeRedeliver(oid) ==
  /\ oid \in redeliver
  /\ orders[oid] # "none"
  /\ IF orders[oid] # "cancelled"
     THEN tokens' = [tokens EXCEPT ![oid] = "cons"]
     ELSE UNCHANGED tokens
  /\ redeliver' = redeliver \ {oid}
  /\ UNCHANGED <<stock, inUsers, rollbackGuard, orders, restored, pendingSend, mqOrder, mqSold, mqCancel, soldState, soldCount, nextId>>

\* 4. User pays order
PayOrder(oid) ==
  /\ orders[oid] = "unpaid"
  /\ orders' = [orders EXCEPT ![oid] = "paid"]
  /\ UNCHANGED <<stock, inUsers, tokens, rollbackGuard, restored, pendingSend, mqOrder, redeliver, mqSold, mqCancel, soldState, soldCount, nextId>>

\* 5. User or Timeout Job cancels unpaid order (DB commit: status=2, stock_restored=0)
CancelOrder(oid) ==
  /\ orders[oid] = "unpaid"
  /\ orders' = [orders EXCEPT ![oid] = "cancelled"]
  /\ restored' = [restored EXCEPT ![oid] = FALSE]
  /\ UNCHANGED <<stock, inUsers, tokens, rollbackGuard, pendingSend, mqOrder, redeliver, mqSold, mqCancel, soldState, soldCount, nextId>>

\* 6a. Cancel restore succeeds completely (Lua + markStockRestored + publishCancelEvent)
RestoreStockSuccess(oid) ==
  /\ orders[oid] = "cancelled"
  /\ ~restored[oid]
  /\ IF oid \notin rollbackGuard
     THEN /\ rollbackGuard' = rollbackGuard \cup {oid}
          /\ tokens' = [tokens EXCEPT ![oid] = "none"]
          /\ IF inUsers
             THEN /\ inUsers' = FALSE
                  /\ stock' = stock + 1
             ELSE UNCHANGED <<inUsers, stock>>
     ELSE UNCHANGED <<rollbackGuard, tokens, inUsers, stock>>
  /\ restored' = [restored EXCEPT ![oid] = TRUE]
  /\ mqCancel' = mqCancel \cup {oid}
  /\ UNCHANGED <<orders, pendingSend, mqOrder, redeliver, mqSold, soldState, soldCount, nextId>>

\* 6b. Cancel restore crashes AFTER Lua rollback, BEFORE markStockRestored
RestoreStockCrashBeforeDb ==
  \E oid \in 1..MaxOrders :
    /\ orders[oid] = "cancelled"
    /\ ~restored[oid]
    /\ oid \notin rollbackGuard
    /\ rollbackGuard' = rollbackGuard \cup {oid}
    /\ tokens' = [tokens EXCEPT ![oid] = "none"]
    /\ IF inUsers
       THEN /\ inUsers' = FALSE
            /\ stock' = stock + 1
       ELSE UNCHANGED <<inUsers, stock>>
    /\ UNCHANGED <<orders, restored, pendingSend, mqOrder, redeliver, mqSold, mqCancel, soldState, soldCount, nextId>>

\* 7. SeckillSoldConsumer.onSoldBatch & onCancelBatch (seckill_sold_state.lua under per-activity RLock)
SoldConsume(oid) ==
  /\ oid \in mqSold
  /\ mqSold' = mqSold \ {oid}
  /\ IF soldState[oid] = "none"
     THEN /\ soldState' = [soldState EXCEPT ![oid] = "sold"]
          /\ soldCount' = soldCount + 1
     ELSE UNCHANGED <<soldState, soldCount>>
  /\ UNCHANGED <<stock, inUsers, tokens, rollbackGuard, orders, restored, pendingSend, mqOrder, redeliver, mqCancel, nextId>>

CancelSoldConsume(oid) ==
  /\ oid \in mqCancel
  /\ mqCancel' = mqCancel \ {oid}
  /\ IF soldState[oid] = "sold"
     THEN /\ soldState' = [soldState EXCEPT ![oid] = "cancelled"]
          /\ soldCount' = IF soldCount > 0 THEN soldCount - 1 ELSE 0
     ELSE IF soldState[oid] = "none"
     THEN /\ soldState' = [soldState EXCEPT ![oid] = "cancelled"]
          /\ UNCHANGED soldCount
     ELSE UNCHANGED <<soldState, soldCount>>
  /\ UNCHANGED <<stock, inUsers, tokens, rollbackGuard, orders, restored, pendingSend, mqOrder, redeliver, mqSold, nextId>>

Done ==
  /\ pendingSend = 0
  /\ mqOrder = {}
  /\ redeliver = {}
  /\ mqSold = {}
  /\ mqCancel = {}
  /\ ~\E oid \in 1..MaxOrders : orders[oid] = "cancelled" /\ ~restored[oid]
  /\ UNCHANGED vars

Next ==
  \/ UserBuy
  \/ KafkaSendAck
  \/ KafkaTimeoutAfterBroker
  \/ \E oid \in 1..MaxOrders : TokenTtlExpire(oid)
  \/ \E oid \in 1..MaxOrders : OrderConsumeCommit(oid)
  \/ \E oid \in 1..MaxOrders : OrderConsumeClaimCrash(oid)
  \/ \E oid \in 1..MaxOrders : KafkaRedeliver(oid)
  \/ \E oid \in 1..MaxOrders : OrderConsumeRedeliver(oid)
  \/ \E oid \in 1..MaxOrders : PayOrder(oid)
  \/ \E oid \in 1..MaxOrders : CancelOrder(oid)
  \/ \E oid \in 1..MaxOrders : RestoreStockSuccess(oid)
  \/ RestoreStockCrashBeforeDb
  \/ \E oid \in 1..MaxOrders : SoldConsume(oid)
  \/ \E oid \in 1..MaxOrders : CancelSoldConsume(oid)
  \/ Done

\* ===== Invariants =====

\* 1. Never oversell
NoOversell == ActiveOrders <= TotalStock

\* 2. When system reaches quiescent state, Redis stock + active orders == TotalStock (no phantom stock leak!)
StockConservedAtRest ==
  (pendingSend = 0 /\ mqOrder = {} /\ ~\E o \in 1..MaxOrders : orders[o] = "cancelled" /\ ~restored[o])
    => (stock + ActiveOrders = TotalStock /\ (inUsers <=> ActiveOrders > 0))

\* 3. At rest, DB sold_count equals actual active (unpaid + paid) orders
SoldCountAccurateAtRest ==
  (pendingSend = 0 /\ mqOrder = {} /\ mqSold = {} /\ mqCancel = {} /\ ~\E o \in 1..MaxOrders : orders[o] = "cancelled" /\ ~restored[o])
    => (soldCount = ActiveOrders)

====
