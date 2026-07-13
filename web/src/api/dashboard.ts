import { client } from './client'
import type { DashboardData, ScreenProduct } from './types'

export function getDashboard() {
  return client.get<DashboardData>('/dashboard').then((r) => r.data)
}

export function getScreenProducts() {
  return client.get<ScreenProduct[]>('/screen/products').then((r) => r.data)
}
