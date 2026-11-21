import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { demoLogin } from '@/api'
import { getToken, setToken, clearToken } from '@/api/request'

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string>(getToken())
  const userId = ref<number>(Number(localStorage.getItem('mall_admin_uid') || 1001))
  const role = ref<string>(localStorage.getItem('mall_admin_role') || 'USER')

  const isLogged = computed(() => !!token.value)
  const isAdmin = computed(() => role.value === 'ADMIN')

  async function login(uid: number, r = 'USER') {
    const t = await demoLogin(uid, r)
    token.value = t
    userId.value = uid
    role.value = r
    setToken(t)
    localStorage.setItem('mall_admin_uid', String(uid))
    localStorage.setItem('mall_admin_role', r)
  }

  function logout() {
    token.value = ''
    clearToken()
  }

  return { token, userId, role, isLogged, isAdmin, login, logout }
})
