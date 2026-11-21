<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="brand">
        <div class="logo">M</div>
        <div>
          <div class="brand-title">Mall Platform</div>
          <div class="brand-sub">优惠券 · 秒杀管理台</div>
        </div>
      </div>
      <el-menu :default-active="route.path" router class="menu" background-color="#151a2e" text-color="#9aa3bd" active-text-color="#fff">
        <el-menu-item index="/dashboard"><el-icon><DataBoard /></el-icon>总览</el-menu-item>
        <el-menu-item index="/templates"><el-icon><Ticket /></el-icon>券模板管理</el-menu-item>
        <el-menu-item index="/my-coupons"><el-icon><Wallet /></el-icon>我的优惠券</el-menu-item>
        <el-menu-item index="/activities"><el-icon><Calendar /></el-icon>秒杀活动</el-menu-item>
        <el-menu-item index="/seckill"><el-icon><Lightning /></el-icon>秒杀抢购</el-menu-item>
        <el-menu-item index="/orders"><el-icon><Document /></el-icon>订单查询</el-menu-item>
        <el-menu-item index="/monitoring"><el-icon><Monitor /></el-icon>监控中心</el-menu-item>
      </el-menu>
      <div class="aside-footer">Vue 3 · Element Plus<br/>Gateway :8080</div>
    </el-aside>
    <el-container>
      <el-header class="header">
        <div class="bc">{{ route.meta.title || '管理台' }}</div>
        <div class="header-right">
          <el-tag type="success" size="small" effect="plain">用户 {{ auth.userId }} · {{ auth.role }}</el-tag>
          <el-button size="small" @click="auth.logout(); router.push('/login')">退出</el-button>
        </div>
      </el-header>
      <el-main class="main"><router-view /></el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { DataBoard, Ticket, Wallet, Calendar, Lightning, Document, Monitor } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
</script>

<style scoped>
.layout { height: 100vh; }
.aside { background: #151a2e; display: flex; flex-direction: column; }
.brand { display: flex; align-items: center; gap: 12px; padding: 20px 18px; border-bottom: 1px solid rgba(255,255,255,.07); }
.logo { width: 36px; height: 36px; border-radius: 10px; background: linear-gradient(135deg, #6d5efc, #a78bfa); display: grid; place-items: center; color: #fff; font-weight: 800; font-size: 18px; }
.brand-title { color: #fff; font-weight: 700; font-size: 15px; }
.brand-sub { color: #8b93ad; font-size: 11px; margin-top: 2px; }
.menu { border: none; flex: 1; padding-top: 8px; }
.aside-footer { padding: 16px 18px; color: #6b7390; font-size: 11px; line-height: 1.7; border-top: 1px solid rgba(255,255,255,.07); }
.header { background: #fff; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid #eef0f4; }
.bc { font-weight: 600; }
.header-right { display: flex; align-items: center; gap: 12px; }
.main { background: #f5f7fa; padding: 0; }
</style>
