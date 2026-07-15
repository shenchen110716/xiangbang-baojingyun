import { client } from './client'
import type { PendingTermination } from './types'

export function listPendingTerminations() {
  return client.get<PendingTermination[]>('/pending-terminations').then((response) => response.data)
}

export function confirmPendingTermination(id: number) {
  return client
    .post<PendingTermination & { terminated_count: number }>(`/pending-terminations/${id}/confirm`)
    .then((response) => response.data)
}
