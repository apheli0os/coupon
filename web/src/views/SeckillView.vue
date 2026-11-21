<template>
  <div class="page">
    <div class="page-header">
      <div><h1 class="page-title">秒杀抢购</h1><div class="page-subtitle">下单返回订单号，轮询 /api/seckill/result 获取异步落单结果</div></div>
    </div>
    <el-row :gutter="16">
      <el-col :xs="24" :md="12">
        <div class="card">
          <h3 style="margin-top:0">发起抢购</h3>
          <el-form label-width="90px">
            <el-form-item label="活动">
              <el-select v-model="activityId" placeholder="选择进行中的活动" style="width:100%" @change="resetResult">
                <el-option v-for="a in activities.filter(x => x.status === 1)" :key="a.id" :label="`${a.name} (${a.productName}) ¥${a.seckillPrice}`" :value="a.id" />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="danger" size="large" style="width:100%" :loading="buying" :disabled="!activityId" @click="buy">立即抢购</el-button>
            </el-form-item>
          </el-form>
          <el-alert v-if="!activities.some(x => x.status === 1)" type="warning" :closable="false" show-icon
            title="暂无进行中的活动" description="请先到「秒杀活动」创建并发布活动，发布后状态为「进行中」。" />
        </div>
      </el-col>
      <el-col :xs="24" :md="12">
        <div class="card">
          <div class="page-header"><h3 style="margin:0">抢购结果</h3><el-button v-if="activityId" size="small" @click="pollOnce">重新查询</el-button></div>
          <div v-if="!result" class="text-muted" style="padding:32px 0;text-align:center">尚未抢购，或结果已重置</div>
          <template v-else>
            <el-descriptions :column="1" border>
              <el-descriptions-item label="订单号"><span class="code">{{ result.orderId || '-' }}</span></el-descriptions-item>
              <el-descriptions-item label="状态"><el-tag :type="result.orderStatus === 1 ? 'success' : 'warning'">{{ result.statusDesc }}</el-tag></el-descriptions-item>
              <el-descriptions-item label="商品">{{ result.productName }}</el-descriptions-item>
              <el-descriptions-item label="秒杀价">¥{{ result.seckillPrice }}</el-descriptions-item>
              <el-descriptions-item label="应付总额"><b style="color:#e11d48">¥{{ result.totalAmount }}</b></el-descriptions-item>
              <el-descriptions-item label="说明">{{ result.message }}</el-descriptions-item>
            </el-descriptions>
            <el-button v-if="result.orderId" type="primary" style="width:100%;margin-top:14px" @click="$router.push({ path:'/orders', query:{ orderId: result!.orderId } })">查看/处理订单 →</el-button>
          </template>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listActivities, seckillBuy, seckillResult } from '@/api'
import type { SeckillActivity, SeckillResult } from '@/types'

const route = useRoute()
const activities = ref<SeckillActivity[]>([])
const activityId = ref<string>('')
const buying = ref(false)
const result = ref<SeckillResult | null>(null)
let timer: number | undefined
let attempts = 0

async function load() {
  activities.value = await listActivities() || []
  const qid = route.query.activityId as string
  if (qid) activityId.value = qid
  else if (!activityId.value) {
    const active = activities.value.find(a => a.status === 1)
    if (active) activityId.value = active.id
  }
}
function resetResult() { result.value = null; stopPoll() }
function stopPoll() { if (timer) { window.clearInterval(timer); timer = undefined } }

async function pollOnce() {
  if (!activityId.value) return
  try {
    const r = await seckillResult(activityId.value)
    result.value = r || null
    // 一旦 Kafka 消费落库返回有效 orderId，立即停止轮询（新订单状态为 0 待支付）
    if (r && r.orderId) stopPoll()
    if (++attempts >= 20) stopPoll()
  } catch { stopPoll() }
}

async function buy() {
  if (!activityId.value) return
  buying.value = true
  attempts = 0
  try {
    const orderId = await seckillBuy(activityId.value)
    if (orderId) ElMessage.success(`抢购请求已提交：${orderId}`)
    else ElMessage.success('抢购请求已提交')
    await pollOnce()
    stopPoll()
    timer = window.setInterval(pollOnce, 1000)
  } finally { buying.value = false }
}

onMounted(load)
onUnmounted(stopPoll)
</script>
