import { client } from './client'

export interface ReceiptAmountResult {
  amount: number
  mock: boolean
  message: string
}

// 识别转账回单/发票金额，供充值时自动带出（到账仍需人工确认）。
export function recognizeReceiptAmount(file: File) {
  const form = new FormData()
  form.append('file', file)
  return client.post<ReceiptAmountResult>('/ocr/receipt-amount', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  }).then((r) => r.data)
}
