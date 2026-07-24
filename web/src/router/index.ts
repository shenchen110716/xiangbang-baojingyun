import { createRouter, createWebHistory } from 'vue-router'
import { routes } from './routes'
import { TOKEN_KEY } from '@/api/client'

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() {
    return { top: 0 }
  },
})

const PUBLIC_ROUTE_NAMES = new Set(['login', 'enterprise-apply'])

router.beforeEach((to) => {
  const hasToken = !!localStorage.getItem(TOKEN_KEY)
  if (!PUBLIC_ROUTE_NAMES.has(to.name as string) && !hasToken) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && hasToken) {
    return { name: 'home' }
  }
  return true
})

export default router
