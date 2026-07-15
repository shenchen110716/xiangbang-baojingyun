import { client } from '@/api/client'

function triggerDownload(blob: Blob, filename: string) {
  if (!blob.size) throw new Error('导出文件为空，请稍后重试')
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.style.display = 'none'
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

export async function downloadAuthenticated(path: string, filename: string) {
  const response = await client.get(path, { responseType: 'blob' })
  const blob = response.data as Blob
  if (blob.type.includes('application/json')) {
    try {
      const data = JSON.parse(await blob.text()) as { detail?: string; message?: string }
      throw new Error(data.detail || data.message || '文件下载失败')
    } catch (error) {
      if (error instanceof SyntaxError) throw new Error('文件下载失败')
      throw error
    }
  }
  triggerDownload(blob, filename)
}

export function downloadCsv(rows: unknown[][], filename: string) {
  const csv = '\ufeff' + rows
    .map((row) => row.map((value) => `"${String(value ?? '').replace(/"/g, '""')}"`).join(','))
    .join('\r\n')
  triggerDownload(new Blob([csv], { type: 'text/csv;charset=utf-8' }), filename)
}
