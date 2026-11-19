import { client } from './client'
import type { OpenApiClient, OpenApiClientIssued, OpenApiRequestLogItem } from './types'

export function issueOpenApiClient(data: {
  enterprise_id: number
  name?: string
  scopes?: string[]
  env?: string
  ip_allowlist?: string[]
  allowed_employer_ids?: number[]
  expires_at?: string | null
}) {
  return client.post<OpenApiClientIssued>('/openapi-clients', data).then((r) => r.data)
}

export function listOpenApiClients(enterpriseId: number) {
  return client
    .get<OpenApiClient[]>('/openapi-clients', { params: { enterprise_id: enterpriseId } })
    .then((r) => r.data)
}

export function updateOpenApiClient(
  id: number,
  data: {
    name?: string
    status?: string
    scopes?: string[]
    ip_allowlist?: string[]
    expires_at?: string
  },
) {
  return client.patch<OpenApiClient>(`/openapi-clients/${id}`, data).then((r) => r.data)
}

export function listOpenApiRequestLogs(params: {
  key_id?: string
  enterprise_id?: number
  limit?: number
}) {
  return client
    .get<OpenApiRequestLogItem[]>('/openapi-request-logs', { params })
    .then((r) => r.data)
}
