import { client } from './client'
import type { ChecklistItem, Claim, ClaimDocument, ClaimTimelineItem } from './types'

export function listClaims(params?: { q?: string; status?: string; risk?: string; enterprise_id?: number }) {
  return client.get<Claim[]>('/claims', { params }).then((r) => r.data)
}

export function getClaim(id: number) {
  return client.get<Claim>(`/claims/${id}`).then((r) => r.data)
}

export function createClaim(data: Partial<Claim>) {
  return client.post<Claim>('/claims', data).then((r) => r.data)
}

export function updateClaim(id: number, data: Partial<Claim>) {
  return client.patch(`/claims/${id}`, data).then((r) => r.data)
}

export function setClaimStatus(id: number, data: { status: string; note?: string; approved_amount?: number; insurer_report_no?: string; rejection_reason?: string; paid_at?: string; current_handler?: string; sla_deadline?: string }) {
  return client.patch(`/claims/${id}/status`, data).then((r) => r.data)
}

export function listClaimDocuments(id: number) {
  return client.get<ClaimDocument[]>(`/claims/${id}/documents`).then((r) => r.data)
}

export function addClaimDocument(id: number, data: { name: string; url: string; doc_type: string }) {
  return client.post<ClaimDocument>(`/claims/${id}/documents`, data).then((r) => r.data)
}

export function uploadClaimDocument(id: number, docType: string, file: File) {
  const form = new FormData()
  form.append('doc_type', docType)
  form.append('file', file)
  return client.post<ClaimDocument>(`/claims/${id}/documents/upload`, form).then((r) => r.data)
}

export function reviewClaimDocument(claimId: number, documentId: number, data: { status: string; review_note?: string }) {
  return client.patch<ClaimDocument>(`/claims/${claimId}/documents/${documentId}`, data).then((r) => r.data)
}

export function deleteClaimDocument(claimId: number, documentId: number) {
  return client.delete<{ ok: boolean }>(`/claims/${claimId}/documents/${documentId}`).then((r) => r.data)
}

export function getClaimTimeline(id: number) {
  return client.get<ClaimTimelineItem[]>(`/claims/${id}/timeline`).then((r) => r.data)
}

export function getClaimChecklist(id: number) {
  return client.get<ChecklistItem[]>(`/claims/${id}/checklist`).then((r) => r.data)
}

export const CLAIM_STATUS_TEXT: Record<string, string> = {
  reported: '已报案',
  collecting: '材料收集中',
  submitted: '已提交平台',
  insurer_review: '保司审核中',
  supplement: '待补充材料',
  approved: '已核赔',
  paid: '已赔付',
  rejected: '已拒赔',
  closed: '已归档',
}

export const CLAIM_TRANSITIONS: Record<string, string[]> = {
  reported: ['collecting'],
  collecting: ['submitted'],
  submitted: ['insurer_review', 'supplement'],
  insurer_review: ['supplement', 'approved', 'rejected'],
  supplement: ['submitted', 'insurer_review'],
  approved: ['paid'],
  paid: ['closed'],
  rejected: ['closed'],
  closed: [],
}

export const CLAIM_RISK_TEXT: Record<string, string> = {
  normal: '正常',
  attention: '关注',
  high: '高风险',
}
