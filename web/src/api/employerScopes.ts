import { client } from './client'
import type { EmployerScope } from './types'

export function listEmployerScopes(enterpriseId?: number) {
  return client.get<EmployerScope[]>('/employer-scopes', {
    params: enterpriseId ? { enterprise_id: enterpriseId } : undefined,
  }).then((r) => r.data)
}

export function createEmployerScope(data: {
  user_id: number
  actual_employer_id: number
  responsibility_type: 'primary' | 'collaborator'
}) {
  return client.post<EmployerScope>('/employer-scopes', data).then((r) => r.data)
}

export function revokeEmployerScope(scopeId: number) {
  return client.delete<EmployerScope>(`/employer-scopes/${scopeId}`).then((r) => r.data)
}

export function replacePrimaryManager(actualEmployerId: number, userId: number) {
  return client.post<EmployerScope>(`/actual-employers/${actualEmployerId}/primary-manager`, {
    user_id: userId,
  }).then((r) => r.data)
}
