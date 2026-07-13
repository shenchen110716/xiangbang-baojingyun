import { client } from './client'
import type { EnrollmentEmailLog, EnrollmentSummaryRow } from './types'

export function getEnrollmentSummary(date?: string) {
  return client.get<EnrollmentSummaryRow[]>('/enrollment/summary', { params: date ? { date } : undefined }).then((r) => r.data)
}

export function exportEnrollmentUrl(kind: 'enrollment' | 'termination', date?: string, planId?: number) {
  const params = new URLSearchParams({ kind })
  if (date) params.set('date', date)
  if (planId) params.set('plan_id', String(planId))
  return `/api/enrollment/export?${params.toString()}`
}

export function sendEnrollment(enterprise_id: number, plan_id: number, kind: 'enrollment' | 'termination' = 'enrollment') {
  return client.post('/enrollment/send', null, { params: { enterprise_id, plan_id, kind } }).then((r) => r.data)
}

export function emailEnrollment(enterprise_id: number, plan_id: number, kind: 'enrollment' | 'termination' = 'enrollment', date?: string) {
  return client.post('/enrollment/email', null, { params: { enterprise_id, plan_id, kind, date } }).then((r) => r.data)
}

export function listEnrollmentEmails() {
  return client.get<EnrollmentEmailLog[]>('/enrollment/emails').then((r) => r.data)
}
