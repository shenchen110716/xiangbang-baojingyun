import { client } from './client'
import type { User } from './types'

export function login(username: string, password: string, portal: 'admin' | 'enterprise') {
  return client.post<{ access_token: string; token_type: string }>('/auth/login', { username, password, portal }).then((r) => r.data)
}

export function me() {
  return client.get<User>('/auth/me').then((r) => r.data)
}

export function changePassword(current_password: string, new_password: string) {
  return client.patch<{ ok: boolean }>('/auth/password', { current_password, new_password }).then((r) => r.data)
}
