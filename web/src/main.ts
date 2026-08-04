import { createApp } from 'vue'
import { createRouter, createWebHashHistory } from 'vue-router'
import App from './App.vue'
import { auth } from './api'
import { role, NAV } from './roles'
import './styles.css'

// hash 路由:后端没有为前端路由做 fallback,用 history 模式刷新子路径会 404。
// 这是刻意的取舍——少改一处后端配置,换掉地址栏里那个 #。
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', component: () => import('./views/Login.vue'), meta: { anon: true } },

    // 求职端
    { path: '/jobs', component: () => import('./views/worker/Jobs.vue') },
    { path: '/my-applications', component: () => import('./views/worker/MyApplications.vue') },
    { path: '/my-wages', component: () => import('./views/worker/MyWages.vue') },
    { path: '/my-profile', component: () => import('./views/worker/MyProfile.vue') },

    // 企业端
    { path: '/my-orgs', component: () => import('./views/employer/MyOrgs.vue') },
    { path: '/my-jobs', component: () => import('./views/employer/MyJobs.vue') },

    // 平台端
    { path: '/settings', component: () => import('./views/platform/Settings.vue') },
    { path: '/review-orgs', component: () => import('./views/platform/ReviewOrgs.vue') },
    { path: '/payouts', component: () => import('./views/platform/Payouts.vue') },
    { path: '/settlements', component: () => import('./views/platform/Settlements.vue') },
    { path: '/ops', component: () => import('./views/Ops.vue') },
    { path: '/roles', component: () => import('./views/platform/Roles.vue') },

    // 跨身份共用
    { path: '/notifications', component: () => import('./views/Notification.vue') },
    { path: '/broker', component: () => import('./views/Broker.vue') },
    { path: '/talent', component: () => import('./views/Talent.vue') },
    { path: '/review', component: () => import('./views/Review.vue') },
    { path: '/voice', component: () => import('./views/Voice.vue') },

    { path: '/', redirect: () => NAV[role.value][0].to },
    { path: '/:rest(.*)', redirect: '/' },
  ],
})

router.beforeEach((to) => {
  if (!to.meta.anon && !auth.token) return '/login'
  if (to.path === '/login' && auth.token) return '/'
})

createApp(App).use(router).mount('#app')
