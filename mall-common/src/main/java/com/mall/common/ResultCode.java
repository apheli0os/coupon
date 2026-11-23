package com.mall.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 全局状态码。约定：1xxx 优惠券域，2xxx 秒杀域，3xxx 订单域，9xxx 系统/通用。
 */
@Getter
@AllArgsConstructor
public enum ResultCode {

    SUCCESS(0, "success"),

    /* 通用 */
    PARAM_ERROR(400, "参数校验失败"),
    UNAUTHORIZED(401, "未登录或登录已失效"),
    FORBIDDEN(403, "无权访问"),
    NOT_FOUND(404, "资源不存在"),
    TOO_MANY_REQUESTS(429, "请求过于频繁，请稍后再试"),
    SYSTEM_ERROR(500, "系统繁忙，请稍后重试"),

    /* 优惠券 1xxx */
    COUPON_TEMPLATE_NOT_FOUND(1001, "优惠券模板不存在"),
    COUPON_NOT_STARTED(1002, "活动未开始"),
    COUPON_ENDED(1003, "活动已结束"),
    COUPON_SOLD_OUT(1004, "优惠券已抢光"),
    COUPON_LIMIT_EXCEEDED(1005, "超出每人限领数量"),
    COUPON_ALREADY_USED(1006, "优惠券已使用"),
    COUPON_EXPIRED(1007, "优惠券已过期"),
    COUPON_NOT_OWNED(1008, "优惠券不属于当前用户"),
    COUPON_THRESHOLD_NOT_MET(1009, "未满足使用门槛"),
    COUPON_LOCKED(1010, "优惠券已锁定在待支付订单中"),
    COUPON_PRODUCT_NOT_MATCHED(1011, "优惠券不适用于当前商品"),
    COUPON_SETTLEMENT_CONFLICT(1012, "优惠券结算单状态冲突"),

    /* 秒杀 2xxx */
    SECKILL_ACTIVITY_NOT_FOUND(2001, "秒杀活动不存在"),
    SECKILL_NOT_STARTED(2002, "秒杀尚未开始"),
    SECKILL_ENDED(2003, "秒杀已结束"),
    SECKILL_SOLD_OUT(2004, "商品已抢光"),
    SECKILL_DUPLICATE(2005, "您已参与过该秒杀活动"),
    SECKILL_SYSTEM_BUSY(2006, "秒杀系统繁忙，请重试"),

    /* 订单 3xxx */
    ORDER_NOT_FOUND(3001, "订单不存在"),
    ORDER_CREATE_FAILED(3002, "订单创建失败"),
    ORDER_STATUS_INVALID(3003, "订单状态不允许该操作"),
    ORDER_COUPON_ALREADY_APPLIED(3004, "订单已绑定优惠券"),
    ORDER_AMOUNT_MISMATCH(3005, "订单折后金额校验不一致"),

    /* 基础设施 9xxx */
    LOCK_ACQUIRE_FAILED(9001, "系统繁忙，请稍后重试"),
    MQ_SEND_FAILED(9002, "消息发送失败，请稍后重试");

    private final int code;
    private final String message;
}
