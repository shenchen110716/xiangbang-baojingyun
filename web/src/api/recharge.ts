import { client } from './client'
import type { InsurerAccount, InsurerAccountLink, RechargeRequest, PremiumAccountRow } from './types'

export function listInsurerAccounts() {
  return client.get<InsurerAccount[]>('/insurer-accounts').then((r) => r.data)
}

export function createInsurerAccount(data: { label: string; bank_name: string; account_no: string; account_holder: string }) {
  return client.post<InsurerAccount>('/insurer-accounts', data).then((r) => r.data)
}

export function updateInsurerAccount(id: number, data: Partial<{ label: string; bank_name: string; account_no: string; account_holder: string; status: 'active' | 'paused' }>) {
  return client.patch<InsurerAccount>(`/insurer-accounts/${id}`, data).then((r) => r.data)
}

export function listInsurerAccountLinks() {
  return client.get<InsurerAccountLink[]>('/insurer-account-links').then((r) => r.data)
}

export function createInsurerAccountLink(data: { insurer: string; account_id: number }) {
  return client.post<InsurerAccountLink>('/insurer-account-links', data).then((r) => r.data)
}

export function deleteInsurerAccountLink(id: number) {
  return client.delete<{ ok: boolean }>(`/insurer-account-links/${id}`).then((r) => r.data)
}

export function listRechargeRequests(status?: string) {
  return client.get<RechargeRequest[]>('/recharge-requests', { params: status ? { status } : undefined }).then((r) => r.data)
}

export function createRechargeRequest(data: { enterprise_id: number; account_type: 'premium' | 'usage'; insurer: string; amount: number; file: File }) {
  const form = new FormData()
  form.append('enterprise_id', String(data.enterprise_id))
  form.append('account_type', data.account_type)
  form.append('insurer', data.insurer)
  form.append('amount', String(data.amount))
  form.append('file', data.file)
  return client.post<RechargeRequest>('/recharge-requests', form, { headers: { 'Content-Type': 'multipart/form-data' } }).then((r) => r.data)
}

export function confirmRechargeRequest(id: number) {
  return client.patch<RechargeRequest>(`/recharge-requests/${id}/confirm`).then((r) => r.data)
}

export function rejectRechargeRequest(id: number, reason: string) {
  return client.patch<RechargeRequest>(`/recharge-requests/${id}/reject`, null, { params: { reason } }).then((r) => r.data)
}

export function getEnterprisePremiumAccounts(id: number) {
  return client.get<PremiumAccountRow[]>(`/enterprises/${id}/premium-accounts`).then((r) => r.data)
}
