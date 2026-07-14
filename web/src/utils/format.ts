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
