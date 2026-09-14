import { client } from './client'
import type { InsurancePlan, PlanTier } from './types'

export function listPlans() {
  return client.get<InsurancePlan[]>('/plans').then((r) => r.data)
}

export function createPlan(data: Partial<InsurancePlan>) {
  return client.post<InsurancePlan>('/plans', data).then((r) => r.data)
}

export function updatePlan(id: number, data: Partial<InsurancePlan>) {
  return client.patch<InsurancePlan>(`/plans/${id}`, data).then((r) => r.data)
}

export function setPlanStatus(id: number, status: 'active' | 'paused') {
  return client.patch<InsurancePlan>(`/plans/${id}/status`, null, { params: { status } }).then((r) => r.data)
}

export function deletePlan(id: number) {
  return client.delete<{ ok: boolean; deleted_id: number }>(`/plans/${id}`).then((r) => r.data)
}

export function uploadPlanImage(id: number, file: File) {
  const form = new FormData()
  form.append('file', file)
  return client.post<InsurancePlan>(`/plans/${id}/image`, form).then((r) => r.data)
}

export function deletePlanImage(id: number) {
  return client.delete<InsurancePlan>(`/plans/${id}/image`).then((r) => r.data)
}

export function getPlanImageLink(id: number) {
  return client.get<{ url: string; image_name: string; expires: number }>(`/plans/${id}/image-link`).then((r) => r.data)
}

// 在线查看（新标签页 inline 展示）或下载（download=1 附件）方案图片。
// 签名 URL 只有 5 分钟有效期，所以每次点击都现取，不缓存。
export async function openPlanImage(id: number, download = false) {
  const link = await getPlanImageLink(id)
  window.open(download ? `${link.url}&download=1` : link.url, '_blank')
}

export function listPlanTiers(planId?: number) {
  return client.get<PlanTier[]>('/plan-tiers', { params: planId ? { plan_id: planId } : undefined }).then((r) => r.data)
}

export function createPlanTier(data: { plan_id: number; occupation_class: string; price: number; coverage?: string }) {
  return client.post<PlanTier>('/plan-tiers', data).then((r) => r.data)
}
