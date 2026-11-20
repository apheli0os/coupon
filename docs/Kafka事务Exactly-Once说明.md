# Kafka 事务（Exactly-Once）可选能力

## 当前默认：at-least-once + 幂等

系统默认**不开启事务**，用以下组合保证正确性：
- 生产者：`acks=all` + `enable.idempotence=true`（防重复、防乱序）
- 消费者：业务幂等（唯一索引 / Redis Set）
- 兜底：15 分钟定时对账 + DLQ

这套方案性能高、运维简单，是绝大多数场景的正确选择。**事务不是必须的。**

## 什么时候需要事务

只有当业务要求「**处理结果 + 发送的消息 + 消费位移**三者原子提交」时才需要。典型场景：
- 消费订单消息 → 写本地 DB → 发"订单已确认"消息 → 若任一步失败，全部回滚、消息重投
- 需要严格 Exactly-Once 语义，不能靠下游幂等兜底

## 开启方式

```yaml
kafka:
  transaction:
    enabled: true
    id-prefix: mall-tx-
```

开启后自动装配：
- `transactionalProducerFactory`：带 `transactional.id` 的生产者工厂（幂等自动开启）
- `transactionalKafkaTemplate`：事务型模板（bean 名 `transactionalKafkaTemplate`，不影响默认注入的普通 template）
- `kafkaTransactionManager`：事务管理器
- `KafkaTransactionTemplate`：业务封装工具类

## 使用示例

### 生产端：多条消息原子发送

```java
@Autowired KafkaTransactionTemplate kafkaTxTemplate;

kafkaTxTemplate.sendInTransaction(
    new ProducerRecord<>("topic-a", key1, value1),
    new ProducerRecord<>("topic-b", key2, value2)
);
// 两条消息要么都成功，要么都失败
```

### 消费端：read-process-write 原子提交

```java
@Autowired KafkaTransactionTemplate kafkaTxTemplate;

@KafkaListener(topics = "orders", groupId = "order-eos-group", properties = {"ackMode:MANUAL"})
public void listen(ConsumerRecord<String, OrderMsg> record) {
    kafkaTxTemplate.processAndProceed(record, "order-eos-group", r -> {
        orderService.create(r.value());  // 处理：写 DB
        return new ProducerRecord<>("order-confirmed", r.key(), r.value()); // 产出
    });
    // 事务成功：产出消息 + 消费位移（sendOffsetsToTransaction）一起提交
    // 事务失败：全部回滚，消息重新消费
}
```

**关键配套**：使用 `processAndProceed` 时，监听器必须是 `ack-mode=manual`，位移由事务统一提交。

## 限制与注意事项

1. **性能开销**：事务有额外协调成本（事务协调器 RPC），吞吐比普通发送低 10-20%。别全量开启，只在需要的链路用。
2. **`transactional.id` 必须唯一**：同一消费者组的多实例要用不同前缀（如加实例序号），否则互相干扰。`KafkaTransactionConfig` 里默认用 `idPrefix + appName`，多实例部署时需通过环境变量覆盖。
3. **Broker 要求**：`transaction.state.log.replication.factor >= 3`、`min.insync.replicas >= 2`（我们的 HA 栈已满足）。
4. **不是万能的**：Kafka 事务保证的是 Kafka 内部的原子性。如果处理逻辑写的是 MySQL，DB 写入和 Kafka 事务之间**仍不是分布式事务**——需要 outbox 模式或本地事务表才能真正跨系统一致。当前用幂等+对账兜底就是这个原因。
5. **默认关闭**：未开启时 `KafkaTransactionTemplate` 和事务相关 bean 都不装配，零开销、零影响。

## 结论

这个能力是"工具备好，按需开启"——系统默认仍用更简单可靠的方式运行。真遇到必须严格 Exactly-Once 的场景，按上面配置开启即可，不用改架构。
