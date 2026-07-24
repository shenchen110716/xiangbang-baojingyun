import { client } from './client'
import type { Claim, ClaimDocument, Insurer, Invoice, InsuredPerson, Policy, PositionVideo, WorkPosition } from './types'

export function getInsurerProfile() {
  return client.get<Insurer>('/insurer-portal/profile').then((response) => response.data)
}

export function submitInsurerProfileEdit(data: Partial<{ name: string; contact: string; phone: string; credit_code: string; email: string; address: string }>) {
  return client.patch<Insurer>('/insurer-portal/profile', data).then((response) => response.data)
}

export function listInsurerPositions() {
  return client.get<WorkPosition[]>('/positions').then((response) => response.data)
}

export function reviewInsurerPosition(id: number, data: { occupation_class?: string; status: 'approved' | 'rejected' | 'supplement'; plan_id?: number | null; review_note?: string }) {
  return client.patch<WorkPosition>(`/positions/${id}/review`, data).then((response) => response.data)
}

export function listInsurerPositionVideos(positionId: number) {
  return client.get<PositionVideo[]>(`/positions/${positionId}/videos`).then((response) => response.data)
}

export function listInsurerPolicies() {
  return client.get<Policy[]>('/policies').then((response) => response.data)
}

export function uploadInsurerPolicyDocument(policyId: number, file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return client.post<Policy>(`/policies/${policyId}/document/upload`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  }).then((response) => response.data)
}

export interface InsurerSettlementRow {
  policy_id: number
  policy_no: string
  enterprise_name: string
  plan_name: string
  status: string
  premium: number
  insurance_base_price?: number
  policy_floor_price?: number
  insurer_settlement_price?: number
  minimum_sale_price?: number
  sale_price?: number
}

export interface InsurerSettlement {
  insurer_id: number
  total_active_premium: number
  rows: InsurerSettlementRow[]
}

export function getInsurerSettlement() {
  return client.get<InsurerSettlement>('/insurer-portal/settlement').then((response) => response.data)
}

export interface InsurerMonthlyPremium {
  month: string
  total_premium: number
  insured_count: number
}

export interface InsurerMonthlyPremiumRow {
  person_id: number
  person_name: string
  id_number: string
  enterprise_name: string
  policy_no: string
  billable_ratio: number
  unit_price: number
  amount: number
}

export function getInsurerMonthlyPremiumSummary(months = 12) {
  return client.get<InsurerMonthlyPremium[]>('/insurer-portal/settlement/monthly', { params: { months } }).then((response) => response.data)
}

export function getInsurerMonthlyPremiumDetail(month: string) {
  return client.get<InsurerMonthlyPremiumRow[]>(`/insurer-portal/settlement/monthly/${month}`).then((response) => response.data)
}

export function exportInsurerMonthlyPremium(month: string) {
  return client.get(`/insurer-portal/settlement/monthly/${month}/export`, { responseType: 'blob' }).then((response) => response.data as Blob)
}

export function listInsurerInvoices() {
  return client.get<Invoice[]>('/invoices').then((response) => response.data)
}

export function listInsurerInsured() {
  return client.get<InsuredPerson[]>('/insurer-portal/insured').then((response) => response.data)
}

export function flagInsuredPerson(id: number, reason: string) {
  return client.patch<InsuredPerson>(`/insured/${id}/insurer-flag`, { reason }).then((response) => response.data)
}

export function listInsurerClaims() {
  return client.get<Claim[]>('/claims').then((response) => response.data)
}

export function reviewInsurerClaim(id: number, data: { status: 'approved' | 'rejected' | 'supplement'; approved_amount?: number; rejection_reason?: string; note?: string }) {
  return client.patch<Claim>(`/claims/${id}/status`, data).then((response) => response.data)
}

export function listInsurerClaimDocuments(claimId: number) {
  return client.get<ClaimDocument[]>(`/claims/${claimId}/documents`).then((response) => response.data)
}
