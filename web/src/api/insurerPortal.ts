import { client } from './client'
import type { Insurer, Policy, WorkPosition } from './types'

export function getInsurerProfile() {
  return client.get<Insurer>('/insurer-portal/profile').then((response) => response.data)
}

export function submitInsurerProfileEdit(data: Partial<{ name: string; contact: string; phone: string }>) {
  return client.patch<Insurer>('/insurer-portal/profile', data).then((response) => response.data)
}

export function listInsurerPositions() {
  return client.get<WorkPosition[]>('/positions').then((response) => response.data)
}

export function reviewInsurerPosition(id: number, data: { occupation_class?: string; status: 'approved' | 'rejected' | 'supplement'; plan_id?: number | null; review_note?: string }) {
  return client.patch<WorkPosition>(`/positions/${id}/review`, data).then((response) => response.data)
}

export function listInsurerPolicies() {
  return client.get<Policy[]>('/policies').then((response) => response.data)
}

export function uploadInsurerPolicyDocument(policyId: number, file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return client.post<Policy>(`/policies/${policyId}/document/upload`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  }).then((response) => response.data)
}

export interface InsurerSettlementRow {
  policy_id: number
  policy_no: string
  enterprise_name: string
  plan_name: string
  status: string
  premium: number
  insurance_base_price?: number
  policy_floor_price?: number
  insurer_settlement_price?: number
  minimum_sale_price?: number
  sale_price?: number
}

export interface InsurerSettlement {
  insurer_id: number
  total_active_premium: number
  rows: InsurerSettlementRow[]
}

export function getInsurerSettlement() {
  return client.get<InsurerSettlement>('/insurer-portal/settlement').then((response) => response.data)
}
