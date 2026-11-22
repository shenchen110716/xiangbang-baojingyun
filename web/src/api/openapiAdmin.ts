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

// story-p3-compliance AC7：轮换密钥。响应带一次性新 secret；
// 旧 secret 自 rotated_at 起 7 天宽限期内仍可验签，超期废止。
export function rotateOpenApiClient(id: number) {
  return client.post<OpenApiClientIssued>(`/openapi-clients/${id}/rotate`).then((r) => r.data)
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
