import { client } from './client'
import type { Insurer } from './types'

export function listInsurers() {
  return client.get<Insurer[]>('/insurers').then((response) => response.data)
}

export function createInsurer(data: { name: string; contact?: string; phone?: string; credit_code?: string; email?: string; address?: string }) {
  return client.post<Insurer>('/insurers', data).then((response) => response.data)
}

export function updateInsurer(id: number, data: Partial<{ name: string; contact: string; phone: string; credit_code: string; email: string; address: string }>) {
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

export interface InsurerAccount {
  id: number
  username: string
  name: string
  active: boolean
  status: string
  created_at: string
}

export function listInsurerAccounts(insurerId: number) {
  return client.get<InsurerAccount[]>(`/insurers/${insurerId}/accounts`).then((response) => response.data)
}

export function createInsurerAccount(insurerId: number, data: { username: string; password: string; name?: string }) {
  return client.post<InsurerAccount>(`/insurers/${insurerId}/accounts`, data).then((response) => response.data)
}

export function setInsurerAccountStatus(accountId: number, status: 'active' | 'paused') {
  return client.patch<InsurerAccount>(`/insurers/accounts/${accountId}/status`, null, { params: { status } }).then((response) => response.data)
}

export function resetInsurerAccountPassword(accountId: number, password: string) {
  return client.post<InsurerAccount>(`/insurers/accounts/${accountId}/reset-password`, { password }).then((response) => response.data)
}

export interface InsurerMonthlySettlementRow {
  month: string
  total_premium: number
  insured_count: number
  settled: boolean
  settled_at: string | null
}

export function getInsurerMonthlySettlement(insurerId: number, months = 12) {
  return client.get<InsurerMonthlySettlementRow[]>(`/insurers/${insurerId}/settlement/monthly`, { params: { months } }).then((response) => response.data)
}

export function markInsurerMonthSettled(insurerId: number, month: string, note = '') {
  return client.post(`/insurers/${insurerId}/settlement/${month}`, { note }).then((response) => response.data)
}

export function unmarkInsurerMonthSettled(insurerId: number, month: string) {
  return client.delete(`/insurers/${insurerId}/settlement/${month}`).then((response) => response.data)
}
