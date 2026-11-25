import { request, getToken } from './request'
import type {
  R, CouponTemplate, CouponTemplateCreate, CouponTemplatePageQuery, PageResult,
  UserCoupon, CouponQuoteResponse,
  SeckillActivity, SeckillActivityCreate, SeckillResult, SeckillOrder,
} from '@/types'

export async function demoLogin(userId: number, role = 'USER'): Promise<string> {
  const res = await request<string>({
    url: `/api/auth/token?userId=${userId}&role=${encodeURIComponent(role)}`,
    method: 'get',
  })
  return res
}

export function health(port = 8080): Promise<unknown> {
  return request({ url: `http://localhost:${port}/actuator/health`, method: 'get' })
}

export const listTemplates = () => request<CouponTemplate[]>({ url: '/api/coupon/templates', method: 'get' })
export const getTemplate = (id: string) => request<CouponTemplate>({ url: `/api/coupon/templates/${id}`, method: 'get' })
export const pageTemplates = (params: CouponTemplatePageQuery) => request<PageResult<CouponTemplate>>({ url: '/api/coupon/templates/page', method: 'get', params })
export const createTemplate = (data: CouponTemplateCreate) => request<string>({ url: '/api/coupon/templates', method: 'post', data })
export const publishTemplate = (id: string) => request<void>({ url: `/api/coupon/templates/${id}/publish`, method: 'post' })
export const increaseTemplateStock = (id: string, delta: number) => request<void>({ url: `/api/coupon/templates/${id}/increase-stock`, method: 'post', data: { delta } })
export const terminateTemplate = (id: string) => request<void>({ url: `/api/coupon/templates/${id}/terminate`, method: 'post' })

export const claimCoupon = (templateId: string) => request<void>({ url: `/api/coupon/claim/${templateId}`, method: 'post' })
export const listMyCoupons = (status?: number) => request<UserCoupon[]>({
  url: '/api/coupon/my', method: 'get', params: status === undefined ? {} : { status },
})
export const quoteCoupons = (data: { orderAmount: number; productCode?: string }) => request<CouponQuoteResponse>({ url: '/api/coupon/quote', method: 'post', data })
export const useCoupon = (data: { userCouponId: string; orderId: string; orderAmount?: number }) =>
  request<void>({ url: '/api/coupon/use', method: 'post', data })

export const listActivities = () => request<SeckillActivity[]>({ url: '/api/seckill/activities', method: 'get' })
export const getActivity = (id: string) => request<SeckillActivity>({ url: `/api/seckill/activities/${id}`, method: 'get' })
export const createActivity = (data: SeckillActivityCreate) => request<string>({ url: '/api/seckill/activities', method: 'post', data })
export const publishActivity = (id: string) => request<void>({ url: `/api/seckill/activities/${id}/publish`, method: 'post' })
export const seckillBuy = (activityId: string) => request<string>({ url: `/api/seckill/${activityId}/buy`, method: 'post' })
export const seckillResult = (activityId: string) => request<SeckillResult>({
  url: '/api/seckill/result', method: 'get', params: { activityId },
})

export const getOrder = (orderId: string) => request<SeckillOrder>({ url: `/api/orders/${orderId}`, method: 'get' })
export const applyOrderCoupon = (orderId: string, userCouponId: string) => request<SeckillOrder>({ url: `/api/orders/${orderId}/apply-coupon`, method: 'post', data: { userCouponId } })
export const payOrder = (orderId: string) => request<void>({ url: `/api/orders/${orderId}/pay`, method: 'post' })
export const cancelOrder = (orderId: string) => request<void>({ url: `/api/orders/${orderId}/cancel`, method: 'post' })

export { getToken }
export type { R }
