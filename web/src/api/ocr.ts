import { client } from './client'

export interface ReceiptAmountResult {
  amount: number
  mock: boolean
  message: string
}

export interface IdCardResult {
  name: string
  id_number: string
  gender: string
  birth: string
  mock: boolean
  message: string
}

export interface BusinessLicenseResult {
  name: string
  credit_code: string
  mock: boolean
  message: string
}

// 识别身份证正面照，供新增参保员工时自动带出姓名/身份证号。
export function recognizeIdCard(file: File) {
  const form = new FormData()
  form.append('file', file)
  return client.post<IdCardResult>('/ocr/id-card', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  }).then((r) => r.data)
}

// 识别营业执照，供新增投保单位时自动带出单位全称/统一社会信用代码。
export function recognizeBusinessLicense(file: File) {
  const form = new FormData()
  form.append('file', file)
  return client.post<BusinessLicenseResult>('/ocr/business-license', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  }).then((r) => r.data)
}

// 识别转账回单/发票金额，供充值时自动带出（到账仍需人工确认）。
export function recognizeReceiptAmount(file: File) {
  const form = new FormData()
  form.append('file', file)
  return client.post<ReceiptAmountResult>('/ocr/receipt-amount', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  }).then((r) => r.data)
}
