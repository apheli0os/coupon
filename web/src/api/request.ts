import axios, { type AxiosInstance, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
import type { R } from '@/types'

const TOKEN_KEY = 'mall_admin_token'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) || ''
}
export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}
export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

const http: AxiosInstance = axios.create({
  baseURL: '',
  timeout: 15000,
})

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (response: AxiosResponse<R<unknown>>) => {
    const body = response.data
    // 网关/服务错误通常是非 2xx，走 error 分支；业务码非 0 在这里统一提示
    if (body && typeof body === 'object' && 'code' in body && body.code !== 0) {
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return response
  },
  (error) => {
    const status = error?.response?.status
    const msg = error?.response?.data?.message || error?.message || '网络异常'
    if (status === 401) {
      clearToken()
      ElMessage.warning('登录已失效，请重新登录')
      // 与 createWebHashHistory 保持一致，跳转到 hash 登录路由
      setTimeout(() => { window.location.hash = '#/login' }, 400)
    } else {
      ElMessage.error(msg)
    }
    return Promise.reject(error)
  },
)

/** 取出业务 data */
export async function request<T>(config: Parameters<AxiosInstance['request']>[0]): Promise<T> {
  const res = await http.request<R<T>>(config)
  return res.data.data
}

export default http
