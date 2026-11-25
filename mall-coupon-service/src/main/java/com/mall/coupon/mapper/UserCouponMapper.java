package com.mall.coupon.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.coupon.domain.entity.UserCoupon;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface UserCouponMapper extends BaseMapper<UserCoupon> {

    /** 核销：仅当未使用且未过期时才能占用，天然幂等 */
    @Update("UPDATE user_coupon SET status = 1, order_id = #{orderId}, use_time = NOW(), update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND status = 0 AND expire_time > NOW()")
    int useCoupon(@Param("id") Long id, @Param("userId") Long userId, @Param("orderId") String orderId);

    @Update("UPDATE user_coupon " +
            "SET status = CASE WHEN expire_time > NOW() THEN 0 ELSE 2 END, " +
            "order_id = NULL, use_time = NULL, update_time = NOW() " +
            "WHERE user_id = #{userId} AND order_id = #{orderId} AND status = 1")
    int refundByOrderAndUser(@Param("userId") Long userId, @Param("orderId") String orderId);

    @Update("UPDATE user_coupon " +
            "SET status = CASE WHEN expire_time > NOW() THEN 0 ELSE 2 END, " +
            "order_id = NULL, use_time = NULL, update_time = NOW() " +
            "WHERE order_id = #{orderId} AND status = 1")
    int refundByOrderId(@Param("orderId") String orderId);

    @Select("SELECT DISTINCT user_id FROM user_coupon WHERE order_id = #{orderId} AND status = 1")
    List<Long> selectUsedUserIdsByOrderId(@Param("orderId") String orderId);

    @Update("UPDATE user_coupon " +
            "SET status = CASE WHEN expire_time > NOW() THEN 0 ELSE 2 END, " +
            "order_id = NULL, use_time = NULL, update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND order_id = #{orderId} AND status = 1")
    int releaseCoupon(@Param("id") Long id, @Param("userId") Long userId, @Param("orderId") String orderId);

    @Update("UPDATE user_coupon SET status = 3, order_id = #{orderId}, update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND status = 0 AND expire_time > NOW()")
    int lockCoupon(@Param("id") Long id, @Param("userId") Long userId, @Param("orderId") String orderId);

    @Update("UPDATE user_coupon SET status = 1, use_time = NOW(), update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND order_id = #{orderId} AND status = 3")
    int settleLockedCoupon(@Param("id") Long id, @Param("userId") Long userId, @Param("orderId") String orderId);

    @Update("UPDATE user_coupon " +
            "SET status = CASE WHEN expire_time > NOW() THEN 0 ELSE 2 END, " +
            "order_id = NULL, use_time = NULL, update_time = NOW() " +
            "WHERE id = #{id} AND user_id = #{userId} AND order_id = #{orderId} AND status = 3")
    int unlockLockedCoupon(@Param("id") Long id, @Param("userId") Long userId, @Param("orderId") String orderId);

    @Select("SELECT * FROM user_coupon WHERE id = #{id} AND user_id = #{userId}")
    UserCoupon selectByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    /** 批量过期任务 */
    @Update("UPDATE user_coupon SET status = 2, update_time = NOW() " +
            "WHERE status = 0 AND expire_time <= #{now} LIMIT 500")
    int expireBatch(@Param("now") LocalDateTime now);

    /** 统计用户在某模板下已领取数量（DB 兜底校验 / 对账用） */
    @Select("SELECT COUNT(1) FROM user_coupon WHERE user_id = #{userId} AND template_id = #{templateId}")
    long countByUserAndTemplate(@Param("userId") Long userId, @Param("templateId") Long templateId);

    @Select("SELECT COUNT(1) FROM user_coupon WHERE template_id = #{templateId}")
    long countByTemplate(@Param("templateId") Long templateId);

    @Select("SELECT user_id AS userId, COUNT(1) AS cnt FROM user_coupon " +
            "WHERE template_id = #{templateId} GROUP BY user_id")
    List<Map<String, Object>> countGroupedByUser(@Param("templateId") Long templateId);

    /**
     * 批量幂等落库：主键为雪花 ID，重复消费时 INSERT IGNORE 直接跳过。
     * 返回实际新增行数，供调用方精确累加模板 received，避免重复消费导致计数虚高。
     */
    @Insert({"<script>",
            "INSERT IGNORE INTO user_coupon (id, user_id, template_id, coupon_name, type, " +
                    "discount_value, max_discount_amount, threshold, target, product_code, " +
                    "status, expire_time, create_time, update_time) VALUES ",
            "<foreach collection='list' item='e' separator=','>",
            "(#{e.id}, #{e.userId}, #{e.templateId}, #{e.couponName}, #{e.type}, " +
                    "#{e.discountValue}, #{e.maxDiscountAmount}, #{e.threshold}, " +
                    "COALESCE(#{e.target}, 0), #{e.productCode}, " +
                    "#{e.status}, #{e.expireTime}, #{e.createTime}, #{e.updateTime})",
            "</foreach>",
            "</script>"})
    int batchInsertIgnore(@Param("list") List<UserCoupon> list);
}
