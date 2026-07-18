import { client } from './client'

export interface ImportRow {
  row_no: number
  errors: string[]
  warnings: string[]
  masked_id: string
  person_name: string
  actual_employer: string
  external_employee_no: string
  match_status: string
  match_method: string
}

export interface ImportPreview {
  batch_id: number
  confirm_token: string
  preview_version: number
  total_rows: number
  valid_rows: number
  invalid_rows: number
  rows: ImportRow[]
}

export interface ImportConfirmResult {
  batch_id: number
  status: string
  created_facts: number
}

// 下载标准模板（含入职/离职时间列）。
export function downloadEmploymentTemplate() {
  return client
    .get('/employment-feedback/template', { responseType: 'blob' })
    .then((r) => r.data as Blob)
}

// 上传文件，返回预览（校验结果 + 每行状态），不落库。
export function importEmploymentPreview(file: File, enterpriseId?: number) {
  const form = new FormData()
  form.append('file', file)
  return client
    .post<ImportPreview>('/employment-feedback/import/preview', form, {
      params: enterpriseId ? { enterprise_id: enterpriseId } : undefined,
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    .then((r) => r.data)
}

// 确认导入该批次，正式生成用工事实。
export function importEmploymentConfirm(batchId: number, confirmToken: string) {
  return client
    .post<ImportConfirmResult>('/employment-feedback/import/confirm', { batch_id: batchId, confirm_token: confirmToken })
    .then((r) => r.data)
}
