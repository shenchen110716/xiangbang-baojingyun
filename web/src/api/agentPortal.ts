import { client } from './client'
import type {
  AgentBalances,
  AgentCommissionRow,
  AgentCommissionSummary,
  AgentPayment,
  AgentProduct,
  AgentStatement,
} from './types'

export interface AgentCommissionFilters {
  enterprise_id?: number
  insurer?: string
  plan_id?: number
}

function clean(filters: AgentCommissionFilters): Record<string, string | number> {
  const params: Record<string, string | number> = {}
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== null && value !== '') params[key] = value
  }
  return params
}

export function getAgentProducts() {
  return client
    .get<{ items: AgentProduct[] }>('/agent-portal/products')
    .then((response) => response.data.items)
}

export function getAgentBalances() {
  return client.get<AgentBalances>('/agent-portal/balances').then((response) => response.data)
}

export function getAgentCommissionSummary(filters: AgentCommissionFilters = {}) {
  return client
    .get<AgentCommissionSummary>('/agent-portal/commissions/summary', { params: clean(filters) })
    .then((response) => response.data)
}

export function getAgentCommissionDetails(filters: AgentCommissionFilters = {}) {
  return client
    .get<{ items: AgentCommissionRow[] }>('/agent-portal/commissions/details', { params: clean(filters) })
    .then((response) => response.data.items)
}

export function exportAgentCommissions(filters: AgentCommissionFilters = {}) {
  return client
    .get('/agent-portal/commissions/export', { params: clean(filters), responseType: 'blob' })
    .then((response) => response.data as Blob)
}

export function getAgentStatements() {
  return client.get<AgentStatement[]>('/agent-portal/statements').then((response) => response.data)
}

export function getAgentPayments() {
  return client.get<AgentPayment[]>('/agent-portal/payments').then((response) => response.data)
}
