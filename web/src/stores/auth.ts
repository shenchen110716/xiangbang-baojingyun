import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as authApi from '@/api/auth'
import { TOKEN_KEY } from '@/api/client'
import type { User } from '@/api/types'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<User | null>(null)
  const token = ref<string | null>(localStorage.getItem(TOKEN_KEY))

  const isEnterprise = () => user.value?.role === 'enterprise'
  const isAdmin = () => user.value?.role === 'admin'
  const isEnterpriseOwner = () => isEnterprise() && user.value?.enterprise_role === 'owner'
  const isProjectManager = () => isEnterprise() && user.value?.enterprise_role === 'project_manager'

  async function login(username: string, password: string, portal: 'admin' | 'enterprise' | 'salesperson' | 'insurer') {
    const result = await authApi.login(username, password, portal)
    token.value = result.access_token
    localStorage.setItem(TOKEN_KEY, result.access_token)
    await loadProfile()
  }

  async function loadProfile() {
    user.value = await authApi.me()
    return user.value
  }

  async function switchAccount(targetUserId: number) {
    const result = await authApi.switchAccount(targetUserId)
    token.value = result.access_token
    localStorage.setItem(TOKEN_KEY, result.access_token)
    await loadProfile()
  }

  function logout() {
    user.value = null
    token.value = null
    localStorage.removeItem(TOKEN_KEY)
  }

  return { user, token, isEnterprise, isAdmin, isEnterpriseOwner, isProjectManager, login, loadProfile, logout, switchAccount }
})
