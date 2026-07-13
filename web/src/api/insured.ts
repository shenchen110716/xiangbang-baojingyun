import { client } from './client'
import type { InsuredPerson, PolicyMemberHistory } from './types'

export function listInsured(q?: string) {
  return client.get<InsuredPerson[]>('/insured', { params: q ? { q } : undefined }).then((r) => r.data)
}

export function createInsured(data: Partial<InsuredPerson>) {
  return client.post<InsuredPerson>('/insured', data).then((r) => r.data)
}

export function updateInsured(id: number, data: Partial<InsuredPerson>) {
  return client.patch<InsuredPerson>(`/insured/${id}`, data).then((r) => r.data)
}

export function setInsuredStatus(id: number, status: 'pending' | 'active' | 'stopped') {
  return client.patch<InsuredPerson>(`/insured/${id}/status`, null, { params: { status } }).then((r) => r.data)
}

export function bulkAddInsured(data: { enterprise_id: number; position_id: number; rows: Array<{ name: string; id_number: string; phone?: string }> }) {
  return client.post('/insured/bulk', data).then((r) => r.data)
}

export function importInsuredFile(kind: 'enrollment' | 'termination', enterprise_id: number, position_id: number, file: File) {
  const form = new FormData()
  form.append('kind', kind)
  form.append('enterprise_id', String(enterprise_id))
  form.append('position_id', String(position_id))
  form.append('file', file)
  return client.post('/insured/import-file', form).then((r) => r.data)
}

export function importTemplateUrl() {
  return '/api/insured/import-template'
}

export function getPolicyMembers(personId: number) {
  return client.get<PolicyMemberHistory[]>(`/insured/${personId}/policy-members`).then((r) => r.data)
}
