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
