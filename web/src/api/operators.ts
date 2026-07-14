import { client } from './client'
import type { Operator } from './types'

export function listOperators() {
  return client.get<Operator[]>('/operators').then((r) => r.data)
}

export function createOperator(data: { enterprise_id?: number; username: string; password: string; name: string; phone?: string }) {
  return client.post<Operator>('/operators', data).then((r) => r.data)
}

export function updateOperator(id: number, data: { name?: string; phone?: string; password?: string; active?: boolean; enterprise_id?: number }) {
  return client.patch<Operator>(`/operators/${id}`, data).then((r) => r.data)
}
