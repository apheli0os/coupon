/** 统一响应 R<T> */
export interface R<T> {
  code: number
  message: string
  data: T
  timestamp?: string
}

/** 券模板 */
export interface CouponTemplate {
  id: string
  name: string
  type: number
  discountValue: number
  threshold: number
  total: number
  received: number
  perUserLimit: number
  validDays: number
  status: number
  startTime: string
  endTime: string
  createTime?: string
}

export interface CouponTemplateCreate {
  name: string
  type: number
  discountValue: number
  threshold?: number
  total: number
  perUserLimit: number
  validDays: number
  startTime: string
  endTime: string
}

/** 用户优惠券（与后端 UserCoupon 实体字段保持一致） */
export interface UserCoupon {
  id: string
  templateId: string
  userId: string
  couponName?: string
  type?: number
  discountValue?: number
  threshold?: number
  status: number
  createTime: string
  expireTime?: string
  useTime?: string
  orderId?: string
}

/** 秒杀活动（与后端 SeckillActivity 实体字段保持一致） */
export interface SeckillActivity {
  id: string
  name: string
  productCode: string
  productName: string
  seckillPrice: number
  originalPrice: number
  totalStock: number
  soldCount: number
  status: number
  startTime: string
  endTime: string
  createTime?: string
}

export interface SeckillActivityCreate {
  name: string
  productCode: string
  productName: string
  seckillPrice: number
  originalPrice: number
  totalStock: number
  startTime: string
  endTime: string
}

/** 抢购结果（异步轮询） */
export interface SeckillResult {
  orderId: string
  orderStatus: number
  statusDesc: string
  productName: string
  seckillPrice: number
  totalAmount: number
  message: string
}

/** 秒杀订单（与后端 SeckillOrder 实体字段保持一致） */
export interface SeckillOrder {
  orderId: string
  userId: string
  activityId: string
  productName: string
  seckillPrice: number
  quantity?: number
  totalAmount: number
  stockBucket?: number
  status: number
  stockRestored?: number
  createTime: string
  payTime?: string
}

/** 状态字典 */
export const COUPON_STATUS: Record<number, string> = { 0: '草稿', 1: '进行中', 2: '已结束' }
export const USER_COUPON_STATUS: Record<number, string> = { 0: '未使用', 1: '已使用', 2: '已过期' }
export const ACTIVITY_STATUS: Record<number, string> = { 0: '未发布', 1: '进行中', 2: '已结束' }
export const ORDER_STATUS: Record<number, string> = { 0: '待支付', 1: '已支付', 2: '已取消' }
