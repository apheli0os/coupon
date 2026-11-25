export interface R<T> {
  code: number
  message: string
  data: T
  timestamp?: string
}

export interface PageResult<T> {
  current: string | number
  size: string | number
  total: string | number
  pages: string | number
  records: T[]
}

export interface CouponTemplate {
  id: string
  name: string
  type: number
  discountValue: number
  maxDiscountAmount?: number
  threshold: number
  total: number
  received: number
  perUserLimit: number
  validDays: number
  status: number
  target?: number
  productCode?: string
  startTime: string
  endTime: string
  createTime?: string
}

export interface CouponTemplateCreate {
  name: string
  type: number
  discountValue: number
  maxDiscountAmount?: number
  threshold?: number
  total: number
  perUserLimit: number
  validDays: number
  target?: number
  productCode?: string
  startTime: string
  endTime: string
}

export interface CouponTemplatePageQuery {
  pageNum?: number
  pageSize?: number
  name?: string
  type?: number
  status?: number
  target?: number
  productCode?: string
}

export interface UserCoupon {
  id: string
  templateId: string
  userId: string
  couponName?: string
  type?: number
  discountValue?: number
  maxDiscountAmount?: number
  threshold?: number
  target?: number
  productCode?: string
  status: number
  createTime: string
  expireTime?: string
  useTime?: string
  orderId?: string
}

export interface CouponQuoteItem {
  userCouponId: string
  templateId: string
  couponName: string
  type: number
  discountValue: number
  maxDiscountAmount?: number | null
  threshold: number
  target: number
  productCode?: string | null
  expireTime: string
  discountAmount?: number | null
  payAmount?: number | null
  available: boolean
  unavailableReason?: string | null
}

export interface CouponQuoteResponse {
  availableCoupons: CouponQuoteItem[]
  unavailableCoupons: CouponQuoteItem[]
}

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

export interface SeckillResult {
  orderId: string
  orderStatus: number
  statusDesc: string
  productName: string
  seckillPrice: number
  totalAmount: number
  message: string
}

export interface SeckillOrder {
  orderId: string
  userId: string
  activityId: string
  productName: string
  productCode?: string
  seckillPrice: number
  quantity?: number
  totalAmount: number
  couponId?: string
  discountAmount?: number
  payAmount?: number
  stockBucket?: number
  status: number
  stockRestored?: number
  createTime: string
  payTime?: string
}

export const COUPON_STATUS: Record<number, string> = { 0: '草稿', 1: '进行中', 2: '已结束' }
export const COUPON_TARGET: Record<number, string> = { 0: '全场通用', 1: '指定商品' }
export const USER_COUPON_STATUS: Record<number, string> = { 0: '未使用', 1: '已使用', 2: '已过期', 3: '锁定中' }
export const ACTIVITY_STATUS: Record<number, string> = { 0: '未发布', 1: '进行中', 2: '已结束' }
export const ORDER_STATUS: Record<number, string> = { 0: '待支付', 1: '已支付', 2: '已取消' }
