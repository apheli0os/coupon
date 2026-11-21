<template>
  <div class="page">
    <div class="page-header">
      <div><h1 class="page-title">券模板管理</h1><div class="page-subtitle">创建、发布优惠券模板，用户领券后才能使用</div></div>
      <el-button type="primary" @click="openCreate">新建模板</el-button>
    </div>
    <div class="card">
      <div class="toolbar">
        <el-input v-model="keyword" placeholder="搜索模板名称" clearable style="width:240px" />
        <el-select v-model="statusFilter" placeholder="状态" clearable style="width:130px">
          <el-option v-for="(label, k) in COUPON_STATUS" :key="k" :label="label" :value="Number(k)" />
        </el-select>
        <el-button @click="load">刷新</el-button>
      </div>
      <el-table :data="filtered" v-loading="loading" empty-text="暂无模板，点击右上角新建">
        <el-table-column prop="id" label="ID" width="120" show-overflow-tooltip />
        <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
        <el-table-column label="优惠" width="140">
          <template #default="{ row }">
            {{ formatDiscount(row) }}
          </template>
        </el-table-column>
        <el-table-column prop="total" label="总量" width="90" />
        <el-table-column label="已领/进度" min-width="150">
          <template #default="{ row }">
            <div>{{ row.received || 0 }} / {{ row.total }}</div>
            <el-progress :percentage="row.total ? Math.min(100, Math.round((row.received || 0) / row.total * 100)) : 0" :show-text="false" />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag size="small" :type="row.status === 1 ? 'success' : 'info'">{{ COUPON_STATUS[row.status] || '未知' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="有效期" min-width="170">
          <template #default="{ row }">{{ fmt(row.startTime) }} ~ {{ fmt(row.endTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="claim(row)">领一张</el-button>
            <el-button v-if="row.status === 0" link type="success" @click="publish(row)">发布</el-button>
            <el-button link @click="detail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="createVisible" title="新建券模板（需管理员角色）" width="540px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="模板名称"><el-input v-model="form.name" placeholder="如：新人满100减20" /></el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="form.type" @change="onTypeChange">
            <el-radio-button :value="1">满减券</el-radio-button>
            <el-radio-button :value="2">折扣券</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="form.type === 1 ? '减金额' : '折扣率'">
          <el-input-number v-model="form.discountValue" :min="0.01" :max="form.type === 2 ? 1 : 99999" :step="form.type === 2 ? 0.05 : 1" :precision="2" />
          <span v-if="form.type === 2" class="text-muted" style="margin-left:10px;font-size:12px">范围 (0, 1]，如 0.85 表示 8.5 折</span>
        </el-form-item>
        <el-form-item label="使用门槛"><el-input-number v-model="form.threshold" :min="0" :precision="2" /></el-form-item>
        <el-form-item label="发放总量"><el-input-number v-model="form.total" :min="1" /></el-form-item>
        <el-form-item label="每人限领"><el-input-number v-model="form.perUserLimit" :min="1" /></el-form-item>
        <el-form-item label="有效天数"><el-input-number v-model="form.validDays" :min="1" /></el-form-item>
        <el-form-item label="开始时间">
          <el-date-picker v-model="form.startTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" placeholder="选择开始时间" style="width:100%" />
        </el-form-item>
        <el-form-item label="结束时间">
          <el-date-picker v-model="form.endTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss" placeholder="选择结束时间" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="createVisible=false">取消</el-button><el-button type="primary" :loading="saving" @click="submitCreate">创建</el-button></template>
    </el-dialog>

    <el-dialog v-model="detailVisible" title="模板详情" width="560px">
      <el-descriptions :column="2" border v-if="current">
        <el-descriptions-item label="ID">{{ current.id }}</el-descriptions-item>
        <el-descriptions-item label="名称">{{ current.name }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ COUPON_STATUS[current.status] }}</el-descriptions-item>
        <el-descriptions-item label="已领">{{ current.received }} / {{ current.total }}</el-descriptions-item>
        <el-descriptions-item label="开始时间">{{ current.startTime }}</el-descriptions-item>
        <el-descriptions-item label="结束时间">{{ current.endTime }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listTemplates, createTemplate, publishTemplate, claimCoupon } from '@/api'
import { COUPON_STATUS } from '@/types'
import type { CouponTemplate, CouponTemplateCreate } from '@/types'

const loading = ref(false)
const templates = ref<CouponTemplate[]>([])
const keyword = ref('')
const statusFilter = ref<number | undefined>()
const filtered = computed(() => templates.value.filter(t =>
  (!keyword.value || t.name?.includes(keyword.value)) &&
  (statusFilter.value === undefined || t.status === statusFilter.value)))

function formatDateTime(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function defaultWindow(): [string, string] {
  const now = new Date()
  const end = new Date(now.getTime() + 7 * 24 * 3600 * 1000)
  return [formatDateTime(now), formatDateTime(end)]
}

const createVisible = ref(false)
const detailVisible = ref(false)
const saving = ref(false)
const current = ref<CouponTemplate | null>(null)
const [initStart, initEnd] = defaultWindow()
const form = reactive<CouponTemplateCreate>({
  name: '',
  type: 1,
  discountValue: 20,
  threshold: 100,
  total: 1000,
  perUserLimit: 1,
  validDays: 30,
  startTime: initStart,
  endTime: initEnd,
})

function fmt(v?: string) { return v ? v.replace('T', ' ').slice(0, 16) : '-' }
function formatDiscount(row: CouponTemplate): string {
  if (row.type === 1) return `满${row.threshold ?? 0}减${row.discountValue}`
  const fold = Number((Number(row.discountValue) * 10).toFixed(1))
  return `${fold}折券${row.threshold ? `(满${row.threshold})` : ''}`
}
function onTypeChange(val: string | number | boolean | undefined) {
  if (val === 2 && form.discountValue > 1) form.discountValue = 0.85
  if (val === 1 && form.discountValue <= 1) form.discountValue = 20
}

async function load() {
  loading.value = true
  try { templates.value = await listTemplates() || [] } finally { loading.value = false }
}
function openCreate() {
  const [s, e] = defaultWindow()
  form.startTime = s
  form.endTime = e
  createVisible.value = true
}
async function submitCreate() {
  if (!form.name) { ElMessage.warning('请输入模板名称'); return }
  if (!form.startTime || !form.endTime) { ElMessage.warning('请选择活动开始与结束时间'); return }
  if (form.type === 2 && (form.discountValue <= 0 || form.discountValue > 1)) {
    ElMessage.warning('折扣券折扣率必须在 (0, 1] 之间')
    return
  }
  saving.value = true
  try {
    await createTemplate({ ...form })
    ElMessage.success('模板创建成功（草稿状态，需发布）')
    createVisible.value = false
    await load()
  } finally { saving.value = false }
}
async function publish(row: CouponTemplate) { await publishTemplate(row.id); ElMessage.success('已发布'); await load() }
async function claim(row: CouponTemplate) { await claimCoupon(row.id); ElMessage.success(`已领取「${row.name}」（异步落库中）`); await load() }
function detail(row: CouponTemplate) { current.value = row; detailVisible.value = true }
onMounted(load)
</script>
