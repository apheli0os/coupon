<template>
  <div class="page">
    <div class="page-header">
      <div><h1 class="page-title">订单查询</h1><div class="page-subtitle">按订单号查询秒杀订单，支持选券试算、支付与取消</div></div>
    </div>
    <el-row :gutter="16">
      <el-col :xs="24" :md="10">
        <div class="card">
          <h3 style="margin-top:0">查询订单</h3>
          <el-input v-model="orderId" placeholder="输入订单号" clearable @keyup.enter="search">
            <template #append><el-button @click="search" :loading="loading">查询</el-button></template>
          </el-input>
          <div class="text-muted" style="font-size:12px;margin-top:12px;line-height:1.8">
            · 订单号从「秒杀抢购」结果中获取（<code>orderId</code>）。<br/>
            · 未支付订单会由定时关单任务自动关闭并归还库存。<br/>
            · 订单通过 Kafka 异步落库，提交后可能有秒级延迟。
          </div>
        </div>
      </el-col>
      <el-col :xs="24" :md="14">
        <div class="card">
          <div class="page-header"><h3 style="margin:0">订单详情</h3></div>
          <div v-if="!order" class="text-muted" style="padding:32px 0;text-align:center">输入订单号查询</div>
          <template v-else>
            <el-descriptions :column="2" border>
              <el-descriptions-item label="订单号" :span="2"><span class="code">{{ order.orderId }}</span></el-descriptions-item>
              <el-descriptions-item label="商品">{{ order.productName }}</el-descriptions-item>
              <el-descriptions-item label="商品编码">{{ order.productCode || '-' }}</el-descriptions-item>
              <el-descriptions-item label="活动 ID">{{ order.activityId }}</el-descriptions-item>
              <el-descriptions-item label="秒杀价">¥{{ order.seckillPrice }} × {{ order.quantity ?? 1 }}</el-descriptions-item>
              <el-descriptions-item label="订单总额">¥{{ order.totalAmount }}</el-descriptions-item>
              <el-descriptions-item label="优惠券">{{ order.couponId || '未使用' }}</el-descriptions-item>
              <el-descriptions-item label="优惠立减">-¥{{ order.discountAmount ?? 0 }}</el-descriptions-item>
              <el-descriptions-item label="实付金额" :span="2"><span class="payable">¥{{ order.payAmount ?? order.totalAmount }}</span></el-descriptions-item>
              <el-descriptions-item label="状态"><el-tag :type="order.status === 1 ? 'success' : order.status === 0 ? 'warning' : 'info'">{{ ORDER_STATUS[order.status] || order.status }}</el-tag></el-descriptions-item>
              <el-descriptions-item label="用户">{{ order.userId }}</el-descriptions-item>
              <el-descriptions-item label="创建时间">{{ fmt(order.createTime) }}</el-descriptions-item>
              <el-descriptions-item label="支付时间">{{ fmt(order.payTime) }}</el-descriptions-item>
            </el-descriptions>
            <div class="toolbar" style="margin-top:16px">
              <el-button v-if="order.status === 0 && !order.couponId" type="primary" @click="openQuote">选择优惠券</el-button>
              <el-button v-if="order.status === 0" type="primary" :loading="acting" @click="act('pay')">支付订单</el-button>
              <el-button v-if="order.status === 0" :loading="acting" @click="act('cancel')">取消订单</el-button>
              <el-button @click="search">刷新</el-button>
            </div>
          </template>
        </div>
      </el-col>
    </el-row>

    <el-dialog v-model="quoteVisible" title="收银台 · 选择优惠券" width="820px">
      <div v-loading="quoting">
        <div v-if="quote && order">
          <div class="quote-summary">
            <span>订单金额 <b>¥{{ order.totalAmount }}</b></span>
            <span>商品编码 <b>{{ order.productCode || '-' }}</b></span>
          </div>
          <h4>可用优惠券（已按最优推荐排序）</h4>
          <div v-if="quote.availableCoupons.length === 0" class="text-muted">当前订单暂无可用优惠券</div>
          <div v-for="(item, index) in quote.availableCoupons" :key="item.userCouponId" class="coupon-item coupon-available">
            <div class="coupon-main">
              <el-tag v-if="index === 0" type="success" size="small" effect="dark">推荐最优</el-tag>
              <span class="coupon-name">{{ item.couponName }}</span>
              <el-tag size="small" :type="item.target === 1 ? 'warning' : 'info'">{{ COUPON_TARGET[item.target] || '全场通用' }}</el-tag>
              <span v-if="item.productCode" class="code">{{ item.productCode }}</span>
              <span class="text-muted">{{ ruleText(item) }}</span>
              <span class="text-muted">有效期至 {{ fmt(item.expireTime) }}</span>
            </div>
            <div class="coupon-amount">
              <div>立减 <b class="discount">-¥{{ item.discountAmount }}</b></div>
              <div>折后实付 <b class="payable">¥{{ item.payAmount }}</b></div>
            </div>
            <el-button type="primary" size="small" :loading="bindingId === item.userCouponId" @click="bind(item.userCouponId)">使用此券</el-button>
          </div>
          <h4>不可用优惠券</h4>
          <div v-if="quote.unavailableCoupons.length === 0" class="text-muted">无</div>
          <div v-for="item in quote.unavailableCoupons" :key="item.userCouponId" class="coupon-item coupon-unavailable">
            <div class="coupon-main">
              <span class="coupon-name">{{ item.couponName }}</span>
              <span class="text-muted">{{ ruleText(item) }}</span>
            </div>
            <span class="unavailable-reason">{{ item.unavailableReason || '不可用' }}</span>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getOrder, payOrder, cancelOrder, quoteCoupons, applyOrderCoupon } from '@/api'
import { ORDER_STATUS, COUPON_TARGET } from '@/types'
import type { SeckillOrder, CouponQuoteResponse, CouponQuoteItem } from '@/types'

const route = useRoute()
const orderId = ref((route.query.orderId as string) || '')
const order = ref<SeckillOrder | null>(null)
const loading = ref(false)
const acting = ref(false)
const quoteVisible = ref(false)
const quoting = ref(false)
const quote = ref<CouponQuoteResponse | null>(null)
const bindingId = ref('')

function fmt(v?: string) { return v ? v.replace('T', ' ').slice(0, 19) : '-' }
function ruleText(item: CouponQuoteItem): string {
  if (item.type === 2) {
    const fold = Number((Number(item.discountValue) * 10).toFixed(1))
    const cap = item.maxDiscountAmount ? `，封顶¥${item.maxDiscountAmount}` : ''
    return `${fold}折${cap}${item.threshold ? `，满${item.threshold}可用` : ''}`
  }
  return `满${item.threshold ?? 0}减${item.discountValue ?? 0}`
}
async function search() {
  if (!orderId.value) { ElMessage.warning('请输入订单号'); return }
  loading.value = true
  try { order.value = await getOrder(orderId.value) } finally { loading.value = false }
}
async function act(op: 'pay' | 'cancel') {
  if (!order.value) return
  acting.value = true
  try {
    if (op === 'pay') await payOrder(order.value.orderId)
    else await cancelOrder(order.value.orderId)
    ElMessage.success(op === 'pay' ? '支付成功' : '订单已取消，库存已归还')
    await search()
  } finally { acting.value = false }
}
async function openQuote() {
  if (!order.value) return
  quoteVisible.value = true
  quoting.value = true
  quote.value = null
  try {
    quote.value = await quoteCoupons({ orderAmount: Number(order.value.totalAmount), productCode: order.value.productCode })
  } finally { quoting.value = false }
}
async function bind(userCouponId: string) {
  if (!order.value) return
  bindingId.value = userCouponId
  try {
    order.value = await applyOrderCoupon(order.value.orderId, userCouponId)
    quoteVisible.value = false
    ElMessage.success('优惠券已锁定到订单，请按实付金额支付')
    await search()
  } finally { bindingId.value = '' }
}
onMounted(() => { if (orderId.value) search() })
</script>

<style scoped>
.payable { color: var(--el-color-danger); font-size: 18px; font-weight: 700; }
.discount { color: var(--el-color-success); }
.quote-summary { display: flex; gap: 24px; padding: 10px 0 16px; font-size: 14px; }
.coupon-item { display: flex; align-items: center; gap: 16px; padding: 12px; margin-bottom: 8px; border-radius: 6px; border: 1px solid var(--el-border-color-lighter); }
.coupon-available { border-left: 3px solid var(--el-color-success); }
.coupon-unavailable { border-left: 3px solid var(--el-color-info); opacity: 0.75; }
.coupon-main { flex: 1; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.coupon-name { font-weight: 600; }
.coupon-amount { text-align: right; font-size: 13px; line-height: 1.7; }
.unavailable-reason { color: var(--el-color-danger); font-size: 13px; }
</style>
