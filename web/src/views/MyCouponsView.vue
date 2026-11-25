<template>
  <div class="page">
    <div class="page-header">
      <div><h1 class="page-title">我的优惠券</h1><div class="page-subtitle">领券异步落库，秒级最终一致；可按状态筛选</div></div>
      <div class="toolbar" style="margin:0">
        <el-select v-model="status" placeholder="全部状态" clearable style="width:140px" @change="load">
          <el-option v-for="(label, k) in USER_COUPON_STATUS" :key="k" :label="label" :value="Number(k)" />
        </el-select>
        <el-button @click="load">刷新</el-button>
      </div>
    </div>
    <div class="card">
      <el-table :data="coupons" v-loading="loading" empty-text="暂无优惠券，去「券模板管理」领一张">
        <el-table-column prop="id" label="券 ID" width="160" show-overflow-tooltip />
        <el-table-column prop="couponName" label="优惠券名称" min-width="150" show-overflow-tooltip />
        <el-table-column label="优惠规则" min-width="190">
          <template #default="{ row }">{{ formatRule(row) }}</template>
        </el-table-column>
        <el-table-column label="适用范围" min-width="150">
          <template #default="{ row }">
            <el-tag size="small" :type="row.target === 1 ? 'warning' : 'info'">{{ COUPON_TARGET[row.target ?? 0] || '全场通用' }}</el-tag>
            <span v-if="row.target === 1" class="code" style="margin-left:6px">{{ row.productCode }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType(row.status)">{{ USER_COUPON_STATUS[row.status] || '未知' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="关联订单" min-width="160">
          <template #default="{ row }">
            <a v-if="row.orderId" class="link" :href="`#/orders?orderId=${row.orderId}`">{{ row.orderId }}</a>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="过期时间" min-width="160"><template #default="{ row }">{{ fmt(row.expireTime) }}</template></el-table-column>
        <el-table-column label="使用时间" min-width="160"><template #default="{ row }">{{ fmt(row.useTime) }}</template></el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 0" link type="primary" @click="openUse(row)">去订单使用 / 核销</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="useVisible" title="绑定优惠券到待支付订单" width="480px">
      <el-form label-width="90px" v-if="current">
        <el-form-item label="券 ID"><span class="code">{{ current.id }}</span></el-form-item>
        <el-form-item label="优惠券">{{ current.couponName || current.templateId }}</el-form-item>
        <el-form-item label="订单号">
          <el-input v-model="orderId" placeholder="请输入本人待支付的真实秒杀订单号" clearable />
        </el-form-item>
        <el-alert type="info" :closable="false" show-icon
          title="两阶段锁券" description="绑定后本券进入「锁定中」，订单按折后实付金额成交；支付则券变为已使用，取消订单则自动释放。" />
      </el-form>
      <template #footer>
        <el-button @click="useVisible=false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitUse">绑定并锁价</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listMyCoupons, applyOrderCoupon } from '@/api'
import { USER_COUPON_STATUS, COUPON_TARGET } from '@/types'
import type { UserCoupon } from '@/types'

const loading = ref(false)
const submitting = ref(false)
const coupons = ref<UserCoupon[]>([])
const status = ref<number | undefined>()
const useVisible = ref(false)
const current = ref<UserCoupon | null>(null)
const orderId = ref('')

function fmt(v?: string) { return v ? v.replace('T', ' ').slice(0, 19) : '-' }
function statusTagType(s: number): 'success' | 'warning' | 'info' {
  if (s === 0) return 'success'
  if (s === 3) return 'warning'
  return 'info'
}
function formatRule(row: UserCoupon): string {
  if (row.type === 2) {
    const fold = Number((Number(row.discountValue) * 10).toFixed(1))
    const cap = row.maxDiscountAmount ? `，封顶¥${row.maxDiscountAmount}` : ''
    return `${fold}折${cap}${row.threshold ? `，满${row.threshold}可用` : ''}`
  }
  return `满${row.threshold ?? 0}减${row.discountValue ?? 0}`
}
async function load() {
  loading.value = true
  try { coupons.value = await listMyCoupons(status.value) || [] } finally { loading.value = false }
}
function openUse(row: UserCoupon) { current.value = row; orderId.value = ''; useVisible.value = true }
async function submitUse() {
  if (!current.value) return
  const oid = orderId.value.trim()
  if (!oid) { ElMessage.warning('请输入真实待支付订单号'); return }
  submitting.value = true
  try {
    await applyOrderCoupon(oid, current.value.id)
    ElMessage.success('已锁定到订单，请前往订单页完成支付')
    useVisible.value = false
    window.location.hash = `#/orders?orderId=${oid}`
    await load()
  } finally { submitting.value = false }
}
onMounted(load)
</script>

<style scoped>
.link { color: var(--el-color-primary); text-decoration: none; }
</style>
