import { createRouter, createWebHistory } from 'vue-router'
import { routes } from './routes'
import { TOKEN_KEY } from '@/api/client'

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const hasToken = !!localStorage.getItem(TOKEN_KEY)
  if (to.name !== 'login' && !hasToken) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && hasToken) {
    return { name: 'home' }
  }
  return true
})

export default router
