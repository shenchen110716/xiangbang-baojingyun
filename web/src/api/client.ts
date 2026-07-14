import axios from 'axios'
import router from '@/router'

export const TOKEN_KEY = 'xbb-auth-token'

export const client = axios.create({
  baseURL: '/api',
})

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
    return Promise.reject(new Error(detail))
  },
)
