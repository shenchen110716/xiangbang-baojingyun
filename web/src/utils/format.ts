export function money(value: number | null | undefined): string {
  return `¥ ${Number(value || 0).toFixed(2)}`
}

export function commissionModeText(mode: string | undefined): string {
  return mode === 'price' ? '输入销售价格' : '按比例返佣'
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  return new Date(value).toLocaleString('zh-CN')
}
