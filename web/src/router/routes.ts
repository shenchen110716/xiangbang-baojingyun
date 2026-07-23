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
  { path: '/home', name: 'home', component: () => import('@/views/dashboard/HomeView.vue'), meta: { title: '仪表盘', group: '' } },
  // 「经营大屏」的产品维度图表已并入首页（仪表盘），这里只保留跳转，避免旧链接 404。
  { path: '/screen', redirect: '/home', meta: { title: '经营大屏' } },
  // 企业端原来在这个路由看到的是「实际用工单位」管理（WorkUnitsPanel），已经挪到
  // 岗位参保方案页面（/dispatch）里做成一个按钮；这个路由现在只对平台管理员开放，
  // 展示全部投保单位列表（EnterprisesPanel）。
  { path: '/team', name: 'team', component: () => import('@/views/enterprises/TeamView.vue'), meta: { title: '投保单位管理', group: '业务管理', adminOnly: true } },
  { path: '/dispatch', name: 'dispatch', component: () => import('@/views/positions/PositionsView.vue'), meta: { title: '岗位参保方案', group: '业务管理' } },
  { path: '/workers', name: 'workers', component: () => import('@/views/insured/WorkEnrollmentView.vue'), meta: { title: '参保员工管理', group: '业务管理' } },
  // 劳动关系管理暂时从导航隐藏（去掉 group），路由本身还在，不会 404。
  { path: '/work-relations', name: 'workRelations', component: () => import('@/views/insured/WorkRelationsView.vue'), meta: { title: '劳动关系管理' } },
  { path: '/timeliness', name: 'timeliness', component: () => import('@/views/timeliness/TimelinessView.vue'), meta: { title: '参停保及时率', group: '业务管理' } },
  { path: '/agents', name: 'agents', component: () => import('@/views/agents/AgentsView.vue'), meta: { title: '业务员管理', group: '业务管理', adminOnly: true } },
  { path: '/insurance', name: 'insurance', component: () => import('@/views/plans/ProductsView.vue'), meta: { title: '保险产品管理', group: '产品与保司', adminOnly: true } },
  { path: '/policy', name: 'policy', component: () => import('@/views/policies/PolicyListView.vue'), meta: { title: '保单管理', group: '产品与保司' } },
  { path: '/claims', name: 'claims', component: () => import('@/views/claims/ClaimsView.vue'), meta: { title: '工伤理赔', group: '产品与保司' } },
  { path: '/insurers', name: 'insurers', component: () => import('@/views/plans/PlansAdminView.vue'), meta: { title: '保险公司', group: '产品与保司', adminOnly: true } },
  { path: '/insurer-management', name: 'insurerManagement', component: () => import('@/views/insurers/InsurerManagementView.vue'), meta: { title: '保司主体管理', group: '产品与保司', adminOnly: true } },
  // 「参停保中心」已并入「参保员工管理」页面的第二个 Tab（见 /workers），
  // 这里只保留一个跳转，避免旧收藏夹/书签链接直接 404。
  { path: '/exports', redirect: '/workers', meta: { title: '参停保中心' } },
  { path: '/billing', name: 'billing', component: () => import('@/views/billing/FinanceView.vue'), meta: { title: '资金与发票', group: '财务' } },
  { path: '/recharge', name: 'recharge', component: () => import('@/views/recharge/RechargeCenterView.vue'), meta: { title: '账户充值', group: '财务' } },
  { path: '/operators', name: 'operators', component: () => import('@/views/operators/OperatorsView.vue'), meta: { title: '单位账号管理', group: '财务' } },
  { path: '/pending-terminations', name: 'pendingTerminations', component: () => import('@/views/pending-terminations/PendingTerminationsView.vue'), meta: { title: '待处理停保', group: '保障与结算', adminOnly: true } },
  { path: '/promotion', name: 'promotion', component: () => import('@/views/promotion/PromotionView.vue'), meta: { title: '推广与佣金', group: '保障与结算', adminOnly: true } },
  { path: '/report', name: 'report', component: () => import('@/views/reports/ReportsView.vue'), meta: { title: '报表中心', group: '其他' } },
  { path: '/message', name: 'message', component: () => import('@/views/messages/MessagesView.vue'), meta: { title: '消息中心', group: '其他', badge: true } },
  { path: '/settings', name: 'settings', component: () => import('@/views/settings/SettingsView.vue'), meta: { title: '账户设置', group: '其他' } },
  { path: '/system-settings', name: 'systemSettings', component: () => import('@/views/settings/SystemSettingsView.vue'), meta: { title: '系统设置', group: '其他', adminOnly: true } },
  { path: '/login', name: 'login', component: () => import('@/views/auth/LoginView.vue'), meta: { title: '登录' } },
  { path: '/agent-portal', name: 'agent-portal', component: () => import('@/views/agent-portal/AgentPortalView.vue'), meta: { title: '业务员工作台' } },
  { path: '/insurer-portal', name: 'insurer-portal', component: () => import('@/views/insurer-portal/InsurerPortalView.vue'), meta: { title: '保司工作台' } },
  { path: '/certificate/:type/:id', name: 'certificate', component: () => import('@/views/certificate/CertificateView.vue'), meta: { title: '参保证明' } },
]
