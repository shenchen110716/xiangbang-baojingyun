import { client } from './client'
import type { BillingRow, Policy, PremiumDetailReport, ReportRow } from './types'

export function listPolicies() {
  return client.get<Policy[]>('/policies').then((r) => r.data)
}

export function exportPolicyUrl(id: number) {
  return `/api/policies/${id}/export`
}

export function getReports() {
  return client.get<ReportRow[]>('/reports').then((r) => r.data)
}

export function getPremiumDetails(start_date: string, end_date: string, filters?: { enterprise_id?: number; insurer?: string; agent_id?: number }) {
  return client.get<PremiumDetailReport>('/reports/premium-details', { params: { start_date, end_date, ...filters } }).then((r) => r.data)
}

export function getBilling() {
  return client.get<BillingRow[]>('/billing').then((r) => r.data)
}
