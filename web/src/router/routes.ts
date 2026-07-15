import type { RouteRecordRaw } from 'vue-router'

export interface NavMeta {
  title: string
  group?: string
  icon?: string
  adminOnly?: boolean
  badge?: boolean
}

// Mirrors the 18 hash-routed pages from the legacy script.js `go()` dispatcher.
// `adminOnly` pages match the old `.role-hidden` list: insurers/insurance/promotion/agents.
export const routes: Array<RouteRecordRaw & { meta: NavMeta }> = [
  { path: '/', redirect: '/home', meta: { title: '' } },
  { path: '/home', name: 'home', component: () => import('@/views/dashboard/HomeView.vue'), meta: { title: '首页', group: '' } },
  { path: '/screen', name: 'screen', component: () => import('@/views/dashboard/ScreenView.vue'), meta: { title: '经营大屏', group: '' } },
  { path: '/team', name: 'team', component: () => import('@/views/enterprises/TeamView.vue'), meta: { title: '投保单位管理', group: '业务管理' } },
  { path: '/dispatch', name: 'dispatch', component: () => import('@/views/positions/PositionsView.vue'), meta: { title: '岗位管理', group: '业务管理' } },
  { path: '/workers', name: 'workers', component: () => import('@/views/insured/WorkersView.vue'), meta: { title: '参保员工管理', group: '业务管理' } },
  { path: '/work-relations', name: 'workRelations', component: () => import('@/views/insured/WorkRelationsView.vue'), meta: { title: '劳动关系管理', group: '业务管理' } },
  { path: '/agents', name: 'agents', component: () => import('@/views/agents/AgentsView.vue'), meta: { title: '业务员管理', group: '业务管理', adminOnly: true } },
  { path: '/insurance', name: 'insurance', component: () => import('@/views/plans/ProductsView.vue'), meta: { title: '保险产品管理', group: '产品与保司', adminOnly: true } },
  { path: '/policy', name: 'policy', component: () => import('@/views/policies/PolicyListView.vue'), meta: { title: '保单管理', group: '产品与保司' } },
  { path: '/claims', name: 'claims', component: () => import('@/views/claims/ClaimsView.vue'), meta: { title: '工伤理赔', group: '产品与保司' } },
  { path: '/insurers', name: 'insurers', component: () => import('@/views/plans/PlansAdminView.vue'), meta: { title: '保险公司', group: '产品与保司', adminOnly: true } },
  { path: '/exports', name: 'exports', component: () => import('@/views/enrollment/EnrollmentCenterView.vue'), meta: { title: '参停保中心', group: '产品与保司' } },
  { path: '/report', name: 'report', component: () => import('@/views/reports/ReportsView.vue'), meta: { title: '报表中心', group: '保障与结算' } },
  { path: '/billing', name: 'billing', component: () => import('@/views/billing/FinanceView.vue'), meta: { title: '资金与发票', group: '保障与结算' } },
  { path: '/recharge', name: 'recharge', component: () => import('@/views/recharge/RechargeCenterView.vue'), meta: { title: '账户充值', group: '保障与结算' } },
  { path: '/promotion', name: 'promotion', component: () => import('@/views/promotion/PromotionView.vue'), meta: { title: '推广与佣金', group: '保障与结算', adminOnly: true } },
  { path: '/operators', name: 'operators', component: () => import('@/views/operators/OperatorsView.vue'), meta: { title: '操作员管理', group: '其他' } },
  { path: '/message', name: 'message', component: () => import('@/views/messages/MessagesView.vue'), meta: { title: '消息中心', group: '其他', badge: true } },
  { path: '/settings', name: 'settings', component: () => import('@/views/settings/SettingsView.vue'), meta: { title: '账户设置', group: '其他' } },
  { path: '/login', name: 'login', component: () => import('@/views/auth/LoginView.vue'), meta: { title: '登录' } },
  { path: '/agent-portal', name: 'agent-portal', component: () => import('@/views/agent-portal/AgentPortalView.vue'), meta: { title: '业务员工作台' } },
  { path: '/certificate/:type/:id', name: 'certificate', component: () => import('@/views/certificate/CertificateView.vue'), meta: { title: '参保证明' } },
]
