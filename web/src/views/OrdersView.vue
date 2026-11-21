<template>
  <div class="page">
    <div class="page-header">
      <div><h1 class="page-title">订单查询</h1><div class="page-subtitle">按订单号查询秒杀订单，支持支付与取消</div></div>
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
              <el-descriptions-item label="活动 ID">{{ order.activityId }}</el-descriptions-item>
              <el-descriptions-item label="秒杀价">¥{{ order.seckillPrice }} × {{ order.quantity ?? 1 }}</el-descriptions-item>
              <el-descriptions-item label="应付">¥{{ order.totalAmount }}</el-descriptions-item>
              <el-descriptions-item label="状态"><el-tag :type="order.status === 1 ? 'success' : order.status === 0 ? 'warning' : 'info'">{{ ORDER_STATUS[order.status] || order.status }}</el-tag></el-descriptions-item>
              <el-descriptions-item label="用户">{{ order.userId }}</el-descriptions-item>
              <el-descriptions-item label="创建时间">{{ fmt(order.createTime) }}</el-descriptions-item>
              <el-descriptions-item label="支付时间">{{ fmt(order.payTime) }}</el-descriptions-item>
            </el-descriptions>
            <div class="toolbar" style="margin-top:16px">
              <el-button v-if="order.status === 0" type="primary" :loading="acting" @click="act('pay')">支付订单</el-button>
              <el-button v-if="order.status === 0" :loading="acting" @click="act('cancel')">取消订单</el-button>
              <el-button @click="search">刷新</el-button>
            </div>
          </template>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getOrder, payOrder, cancelOrder } from '@/api'
import { ORDER_STATUS } from '@/types'
import type { SeckillOrder } from '@/types'

const route = useRoute()
const orderId = ref((route.query.orderId as string) || '')
const order = ref<SeckillOrder | null>(null)
const loading = ref(false)
const acting = ref(false)

function fmt(v?: string) { return v ? v.replace('T', ' ').slice(0, 19) : '-' }
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
onMounted(() => { if (orderId.value) search() })
</script>
