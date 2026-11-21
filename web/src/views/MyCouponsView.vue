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
        <el-table-column prop="templateId" label="模板 ID" width="140" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 0 ? 'success' : 'info'">{{ USER_COUPON_STATUS[row.status] || '未知' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="领取时间" min-width="160"><template #default="{ row }">{{ fmt(row.createTime) }}</template></el-table-column>
        <el-table-column label="过期时间" min-width="160"><template #default="{ row }">{{ fmt(row.expireTime) }}</template></el-table-column>
        <el-table-column label="使用时间" min-width="160"><template #default="{ row }">{{ fmt(row.useTime) }}</template></el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 0" link type="primary" @click="openUse(row)">核销</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="useVisible" title="核销优惠券" width="480px">
      <el-form label-width="90px" v-if="current">
        <el-form-item label="券 ID"><span class="code">{{ current.id }}</span></el-form-item>
        <el-form-item label="优惠券">{{ current.couponName || current.templateId }}</el-form-item>
        <el-form-item label="订单号">
          <el-input v-model="orderId" placeholder="请输入本人待支付的真实秒杀订单号" clearable />
        </el-form-item>
        <el-alert type="info" :closable="false" show-icon
          title="服务端强校验" description="后端会通过 Feign 校验订单归属、待支付状态及服务端真实金额，请先在「秒杀抢购」生成待支付订单。" />
      </el-form>
      <template #footer><el-button @click="useVisible=false">取消</el-button><el-button type="primary" :loading="submitting" @click="submitUse">确认核销</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listMyCoupons, useCoupon } from '@/api'
import { USER_COUPON_STATUS } from '@/types'
import type { UserCoupon } from '@/types'

const loading = ref(false)
const submitting = ref(false)
const coupons = ref<UserCoupon[]>([])
const status = ref<number | undefined>()
const useVisible = ref(false)
const current = ref<UserCoupon | null>(null)
const orderId = ref('')

function fmt(v?: string) { return v ? v.replace('T', ' ').slice(0, 19) : '-' }
async function load() {
  loading.value = true
  try { coupons.value = await listMyCoupons(status.value) || [] } finally { loading.value = false }
}
function openUse(row: UserCoupon) { current.value = row; orderId.value = ''; useVisible.value = true }
async function submitUse() {
  if (!current.value) return
  if (!orderId.value.trim()) { ElMessage.warning('请输入真实待支付订单号'); return }
  submitting.value = true
  try {
    await useCoupon({ userCouponId: current.value.id, orderId: orderId.value.trim() })
    ElMessage.success('核销成功')
    useVisible.value = false
    await load()
  } finally { submitting.value = false }
}
onMounted(load)
</script>
