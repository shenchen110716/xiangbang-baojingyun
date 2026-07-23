import { client } from './client'
import type { Insurer } from './types'

export function getInsurerProfile() {
  return client.get<Insurer>('/insurer-portal/profile').then((response) => response.data)
}

export function submitInsurerProfileEdit(data: Partial<{ name: string; contact: string; phone: string }>) {
  return client.patch<Insurer>('/insurer-portal/profile', data).then((response) => response.data)
}
