import { client } from '@/api/client'

export async function downloadAuthenticated(path: string, filename: string) {
  const response = await client.get(path, { responseType: 'blob' })
  const url = URL.createObjectURL(response.data as Blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
}
