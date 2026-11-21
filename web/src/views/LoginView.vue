<template>
  <div class="login-wrap">
    <div class="login-card">
      <h1>Mall Coupon Platform</h1>
      <div class="sub">演示登录 · 通过网关白名单签发 JWT（仅 dev/local profile）</div>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="用户 ID">
          <el-input-number v-model="uid" :min="1" :max="99999999" style="width:100%" />
        </el-form-item>
        <el-form-item label="角色">
          <el-radio-group v-model="role">
            <el-radio-button value="USER">普通用户</el-radio-button>
            <el-radio-button value="ADMIN">管理员</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-button type="primary" style="width:100%" size="large" :loading="loading" @click="doLogin">登录并进入管理台</el-button>
      </el-form>
      <el-alert style="margin-top:18px" type="info" :closable="false" show-icon
        title="需要后端服务运行" description="Windows 执行 .\scripts\mall.ps1 start，Linux/macOS 执行 ./scripts/mall.sh start，确认网关 :8080 可访问。" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const uid = ref(auth.userId || 1001)
const role = ref(auth.role || 'USER')
const loading = ref(false)

async function doLogin() {
  loading.value = true
  try {
    await auth.login(uid.value, role.value)
    ElMessage.success('登录成功')
    router.push((route.query.redirect as string) || '/dashboard')
  } finally {
    loading.value = false
  }
}
</script>
