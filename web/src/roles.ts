import { ref } from 'vue'

export type RoleKey = 'worker' | 'employer' | 'broker' | 'platform'

/**
 * 界面身份。**只影响看到什么,不影响能做什么** ——
 * 权限一律由后端裁决(归属校验 + 平台角色),前端切身份不会多出任何权力。
 * 把它当权限用是危险的:任何人都能改本地存储。
 */
export const role = ref<RoleKey>((localStorage.getItem('xbb.role') as RoleKey) || 'worker')

export function setRole(r: RoleKey) {
  role.value = r
  localStorage.setItem('xbb.role', r)
}

export const ROLES: { key: RoleKey; label: string; hint: string }[] = [
  { key: 'worker',   label: '我要找活', hint: '找活、报名、签协议、看工资' },
  { key: 'employer', label: '我要招人', hint: '组织、发岗、审应聘、确认履约' },
  { key: 'broker',   label: '经纪人',   hint: '绑定工人、看佣金' },
  { key: 'platform', label: '平台方',   hint: '审核、放款、运维（需平台角色）' },
]

export const NAV: Record<RoleKey, { to: string; label: string; badge?: boolean }[]> = {
  worker: [
    { to: '/jobs', label: '找活' },
    { to: '/my-applications', label: '我的报名' },
    { to: '/my-wages', label: '我的工资' },
    { to: '/my-profile', label: '画像与信用' },
    { to: '/review', label: '评价' },
    { to: '/notifications', label: '消息', badge: true },
  ],
  employer: [
    { to: '/my-orgs', label: '我的组织' },
    { to: '/my-jobs', label: '我的岗位' },
    { to: '/voice', label: '语音发单' },
    { to: '/talent', label: '人才库' },
    { to: '/review', label: '评价' },
    { to: '/notifications', label: '消息', badge: true },
  ],
  broker: [
    { to: '/broker', label: '经纪人中心' },
    { to: '/notifications', label: '消息', badge: true },
  ],
  platform: [
    { to: '/settings', label: '参数设置' },
    { to: '/review-orgs', label: '组织审核' },
    { to: '/payouts', label: '资金与代发' },
    { to: '/settlements', label: '结算处理' },
    { to: '/ops', label: '事件投递监控' },
    { to: '/roles', label: '角色管理' },
  ],
}
