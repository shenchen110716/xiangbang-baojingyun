import { client } from './client'

export interface EnrollSubmission {
  id: number
  position_id: number
  position_name: string
  actual_employer: string
  enterprise_id: number | null
  enterprise_name: string
  plan_name: string
  payment_mode: string
  payment_mode_label: string
  name: string
  id_number_masked: string
  phone: string
  payment_status: string
  payment_status_label: string
  order_no: string
  review_status: string
  review_status_label: string
  review_note: string
  reviewed_at: string | null
  insured_person_id: number | null
  created_at: string
}

export function listEnrollSubmissions(params?: { review_status?: string; position_id?: number }) {
  return client.get<EnrollSubmission[]>('/enroll-submissions', { params }).then((r) => r.data)
}

export function reviewEnrollSubmission(id: number, data: { status: 'approved' | 'rejected'; review_note?: string }) {
  return client.patch<EnrollSubmission>(`/enroll-submissions/${id}/review`, data).then((r) => r.data)
}

export async function exportEnrollSubmissions(params?: { review_status?: string }) {
  const response = await client.get('/enroll-submissions/export', { params, responseType: 'blob' })
  const url = URL.createObjectURL(response.data as Blob)
  const link = document.createElement('a')
  link.href = url
  link.download = 'enroll-submissions.xlsx'
  link.click()
  URL.revokeObjectURL(url)
}
