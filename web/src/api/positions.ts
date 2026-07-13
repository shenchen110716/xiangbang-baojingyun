import { client } from './client'
import type { ActualEmployer, PositionVideo, WorkPosition } from './types'

export function listPositions() {
  return client.get<WorkPosition[]>('/positions').then((r) => r.data)
}

export function createPosition(data: Partial<WorkPosition>) {
  return client.post<WorkPosition>('/positions', data).then((r) => r.data)
}

export function updatePosition(id: number, data: Partial<WorkPosition>) {
  return client.patch<WorkPosition>(`/positions/${id}`, data).then((r) => r.data)
}

export function deletePosition(id: number) {
  return client.delete<{ ok: boolean }>(`/positions/${id}`).then((r) => r.data)
}

export function reviewPosition(id: number, data: { status: string; occupation_class?: string; plan_id?: number | null; review_note?: string }) {
  return client.patch<WorkPosition>(`/positions/${id}/review`, data).then((r) => r.data)
}

export function listPositionVideos(id: number) {
  return client.get<PositionVideo[]>(`/positions/${id}/videos`).then((r) => r.data)
}

export function addPositionVideo(id: number, data: { name: string; url: string }) {
  return client.post<PositionVideo>(`/positions/${id}/videos`, data).then((r) => r.data)
}

export function uploadPositionVideo(id: number, file: File) {
  const form = new FormData()
  form.append('file', file)
  return client.post<PositionVideo>(`/positions/${id}/videos/upload`, form).then((r) => r.data)
}

export function reviewPositionVideo(videoId: number, data: { status: string; review_note?: string }) {
  return client.patch<PositionVideo>(`/position-videos/${videoId}/review`, data).then((r) => r.data)
}

export function deletePositionVideo(videoId: number) {
  return client.delete<{ ok: boolean }>(`/position-videos/${videoId}`).then((r) => r.data)
}

export function listActualEmployers() {
  return client.get<ActualEmployer[]>('/actual-employers').then((r) => r.data)
}

export function createActualEmployer(data: Partial<ActualEmployer>) {
  return client.post<ActualEmployer>('/actual-employers', data).then((r) => r.data)
}

export function updateActualEmployer(id: number, data: Partial<ActualEmployer>) {
  return client.patch<ActualEmployer>(`/actual-employers/${id}`, data).then((r) => r.data)
}

export function deleteActualEmployer(id: number) {
  return client.delete<{ ok: boolean }>(`/actual-employers/${id}`).then((r) => r.data)
}

export function setActualEmployerStatus(id: number, status: 'active' | 'paused') {
  return client.patch<ActualEmployer>(`/actual-employers/${id}/status`, null, { params: { status } }).then((r) => r.data)
}
