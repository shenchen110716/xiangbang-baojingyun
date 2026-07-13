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

export function listPlanTiers(planId?: number) {
  return client.get<PlanTier[]>('/plan-tiers', { params: planId ? { plan_id: planId } : undefined }).then((r) => r.data)
}

export function createPlanTier(data: { plan_id: number; occupation_class: string; price: number; coverage?: string }) {
  return client.post<PlanTier>('/plan-tiers', data).then((r) => r.data)
}
