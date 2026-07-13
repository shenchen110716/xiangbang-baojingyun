import { client } from './client'
import type { Agent, AgentCommission } from './types'

export function listAgents() {
  return client.get<Agent[]>('/agents').then((r) => r.data)
}

export function createAgent(data: { username: string; password: string; name: string; phone?: string }) {
  return client.post('/agents', data).then((r) => r.data)
}

export function setAgentStatus(id: number, status: string) {
  return client.patch(`/agents/${id}/status`, null, { params: { status } }).then((r) => r.data)
}

export function getAgentCommissions(id: number) {
  return client.get<AgentCommission[]>(`/agents/${id}/commissions`).then((r) => r.data)
}

export function listAgentCommissions() {
  return client.get<AgentCommission[]>('/agent-commissions').then((r) => r.data)
}

export function createAgentCommission(data: { agent_id: number; enterprise_id: number; plan_id: number; rate?: number; mode: string; markup_amount?: number; sale_price?: number }) {
  return client.post<AgentCommission>('/agent-commissions', data).then((r) => r.data)
}

export function updateAgentCommission(id: number, data: Partial<AgentCommission>) {
  return client.patch<AgentCommission>(`/agent-commissions/${id}`, data).then((r) => r.data)
}

export function deleteAgentCommission(id: number) {
  return client.delete<{ ok: boolean }>(`/agent-commissions/${id}`).then((r) => r.data)
}
