import { client } from './client'
import type { BillingRow, Policy, ReportRow } from './types'

export function listPolicies() {
  return client.get<Policy[]>('/policies').then((r) => r.data)
}

export function exportPolicyUrl(id: number) {
  return `/api/policies/${id}/export`
}

export function getReports() {
  return client.get<ReportRow[]>('/reports').then((r) => r.data)
}

export function getBilling() {
  return client.get<BillingRow[]>('/billing').then((r) => r.data)
}
