package com.mall.common.constants;

public final class CommonConstant {

    private CommonConstant() {
    }

    /** 演示环境用户直通请求头；生产环境由网关覆盖注入 */
    public static final String USER_ID_HEADER = "X-User-Id";
    /** 用户角色请求头，由网关从 JWT 解析并覆盖注入 */
    public static final String USER_ROLE_HEADER = "X-User-Role";
    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";

    public static final String MQ_TAG_SECKILL_ORDER = "seckill-order";
    public static final String MQ_TAG_ORDER_TIMEOUT = "order-timeout";
    public static final String MQ_TOPIC_COUPON = "coupon-topic";
    public static final String MQ_TOPIC_SECKILL = "seckill-topic";
    /** 死信队列：消费重试耗尽后的消息在这里兜底告警 */
    public static final String MQ_DLQ_SECKILL_ORDER = "%DLQ%order-seckill-consumer-group";
    public static final String MQ_GROUP_SECKILL_ORDER = "order-seckill-consumer-group";
    public static final String MQ_GROUP_COUPON_EXPIRE = "coupon-expire-consumer-group";

    /* ---------------- Kafka 主链路 ---------------- */
    /** 领券异步落库 topic */
    public static final String MQ_TOPIC_COUPON_CLAIM = "coupon-claim-topic";
    public static final String MQ_GROUP_COUPON_CLAIM = "coupon-claim-consumer-group";
    /** 秒杀订单消费失败重试耗尽后的死信 topic */
    public static final String MQ_TOPIC_SECKILL_DLQ = "seckill-topic.DLQ";
    /** 秒杀订单取消事件 topic（供秒杀服务异步扣减展示销量 sold_count） */
    public static final String MQ_TOPIC_SECKILL_CANCEL = "seckill-cancel-topic";
    public static final String MQ_GROUP_SECKILL_SOLD = "seckill-sold-counter-group";
}
