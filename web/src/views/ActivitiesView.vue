<template>
  <div class="page">
    <div class="page-header">
      <div><h1 class="page-title">秒杀活动</h1><div class="page-subtitle">创建并发布活动，库存预热到 Redis（16 桶拆分）</div></div>
      <div class="toolbar" style="margin:0">
        <el-button @click="load">刷新</el-button>
        <el-button type="primary" @click="openCreate">新建活动</el-button>
      </div>
    </div>
    <div class="card">
      <el-table :data="activities" v-loading="loading" empty-text="暂无活动，点击右上角新建">
        <el-table-column prop="id" label="ID" width="140" show-overflow-tooltip />
        <el-table-column prop="name" label="活动名称" min-width="150" show-overflow-tooltip />
        <el-table-column prop="productName" label="商品" min-width="120" show-overflow-tooltip />
        <el-table-column label="秒杀价/原价" width="130">
          <template #default="{ row }"><span style="color:#e11d48;font-weight:600">¥{{ row.seckillPrice }}</span> <span class="text-muted" style="text-decoration:line-through">¥{{ row.originalPrice }}</span></template>
        </el-table-column>
        <el-table-column label="库存进度" min-width="160">
          <template #default="{ row }">
            <div>{{ row.soldCount || 0 }} / {{ row.totalStock }}</div>
            <el-progress :percentage="row.totalStock ? Math.min(100, Math.round((row.soldCount || 0) / row.totalStock * 100)) : 0" color="#e11d48" :show-text="false" />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag size="small" :type="row.status === 1 ? 'danger' : 'info'">{{ ACTIVITY_STATUS[row.status] || '未知' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="时间窗" min-width="170">
          <template #default="{ row }">{{ fmt(row.startTime) }} ~ {{ fmt(row.endTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 0" link type="danger" @click="publish(row)">发布</el-button>
            <el-button link type="primary" @click="goSeckill(row)">去抢购</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="createVisible" title="新建秒杀活动（需管理员角色）" width="540px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="活动名称"><el-input v-model="form.name" placeholder="如：双十一秒杀场" /></el-form-item>
        <el-form-item label="商品编码"><el-input v-model="form.productCode" placeholder="SKU-001" /></el-form-item>
        <el-form-item label="商品名称"><el-input v-model="form.productName" placeholder="限量商品" /></el-form-item>
        <el-form-item label="秒杀价"><el-input-number v-model="form.seckillPrice" :min="0.01" :precision="2" /></el-form-item>
        <el-form-item label="原价"><el-input-number v-model="form.originalPrice" :min="0.01" :precision="2" /></el-form-item>
        <el-form-item label="库存"><el-input-number v-model="form.totalStock" :min="1" /></el-form-item>
        <el-form-item label="开始时间">
          <el-date-picker v-model="form.startTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" placeholder="选择开始时间" style="width:100%" />
        </el-form-item>
        <el-form-item label="结束时间">
          <el-date-picker v-model="form.endTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" placeholder="选择结束时间" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="createVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="submitCreate">创建</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listActivities, createActivity, publishActivity } from '@/api'
import { ACTIVITY_STATUS } from '@/types'
import type { SeckillActivity, SeckillActivityCreate } from '@/types'

function formatDateTime(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function defaultWindow(): [string, string] {
  const now = new Date()
  const end = new Date(now.getTime() + 7 * 24 * 3600 * 1000)
  return [formatDateTime(now), formatDateTime(end)]
}

const router = useRouter()
const loading = ref(false)
const activities = ref<SeckillActivity[]>([])
const createVisible = ref(false)
const saving = ref(false)
const [initStart, initEnd] = defaultWindow()
const form = reactive<SeckillActivityCreate>({
  name: '',
  productCode: 'SKU-001',
  productName: '限量秒杀商品',
  seckillPrice: 9.9,
  originalPrice: 99,
  totalStock: 1000,
  startTime: initStart,
  endTime: initEnd,
})

function fmt(v?: string) { return v ? v.replace('T', ' ').slice(0, 16) : '-' }
async function load() {
  loading.value = true
  try { activities.value = await listActivities() || [] } finally { loading.value = false }
}
function openCreate() {
  const [s, e] = defaultWindow()
  form.startTime = s
  form.endTime = e
  createVisible.value = true
}
async function submitCreate() {
  if (!form.name) { ElMessage.warning('请输入活动名称'); return }
  if (!form.startTime || !form.endTime) { ElMessage.warning('请选择活动开始与结束时间'); return }
  saving.value = true
  try {
    await createActivity({ ...form })
    ElMessage.success('活动创建成功（未发布）')
    createVisible.value = false
    await load()
  } finally { saving.value = false }
}
async function publish(row: SeckillActivity) { await publishActivity(row.id); ElMessage.success('活动已发布，库存已预热'); await load() }
function goSeckill(row: SeckillActivity) { router.push({ path: '/seckill', query: { activityId: row.id } }) }
onMounted(load)
</script>
