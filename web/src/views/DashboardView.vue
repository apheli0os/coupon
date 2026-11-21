<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">总览</h1>
        <div class="page-subtitle">优惠券 & 秒杀平台运行状态一览</div>
      </div>
      <el-button :loading="loading" @click="load">刷新数据</el-button>
    </div>

    <div class="stat-grid">
      <div class="stat-card primary"><div class="label">券模板</div><div class="value">{{ templates.length }}</div><div class="hint">已发布 {{ publishedTemplates }} 个</div></div>
      <div class="stat-card success"><div class="label">我的优惠券</div><div class="value">{{ myCoupons.length }}</div><div class="hint">可用 {{ usableCoupons }} 张</div></div>
      <div class="stat-card warning"><div class="label">秒杀活动</div><div class="value">{{ activities.length }}</div><div class="hint">进行中 {{ runningActivities }} 个</div></div>
      <div class="stat-card"><div class="label">累计放券</div><div class="value">{{ totalReceived.toLocaleString() }}</div><div class="hint">全平台模板 received 合计</div></div>
    </div>

    <el-row :gutter="16">
      <el-col :xs="24" :md="14">
        <div class="card">
          <div class="page-header"><div><h3 style="margin:0">快速开始</h3></div></div>
          <el-steps :active="1" direction="vertical" finish-status="success">
            <el-step title="启动后端" description="Windows: .\scripts\mall.ps1 start / Linux: ./scripts/mall.sh start（网关 :8080）" />
            <el-step title="创建券模板" description="在「券模板管理」新建并发布，用户即可领券" />
            <el-step title="发布秒杀活动" description="在「秒杀活动」新建并发布，活动进入进行中" />
            <el-step title="抢购与查看结果" description="在「秒杀抢购」下单，轮询结果后在「订单查询」支付/取消" />
          </el-steps>
        </div>
      </el-col>
      <el-col :xs="24" :md="10">
        <div class="card">
          <div class="page-header"><div><h3 style="margin:0">最近券模板</h3></div><el-link type="primary" @click="$router.push('/templates')">全部 →</el-link></div>
          <el-table :data="templates.slice(0, 5)" size="small" empty-text="暂无数据，先启动后端并创建模板">
            <el-table-column prop="name" label="名称" min-width="110" show-overflow-tooltip />
            <el-table-column prop="total" label="总量" width="70" />
            <el-table-column prop="received" label="已领" width="70" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }"><el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">{{ COUPON_STATUS[row.status] || '未知' }}</el-tag></template>
            </el-table-column>
          </el-table>
        </div>
        <div class="card mt-16">
          <div class="page-header"><div><h3 style="margin:0">监控入口</h3></div><el-link type="primary" @click="$router.push('/monitoring')">前往 →</el-link></div>
          <div class="text-muted" style="font-size:13px;line-height:1.9">
            Prometheus 抓取各服务 <code>/actuator/prometheus</code>，Grafana 展示 QPS / P99 / JVM / 业务指标 / Kafka / Redis。<br/>
            本地一键启动：<code>docker compose -f docker-compose-monitoring.yml up -d</code>
          </div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { listTemplates, listMyCoupons, listActivities } from '@/api'
import { COUPON_STATUS } from '@/types'
import type { CouponTemplate, UserCoupon, SeckillActivity } from '@/types'

const loading = ref(false)
const templates = ref<CouponTemplate[]>([])
const myCoupons = ref<UserCoupon[]>([])
const activities = ref<SeckillActivity[]>([])

const publishedTemplates = computed(() => templates.value.filter(t => t.status === 1).length)
const usableCoupons = computed(() => myCoupons.value.filter(c => c.status === 0).length)
const runningActivities = computed(() => activities.value.filter(a => a.status === 1).length)
const totalReceived = computed(() => templates.value.reduce((s, t) => s + (t.received || 0), 0))

async function load() {
  loading.value = true
  const [t, c, a] = await Promise.allSettled([listTemplates(), listMyCoupons(), listActivities()])
  if (t.status === 'fulfilled') templates.value = t.value || []
  if (c.status === 'fulfilled') myCoupons.value = c.value || []
  if (a.status === 'fulfilled') activities.value = a.value || []
  loading.value = false
}
onMounted(load)
</script>
