import { client } from './client'
import type { AuditLogItem, MessageItem, ProviderStatus } from './types'

export function listMessages() {
  return client.get<MessageItem[]>('/messages').then((r) => r.data)
}

export function listAuditLogs(limit = 100) {
  return client.get<AuditLogItem[]>('/audit-logs', { params: { limit } }).then((r) => r.data)
}

export function getProviderStatus() {
  return client.get<ProviderStatus>('/providers/status').then((r) => r.data)
}

export function sendNotification(data: { kind: 'sms' | 'email'; recipient: string; subject?: string; content: string; template?: string }) {
  return client.post('/notifications/send', data).then((r) => r.data)
}
