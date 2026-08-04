import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import App from './App.vue'
import { auth } from './api'
import './styles.css'

// hash 路由:后端没有为前端路由做 fallback,用 history 模式刷新子路径会 404。
// 这是刻意的取舍——少改一处后端配置,换掉地址栏里那个 #。
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', component: () => import('./views/Login.vue'), meta: { anon: true } },
    { path: '/', component: () => import('./views/Home.vue') },
    { path: '/identity', component: () => import('./views/Identity.vue') },
    { path: '/org', component: () => import('./views/Org.vue') },
    { path: '/job', component: () => import('./views/Job.vue') },
    { path: '/voice', component: () => import('./views/Voice.vue') },
    { path: '/engagement', component: () => import('./views/Engagement.vue') },
    { path: '/agreement', component: () => import('./views/Agreement.vue') },
    { path: '/settlement', component: () => import('./views/Settlement.vue') },
    { path: '/fund', component: () => import('./views/Fund.vue') },
    { path: '/broker', component: () => import('./views/Broker.vue') },
    { path: '/profile', component: () => import('./views/Profile.vue') },
    { path: '/matching', component: () => import('./views/Matching.vue') },
    { path: '/talent', component: () => import('./views/Talent.vue') },
    { path: '/review', component: () => import('./views/Review.vue') },
    { path: '/notification', component: () => import('./views/Notification.vue') },
    { path: '/ops', component: () => import('./views/Ops.vue') },
    { path: '/:rest(.*)', redirect: '/' },
  ],
})

router.beforeEach((to) => {
  if (!to.meta.anon && !auth.token) return '/login'
  if (to.path === '/login' && auth.token) return '/'
})

createApp(App).use(router).mount('#app')
