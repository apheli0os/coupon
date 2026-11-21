import { request, getToken } from './request'
import type {
  R, CouponTemplate, CouponTemplateCreate, UserCoupon,
  SeckillActivity, SeckillActivityCreate, SeckillResult, SeckillOrder,
} from '@/types'

/** 演示登录：dev/local profile 下网关白名单接口，生产需替换为真实登录 */
export async function demoLogin(userId: number, role = 'USER'): Promise<string> {
  const res = await request<string>({
    url: `/api/auth/token?userId=${userId}&role=${encodeURIComponent(role)}`,
    method: 'get',
  })
  return res
}

/** 健康检查（无需 token） */
export function health(port = 8080): Promise<unknown> {
  return request({ url: `http://localhost:${port}/actuator/health`, method: 'get' })
}

// ---------- 券模板 ----------
export const listTemplates = () => request<CouponTemplate[]>({ url: '/api/coupon/templates', method: 'get' })
export const getTemplate = (id: string) => request<CouponTemplate>({ url: `/api/coupon/templates/${id}`, method: 'get' })
export const createTemplate = (data: CouponTemplateCreate) => request<string>({ url: '/api/coupon/templates', method: 'post', data })
export const publishTemplate = (id: string) => request<void>({ url: `/api/coupon/templates/${id}/publish`, method: 'post' })

// ---------- 用户券 ----------
export const claimCoupon = (templateId: string) => request<void>({ url: `/api/coupon/claim/${templateId}`, method: 'post' })
export const listMyCoupons = (status?: number) => request<UserCoupon[]>({
  url: '/api/coupon/my', method: 'get', params: status === undefined ? {} : { status },
})
export const useCoupon = (data: { userCouponId: string; orderId: string; orderAmount?: number }) =>
  request<void>({ url: '/api/coupon/use', method: 'post', data })

// ---------- 秒杀活动 ----------
export const listActivities = () => request<SeckillActivity[]>({ url: '/api/seckill/activities', method: 'get' })
export const getActivity = (id: string) => request<SeckillActivity>({ url: `/api/seckill/activities/${id}`, method: 'get' })
export const createActivity = (data: SeckillActivityCreate) => request<string>({ url: '/api/seckill/activities', method: 'post', data })
export const publishActivity = (id: string) => request<void>({ url: `/api/seckill/activities/${id}/publish`, method: 'post' })
export const seckillBuy = (activityId: string) => request<string>({ url: `/api/seckill/${activityId}/buy`, method: 'post' })
export const seckillResult = (activityId: string) => request<SeckillResult>({
  url: '/api/seckill/result', method: 'get', params: { activityId },
})

// ---------- 订单 ----------
export const getOrder = (orderId: string) => request<SeckillOrder>({ url: `/api/orders/${orderId}`, method: 'get' })
export const payOrder = (orderId: string) => request<void>({ url: `/api/orders/${orderId}/pay`, method: 'post' })
export const cancelOrder = (orderId: string) => request<void>({ url: `/api/orders/${orderId}/cancel`, method: 'post' })

export { getToken }
export type { R }
