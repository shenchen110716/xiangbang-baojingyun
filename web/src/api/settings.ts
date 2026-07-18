import { client } from './client'

export interface SettingItem {
  key: string
  label: string
  secret: boolean
  kind: 'text' | 'password' | 'select' | 'bool'
  options?: string[] | null
  hint?: string
  configured: boolean
  value: string
}
export interface SettingGroup {
  group: string
  items: SettingItem[]
}

export function getSystemSettings() {
  return client.get<{ groups: SettingGroup[] }>('/system-settings').then((r) => r.data.groups)
}

export function updateSystemSettings(values: Record<string, string>) {
  return client.put<{ groups: SettingGroup[] }>('/system-settings', { values }).then((r) => r.data.groups)
}
