import { client } from './client'

export interface CreatePaymentResult {
  order_no: string
  status: 'pending' | 'paid' | 'failed'
  channel: 'native' | 'jsapi'
  code_url?: string
  request_id: string
}

export function createPayment(data: { enterprise_id: number; account: 'premium' | 'usage'; amount: number; channel: 'native' | 'jsapi' }) {
  return client.post<CreatePaymentResult>('/payments', data).then((r) => r.data)
}

export interface PaymentStatus {
  order_no: string
  status: 'pending' | 'paid' | 'failed'
  amount: number
  account: 'premium' | 'usage'
  channel: 'native' | 'jsapi'
  paid_at: string | null
}

export function getPaymentStatus(orderNo: string) {
  return client.get<PaymentStatus>(`/payments/${orderNo}`).then((r) => r.data)
}

export interface PaymentRecordRow {
  order_no: string
  enterprise_id: number
  enterprise_name: string
  account: 'premium' | 'usage'
  amount: number
  status: 'pending' | 'paid' | 'failed'
  provider: string
  channel: 'native' | 'jsapi'
  provider_trade_no: string | null
  created_at: string
  paid_at: string | null
}

export function listPayments(params: { enterprise_id?: number; status?: string; channel?: string } = {}) {
  return client.get<PaymentRecordRow[]>('/payments', { params }).then((r) => r.data)
}
