import { client } from './client'
import type { TimelinessDetail, TimelinessSummary } from './types'

export interface TimelinessFilters {
  operation_type?: string
  timeliness_status?: string
  responsibility_reason?: string
  responsible_user_id?: number
  actual_employer_id?: number
  since?: string
  until?: string
}

function clean(filters: TimelinessFilters): Record<string, string | number> {
  const params: Record<string, string | number> = {}
  for (const [key, value] of Object.entries(filters)) {
    if (value !== undefined && value !== null && value !== '') params[key] = value
  }
  return params
}

export function getTimelinessSummary(filters: TimelinessFilters = {}) {
  return client
    .get<TimelinessSummary>('/timeliness/summary', { params: clean(filters) })
    .then((response) => response.data)
}

export function getTimelinessDetails(filters: TimelinessFilters = {}) {
  return client
    .get<{ items: TimelinessDetail[] }>('/timeliness/details', { params: clean(filters) })
    .then((response) => response.data.items)
}

export function getTimelinessDataQuality() {
  return client
    .get<{ items: TimelinessDetail[] }>('/timeliness/data-quality')
    .then((response) => response.data.items)
}

export function recalculateTimeliness() {
  return client
    .post<{ queued: number; processed: number; failed: number }>('/timeliness/recalculate')
    .then((response) => response.data)
}

/** Streams the audited XLSX; the server records filters, exporter, row count and digest. */
export function exportTimeliness(filters: TimelinessFilters = {}) {
  return client
    .get('/timeliness/export', { params: clean(filters), responseType: 'blob' })
    .then((response) => response.data as Blob)
}
