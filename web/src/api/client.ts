import axios from 'axios'
import { ElNotification } from 'element-plus'
import router from '@/router'

export const TOKEN_KEY = 'xbb-auth-token'

export const client = axios.create({
  baseURL: '/api',
})

const USAGE_LOCK_MESSAGE = '使用费余额不足，请先充值后再操作参停保'
let usageLockNotified = false

client.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

client.interceptors.response.use(
  (response) => response,
  (error) => {
    const detail = error?.response?.data?.detail || error.message || '请求失败'
    if (error?.response?.status === 401) {
      localStorage.removeItem(TOKEN_KEY)
      if (router.currentRoute.value.name !== 'login') {
        router.push({ name: 'login', query: router.currentRoute.value.query })
      }
    }
    if (error?.response?.status === 403 && detail === USAGE_LOCK_MESSAGE && !usageLockNotified) {
      usageLockNotified = true
      ElNotification({
        title: '参停保功能已锁定',
        message: '使用费余额不足，充值到账后自动恢复，无需额外操作。',
        type: 'warning',
        duration: 8000,
        onClick: () => {
          router.push({ name: 'recharge' })
        },
        onClose: () => {
          usageLockNotified = false
        },
      })
    }
    return Promise.reject(new Error(detail))
  },
)
