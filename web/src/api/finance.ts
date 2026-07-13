import { client } from './client'
import type { Invoice } from './types'

export function listInvoices() {
  return client.get<Invoice[]>('/invoices').then((r) => r.data)
}

export function createInvoice(data: { enterprise_id: number; account: 'premium' | 'usage'; amount: number; title: string; tax_no?: string; email?: string }) {
  return client.post<Invoice>('/invoices', data).then((r) => r.data)
}

export function updateInvoiceStatus(id: number, status: string) {
  return client.patch<Invoice>(`/invoices/${id}`, { status }).then((r) => r.data)
}

export function createPayment(data: { enterprise_id: number; account: 'premium' | 'usage'; amount: number }) {
  return client.post('/payments', data).then((r) => r.data)
}

export function reconcilePayments() {
  return client.get('/payments/reconcile').then((r) => r.data)
}

export const INVOICE_STATUS_TEXT: Record<string, string> = {
  pending: '待审核',
  approved: '已审核',
  issued: '已开票',
  rejected: '已驳回',
}
