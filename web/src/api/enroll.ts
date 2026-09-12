import { client } from './client'

// 扫码参保公开接口（免登录）。client 会自动带上 Authorization 头（如果有），
// 后端这些端点不看它，无碍。

export interface EnrollInfo {
  position_name: string
  actual_employer: string
  payment_mode: string
  plan_name: string
  plan_price: number | null
  plan_billing_mode: string | null
}

export function fetchEnrollInfo(positionId: string, mode: string, token: string) {
  return client.get<EnrollInfo>(`/enroll/${positionId}/${mode}/${token}`).then((r) => r.data)
}

export function submitEnroll(
  positionId: string,
  mode: string,
  token: string,
  data: { name: string; id_number: string; phone?: string; website?: string },
) {
  return client
    .post<{ message: string; submission_id: number; payment_mode: string }>(
      `/enroll/${positionId}/${mode}/${token}`,
      data,
    )
    .then((r) => r.data)
}

export function createPaymentOrder(submissionId: number) {
  return client
    .post<{ submission_id: number; order_no: string; mweb_url: string; return_url: string }>(
      `/enroll/${submissionId}/payment-order`,
    )
    .then((r) => r.data)
}

export function fetchPaymentStatus(submissionId: number) {
  return client
    .get<{ submission_id: number; payment_mode: string; payment_status: string; order_no: string; review_status: string }>(
      `/enroll/${submissionId}/payment-status`,
    )
    .then((r) => r.data)
}
