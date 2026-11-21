import { createRouter, createWebHashHistory } from 'vue-router'
import { getToken } from '@/api/request'

const routes = [
  { path: '/login', name: 'Login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'Dashboard', component: () => import('@/views/DashboardView.vue'), meta: { title: '总览' } },
      { path: 'templates', name: 'Templates', component: () => import('@/views/TemplatesView.vue'), meta: { title: '券模板管理' } },
      { path: 'my-coupons', name: 'MyCoupons', component: () => import('@/views/MyCouponsView.vue'), meta: { title: '我的优惠券' } },
      { path: 'activities', name: 'Activities', component: () => import('@/views/ActivitiesView.vue'), meta: { title: '秒杀活动' } },
      { path: 'seckill', name: 'Seckill', component: () => import('@/views/SeckillView.vue'), meta: { title: '秒杀抢购' } },
      { path: 'orders', name: 'Orders', component: () => import('@/views/OrdersView.vue'), meta: { title: '订单查询' } },
      { path: 'monitoring', name: 'Monitoring', component: () => import('@/views/MonitoringView.vue'), meta: { title: '监控中心' } },
    ],
  },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

router.beforeEach((to) => {
  if (!to.meta.public && !getToken()) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.path === '/login' && getToken()) return { path: '/dashboard' }
  return true
})

export default router
