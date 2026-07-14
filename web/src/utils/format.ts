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

export function formatDateOnly(value: string | null | undefined): string {
  if (!value) return '—'
  return new Date(value).toLocaleDateString('zh-CN')
}

/** 次日生效方案的生效/停保时间本来就总是落在自然日边界上，只显示日期；
 * 即时生效方案的生效/停保时间精确到分钟才有意义（24 小时倒计时），要显
 * 示完整时间。见反馈：次日生效保险只显示日期、即时生效保险显示完整时间。 */
export function formatCoverageDate(value: string | null | undefined, effectiveMode: string | undefined): string {
  return effectiveMode === 'immediate' ? formatDateTime(value) : formatDateOnly(value)
}

export function insuredStatusLabel(person: { status: string; effective_at?: string | null }): { text: string; type: string } {
  if (person.status === 'active' && person.effective_at && new Date(person.effective_at) > new Date()) {
    return { text: '待生效', type: 'warning' }
  }
  const map: Record<string, { text: string; type: string }> = {
    active: { text: '在保', type: 'success' },
    pending: { text: '待审核', type: 'warning' },
    stopped: { text: '已停保', type: 'danger' },
  }
  return map[person.status] || { text: person.status, type: 'info' }
}
