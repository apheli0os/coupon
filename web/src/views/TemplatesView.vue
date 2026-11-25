<template>
  <div class="page">
    <div class="page-header">
      <div><h1 class="page-title">券模板管理</h1><div class="page-subtitle">创建、发布优惠券模板，支持条件分页、动态控量与规则配置</div></div>
      <el-button type="primary" @click="openCreate">新建模板</el-button>
    </div>
    <div class="card">
      <div class="toolbar">
        <el-input v-model="query.name" placeholder="搜索模板名称" clearable style="width:200px" @keyup.enter="resetAndLoad" />
        <el-select v-model="query.type" placeholder="类型" clearable style="width:120px" @change="resetAndLoad">
          <el-option label="满减券" :value="1" />
          <el-option label="折扣券" :value="2" />
        </el-select>
        <el-select v-model="query.status" placeholder="状态" clearable style="width:120px" @change="resetAndLoad">
          <el-option v-for="(label, k) in COUPON_STATUS" :key="k" :label="label" :value="Number(k)" />
        </el-select>
        <el-select v-model="query.target" placeholder="适用范围" clearable style="width:130px" @change="resetAndLoad">
          <el-option v-for="(label, k) in COUPON_TARGET" :key="k" :label="label" :value="Number(k)" />
        </el-select>
        <el-input v-model="query.productCode" placeholder="商品编码" clearable style="width:160px" @keyup.enter="resetAndLoad" />
        <el-button type="primary" @click="resetAndLoad">搜索</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </div>
      <el-table :data="records" v-loading="loading" empty-text="暂无模板，点击右上角新建">
        <el-table-column prop="id" label="ID" width="150" show-overflow-tooltip />
        <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column label="优惠" width="190">
          <template #default="{ row }">{{ formatDiscount(row) }}</template>
        </el-table-column>
        <el-table-column label="适用范围" min-width="160">
          <template #default="{ row }">
            <el-tag size="small" :type="row.target === 1 ? 'warning' : 'info'">{{ COUPON_TARGET[row.target ?? 0] || '全场通用' }}</el-tag>
            <span v-if="row.target === 1" class="code" style="margin-left:6px">{{ row.productCode }}</span>
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
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="claim(row)">领一张</el-button>
            <el-button v-if="row.status === 0" link type="success" @click="publish(row)">发布</el-button>
            <el-button v-if="row.status === 0 || row.status === 1" link type="warning" @click="openIncrease(row)">增发库存</el-button>
            <el-button v-if="row.status === 0 || row.status === 1" link type="danger" @click="terminate(row)">终止</el-button>
            <el-button link @click="detail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination">
        <el-pagination
          v-model:current-page="query.pageNum"
          v-model:page-size="query.pageSize"
          :total="Number(pageTotal)"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="resetAndLoad"
        />
      </div>
    </div>

    <el-dialog v-model="createVisible" title="新建券模板（需管理员角色）" width="560px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="模板名称"><el-input v-model="form.name" placeholder="如：新人满100减20" /></el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="form.type" @change="onTypeChange">
            <el-radio-button :value="1">满减券</el-radio-button>
            <el-radio-button :value="2">折扣券</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="form.type === 1 ? '减金额' : '折扣率'">
          <el-input-number v-model="form.discountValue" :min="0.01" :max="form.type === 2 ? 0.99 : 99999" :step="form.type === 2 ? 0.05 : 1" :precision="2" />
          <span v-if="form.type === 2" class="text-muted" style="margin-left:10px;font-size:12px">范围 (0, 1)，如 0.85 表示 8.5 折</span>
        </el-form-item>
        <el-form-item label="封顶优惠金额">
          <el-input-number v-model="form.maxDiscountAmount" :min="0.01" :precision="2" />
          <span v-if="form.type === 2" class="text-danger" style="margin-left:10px;font-size:12px">折扣券必填，&gt; 0</span>
        </el-form-item>
        <el-form-item label="使用门槛"><el-input-number v-model="form.threshold" :min="0" :precision="2" /></el-form-item>
        <el-form-item label="适用范围">
          <el-radio-group v-model="form.target">
            <el-radio-button :value="0">全场通用</el-radio-button>
            <el-radio-button :value="1">指定商品</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.target === 1" label="商品编码">
          <el-input v-model="form.productCode" placeholder="如：SKU-2026-001" />
        </el-form-item>
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

    <el-dialog v-model="increaseVisible" title="增发库存" width="420px">
      <div style="margin-bottom:12px">模板：<span class="code">{{ increaseRow?.id }}</span></div>
      <el-form label-width="100px">
        <el-form-item label="增发数量">
          <el-input-number v-model="increaseDelta" :min="1" :step="10" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="increaseVisible=false">取消</el-button><el-button type="primary" :loading="increasing" @click="submitIncrease">确认增发</el-button></template>
    </el-dialog>

    <el-dialog v-model="detailVisible" title="模板详情" width="560px">
      <el-descriptions :column="2" border v-if="current">
        <el-descriptions-item label="ID">{{ current.id }}</el-descriptions-item>
        <el-descriptions-item label="名称">{{ current.name }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ COUPON_STATUS[current.status] }}</el-descriptions-item>
        <el-descriptions-item label="优惠">{{ formatDiscount(current) }}</el-descriptions-item>
        <el-descriptions-item label="适用范围">{{ COUPON_TARGET[current.target ?? 0] || '全场通用' }}</el-descriptions-item>
        <el-descriptions-item label="商品编码">{{ current.productCode || '-' }}</el-descriptions-item>
        <el-descriptions-item label="封顶优惠">{{ current.maxDiscountAmount ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="已领">{{ current.received }} / {{ current.total }}</el-descriptions-item>
        <el-descriptions-item label="开始时间">{{ current.startTime }}</el-descriptions-item>
        <el-descriptions-item label="结束时间">{{ current.endTime }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  pageTemplates, createTemplate, publishTemplate, claimCoupon,
  increaseTemplateStock, terminateTemplate,
} from '@/api'
import { COUPON_STATUS, COUPON_TARGET } from '@/types'
import type { CouponTemplate, CouponTemplateCreate, CouponTemplatePageQuery } from '@/types'

const loading = ref(false)
const records = ref<CouponTemplate[]>([])
const pageTotal = ref(0)
const query = reactive<CouponTemplatePageQuery>({ pageNum: 1, pageSize: 20, name: '', type: undefined, status: undefined, target: undefined, productCode: '' })

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
  maxDiscountAmount: undefined,
  threshold: 100,
  total: 1000,
  perUserLimit: 1,
  validDays: 30,
  target: 0,
  productCode: '',
  startTime: initStart,
  endTime: initEnd,
})

const increaseVisible = ref(false)
const increasing = ref(false)
const increaseDelta = ref(10)
const increaseRow = ref<CouponTemplate | null>(null)

function fmt(v?: string) { return v ? v.replace('T', ' ').slice(0, 16) : '-' }
function formatDiscount(row: CouponTemplate): string {
  if (row.type === 1) return `满${row.threshold ?? 0}减${row.discountValue}`
  const fold = Number((Number(row.discountValue) * 10).toFixed(1))
  return `${fold}折(封顶¥${row.maxDiscountAmount ?? 0})`
}
function onTypeChange(val: string | number | boolean | undefined) {
  if (val === 2) {
    if (form.discountValue > 1 || form.discountValue <= 0) form.discountValue = 0.85
    if (!form.maxDiscountAmount) form.maxDiscountAmount = 200
  }
  if (val === 1 && form.discountValue <= 1) form.discountValue = 20
}

async function load() {
  loading.value = true
  try {
    const page = await pageTemplates(query)
    records.value = page?.records || []
    pageTotal.value = Number(page?.total || 0)
  } finally { loading.value = false }
}
function resetAndLoad() { query.pageNum = 1; void load() }
function resetFilters() {
  query.name = ''
  query.type = undefined
  query.status = undefined
  query.target = undefined
  query.productCode = ''
  resetAndLoad()
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
  if (form.endTime <= form.startTime) { ElMessage.warning('结束时间必须晚于开始时间'); return }
  if (form.type === 2) {
    if (form.discountValue <= 0 || form.discountValue >= 1) { ElMessage.warning('折扣率必须严格在 (0, 1) 之间，如 0.85 表示 8.5 折'); return }
    if (!form.maxDiscountAmount || form.maxDiscountAmount < 0.01) { ElMessage.warning('折扣券必须填写封顶优惠金额（> 0）'); return }
  }
  if (form.target === 1 && !form.productCode?.trim()) { ElMessage.warning('指定商品时商品编码必填'); return }
  saving.value = true
  try {
    const payload: CouponTemplateCreate = { ...form }
    if (form.target !== 1) payload.productCode = undefined
    if (form.type !== 2 && !form.maxDiscountAmount) delete payload.maxDiscountAmount
    await createTemplate(payload)
    ElMessage.success('模板创建成功（草稿状态，需发布）')
    createVisible.value = false
    resetAndLoad()
  } finally { saving.value = false }
}
async function publish(row: CouponTemplate) { await publishTemplate(row.id); ElMessage.success('已发布'); await load() }
async function claim(row: CouponTemplate) { await claimCoupon(row.id); ElMessage.success(`已领取「${row.name}」（异步落库中）`); await load() }
function openIncrease(row: CouponTemplate) { increaseRow.value = row; increaseDelta.value = 10; increaseVisible.value = true }
async function submitIncrease() {
  if (!increaseRow.value) return
  if (!Number.isInteger(increaseDelta.value) || increaseDelta.value < 1) { ElMessage.warning('增发数量必须为正整数'); return }
  increasing.value = true
  try {
    await increaseTemplateStock(increaseRow.value.id, increaseDelta.value)
    ElMessage.success(`已增发 ${increaseDelta.value} 张`)
    increaseVisible.value = false
    await load()
  } finally { increasing.value = false }
}
async function terminate(row: CouponTemplate) {
  await ElMessageBox.confirm(`确认终止「${row.name}」？终止后不可增发`, '提示', { type: 'warning' })
  await terminateTemplate(row.id)
  ElMessage.success('已终止')
  await load()
}
function detail(row: CouponTemplate) { current.value = row; detailVisible.value = true }
onMounted(load)
</script>

<style scoped>
.pagination { display: flex; justify-content: flex-end; margin-top: 16px; }
.text-danger { color: var(--el-color-danger); }
</style>
