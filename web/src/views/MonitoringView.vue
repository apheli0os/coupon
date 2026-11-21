<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">监控中心</h1>
        <div class="page-subtitle">Prometheus 抓取 + Grafana 可视化 + Alertmanager 告警</div>
      </div>
      <el-button @click="load" :loading="checking">检查可达性</el-button>
    </div>

    <div class="stat-grid">
      <div v-for="s in systems" :key="s.name" class="stat-card" :class="s.ok ? 'success' : 'warning'">
        <div class="label">{{ s.name }}</div>
        <div class="value" style="font-size:18px">{{ s.ok ? '● 可达' : '○ 未启动' }}</div>
        <div class="hint code">{{ s.url }}</div>
      </div>
    </div>

    <el-row :gutter="16">
      <el-col :xs="24" :md="14">
        <div class="card">
          <h3 style="margin-top:0">快速入口</h3>
          <el-table :data="links" size="small">
            <el-table-column prop="name" label="系统" min-width="140" />
            <el-table-column prop="desc" label="用途" min-width="220" />
            <el-table-column label="地址" min-width="220">
              <template #default="{ row }"><el-link type="primary" :href="row.url" target="_blank">{{ row.url }}</el-link></template>
            </el-table-column>
          </el-table>
          <el-alert style="margin-top:16px" type="info" :closable="false" show-icon
            title="一键启动监控栈" description="在项目根目录执行：docker compose -f docker-compose-monitoring.yml up -d" />
        </div>
      </el-col>
      <el-col :xs="24" :md="10">
        <div class="card">
          <h3 style="margin-top:0">抓取目标</h3>
          <ul style="font-size:13px;line-height:2;padding-left:18px;margin:0">
            <li>gateway :8080（prometheus）</li>
            <li>coupon :8081（prometheus）</li>
            <li>seckill :8082（prometheus）</li>
            <li>order :8083（prometheus）</li>
            <li>redis-exporter :9121</li>
            <li>kafka-exporter :9308</li>
          </ul>
          <div class="text-muted" style="font-size:12px;margin-top:12px;line-height:1.8">
            Dashboard 10 个面板覆盖 QPS / P99 / 5xx / JVM / 业务指标 / Kafka 积压 / Redis 从节点 / Kafka ISR。<br/>
            告警规则见 <code>k8s/alerts.yml</code>，按 critical / warning / info 分级路由。
          </div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import axios from 'axios'

const checking = ref(false)
const systems = ref([
  { name: 'Grafana', url: 'http://localhost:3000', ok: false, probeUrl: '/_probe/grafana/api/health' },
  { name: 'Prometheus', url: 'http://localhost:9090', ok: false, probeUrl: '/_probe/prometheus/-/ready' },
  { name: 'Alertmanager', url: 'http://localhost:9093', ok: false, probeUrl: '/_probe/alertmanager/-/ready' },
])
const links = [
  { name: 'Grafana', desc: '可视化大盘（admin / admin）', url: 'http://localhost:3000' },
  { name: 'Mall Overview', desc: '业务大盘直达', url: 'http://localhost:3000/d/mall-overview' },
  { name: 'Prometheus', desc: '指标查询与抓取状态', url: 'http://localhost:9090' },
  { name: 'Alertmanager', desc: '告警路由与静默', url: 'http://localhost:9093' },
]

async function load() {
  checking.value = true
  await Promise.all(systems.value.map(async (s) => {
    try { await axios.get(s.probeUrl, { timeout: 1500 }); s.ok = true }
    catch { s.ok = false }
  }))
  checking.value = false
}
onMounted(load)
</script>
