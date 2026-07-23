import { client } from './client'
import type { Insurer } from './types'

export function listInsurers() {
  return client.get<Insurer[]>('/insurers').then((response) => response.data)
}

export function createInsurer(data: { name: string; contact?: string; phone?: string }) {
  return client.post<Insurer>('/insurers', data).then((response) => response.data)
}

export function updateInsurer(id: number, data: Partial<{ name: string; contact: string; phone: string }>) {
  return client.patch<Insurer>(`/insurers/${id}`, data).then((response) => response.data)
}

export function listPendingInsurerEdits() {
  return client.get<Insurer[]>('/insurers/pending-edits').then((response) => response.data)
}

export function reviewInsurerEdit(id: number, data: { approve: boolean; reject_reason?: string }) {
  return client.post<Insurer>(`/insurers/${id}/review-edit`, data).then((response) => response.data)
}

export function mergeInsurers(data: { source_ids: number[]; target_id: number }) {
  return client.post<Insurer>('/insurers/merge', data).then((response) => response.data)
}
