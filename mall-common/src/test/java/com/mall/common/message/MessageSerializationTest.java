package com.mall.common.message;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class MessageSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void couponClaimMessage_jsonRoundTrip_shouldPreserveAllFields() throws Exception {
        CouponClaimMessage msg = new CouponClaimMessage();
        msg.setId(123456L);
        msg.setUserId(999L);
        msg.setTemplateId(888L);
        msg.setCouponName("满100减20");
        msg.setType(1);
        msg.setDiscountValue(new BigDecimal("20.00"));
        msg.setThreshold(new BigDecimal("100.00"));
        msg.setStatus(0);
        msg.setExpireTime(LocalDateTime.of(2026, 12, 31, 23, 59, 59));
        msg.setCreateTime(LocalDateTime.of(2026, 11, 19, 10, 0, 0));

        String json = mapper.writeValueAsString(msg);
        CouponClaimMessage back = mapper.readValue(json, CouponClaimMessage.class);

        assertEquals(msg.getId(), back.getId());
        assertEquals(msg.getUserId(), back.getUserId());
        assertEquals(msg.getCouponName(), back.getCouponName());
        assertEquals(0, msg.getDiscountValue().compareTo(back.getDiscountValue()));
        assertEquals(msg.getExpireTime(), back.getExpireTime());
        assertEquals(msg.getCreateTime(), back.getCreateTime());
    }

    @Test
    void seckillOrderMessage_jsonRoundTrip_shouldPreserveStockBucket() throws Exception {
        SeckillOrderMessage msg = new SeckillOrderMessage();
        msg.setOrderId("ORDER-123");
        msg.setUserId(1001L);
        msg.setActivityId(2001L);
        msg.setProductName("秒杀商品");
        msg.setSeckillPrice(new BigDecimal("9.90"));
        msg.setQuantity(1);
        msg.setStockBucket(7);
        msg.setCreateTime(LocalDateTime.of(2026, 11, 19, 12, 0, 0));

        String json = mapper.writeValueAsString(msg);
        SeckillOrderMessage back = mapper.readValue(json, SeckillOrderMessage.class);

        assertEquals(msg.getOrderId(), back.getOrderId());
        assertEquals(msg.getStockBucket(), back.getStockBucket(), "库存桶索引必须无损，否则关单无法归还");
        assertEquals(0, msg.getSeckillPrice().compareTo(back.getSeckillPrice()));
        assertEquals(msg.getCreateTime(), back.getCreateTime());
    }
}
