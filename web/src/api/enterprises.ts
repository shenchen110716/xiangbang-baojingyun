import { client } from './client'
import type { Enterprise, LedgerResponse } from './types'

export function listEnterprises(params?: { q?: string; status?: string }) {
  return client.get<Enterprise[]>('/enterprises', { params }).then((r) => r.data)
}

export function createEnterprise(data: Partial<Enterprise>) {
  return client.post<Enterprise>('/enterprises', data).then((r) => r.data)
}

export function updateEnterprise(id: number, data: Partial<Enterprise>) {
  return client.patch<Enterprise>(`/enterprises/${id}`, data).then((r) => r.data)
}

export function deleteEnterprise(id: number) {
  return client.delete<{ ok: boolean }>(`/enterprises/${id}`).then((r) => r.data)
}

export function setEnterpriseStatus(id: number, status: string) {
  return client.patch<Enterprise>(`/enterprises/${id}/status`, null, { params: { status } }).then((r) => r.data)
}

export function rechargeEnterprise(id: number, account: 'premium' | 'usage', amount: number) {
  return client.post<Enterprise>(`/enterprises/${id}/recharge`, { account, amount }).then((r) => r.data)
}

export function listEnterpriseAdmins(id: number) {
  return client.get(`/enterprises/${id}/admins`).then((r) => r.data)
}

export function createEnterpriseAdmin(id: number, data: { username: string; password: string; name: string; phone?: string }) {
  return client.post(`/enterprises/${id}/admins`, data).then((r) => r.data)
}

export function listEnterpriseProducts(id: number) {
  return client.get(`/enterprises/${id}/products`).then((r) => r.data)
}

export function getEnterpriseLedger(id: number) {
  return client.get<LedgerResponse>(`/enterprises/${id}/ledger`).then((r) => r.data)
}

export function applyEnterprise(data: { enterprise_name: string; credit_code?: string; contact: string; phone: string; username: string; password: string; website?: string }) {
  return client.post<{ message: string }>('/enterprises/apply', data).then((r) => r.data)
}
