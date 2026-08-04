import { reactive } from 'vue'

/** 登录态。放 localStorage 是为了刷新不掉线;token 本身由后端签发,前端只是搬运。 */
export const auth = reactive({
  token: localStorage.getItem('xbb.token') || '',
  userId: Number(localStorage.getItem('xbb.userId') || 0),
  phone: localStorage.getItem('xbb.phone') || '',
  /** 取验证码用的开发口令。只存在本地,不进构建产物 —— 打包进去等于公开。 */
  devToken: localStorage.getItem('xbb.devToken') || '',
})

export function setAuth(token: string, userId: number, phone: string) {
  auth.token = token; auth.userId = userId; auth.phone = phone
  localStorage.setItem('xbb.token', token)
  localStorage.setItem('xbb.userId', String(userId))
  localStorage.setItem('xbb.phone', phone)
}

export function setDevToken(t: string) {
  auth.devToken = t
  localStorage.setItem('xbb.devToken', t)
}

export function logout() {
  auth.token = ''; auth.userId = 0; auth.phone = ''
  localStorage.removeItem('xbb.token')
  localStorage.removeItem('xbb.userId')
  localStorage.removeItem('xbb.phone')
}

export class ApiError extends Error {
  constructor(public status: number, message: string, public body?: unknown) {
    super(message)
  }
}

type Opts = { method?: string; body?: unknown; headers?: Record<string, string> }

/** 状态码 → 人能看懂的话。查不到就报状态码,总比一坨原始响应强。 */
const STATUS_TEXT: Record<number, string> = {
  400: '请求有误，请检查填写的内容',
  401: '登录已失效，请重新登录',
  403: '没有权限做这个操作',
  404: '找不到对应的数据',
  409: '当前状态不允许这个操作',
  429: '操作太频繁，稍后再试',
  500: '服务器出错了',
  502: '服务暂时不可用（可能正在启动）',
  503: '服务暂时不可用（可能正在启动）',
  504: '服务响应超时，请稍后重试',
}

/**
 * 把错误响应变成一句人话。
 *
 * <p>关键是**不能把非 JSON 的响应体原样显示**:网关返回的 502/504 是 HTML 页面,
 * 直接当消息显示会在界面上糊出一整坨 `<!DOCTYPE HTML …Error code: 404…`。
 * 这个只有真去看渲染结果才会发现——接口调用本身没报错,页面也没崩。
 */
function humanError(status: number, parsed: unknown): string {
  if (parsed && typeof parsed === 'object') {
    const m = (parsed as any).error ?? (parsed as any).message
    if (typeof m === 'string' && m.trim()) return m
  }
  if (typeof parsed === 'string') {
    const t = parsed.trim()
    const looksLikeHtml = /^</.test(t) || /<html|<!doctype/i.test(t)
    if (t && !looksLikeHtml && t.length <= 120) return t
  }
  return STATUS_TEXT[status] ?? `请求失败（${status}）`
}

export async function api<T = any>(path: string, opts: Opts = {}): Promise<T> {
  const headers: Record<string, string> = { ...(opts.headers || {}) }
  if (opts.body !== undefined) headers['Content-Type'] = 'application/json'
  if (auth.token) headers['Authorization'] = `Bearer ${auth.token}`

  const res = await fetch(path, {
    method: opts.method || (opts.body !== undefined ? 'POST' : 'GET'),
    headers,
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  })

  const text = await res.text()
  let parsed: any = undefined
  if (text) { try { parsed = JSON.parse(text) } catch { parsed = text } }

  if (!res.ok) {
    // 401 说明 token 没了或过期。就地清掉,否则每个页面都会拿着一个死 token 反复失败,
    // 而界面上看着像"服务器坏了"。
    if (res.status === 401 && auth.token) logout()
    throw new ApiError(res.status, humanError(res.status, parsed), parsed)
  }
  return parsed as T
}

/** 取验证码。走带口令的开发端点 —— 公开端点按设计**不会**回显验证码。 */
export async function fetchDevCode(phone: string): Promise<string> {
  if (!auth.devToken) {
    throw new ApiError(0, '需要先填写开发者口令,否则取不到验证码(公开端点按设计不回显)')
  }
  const r = await api<{ code: string }>('/api/identity/dev/code', {
    body: { phone },
    headers: { 'X-Dev-Token': auth.devToken },
  })
  return r.code
}

/** 金额:后端一律以「分」为单位存取,展示时才转元。绝不在前端做金额运算。 */
export function yuan(cents: number | null | undefined): string {
  if (cents === null || cents === undefined) return '—'
  const neg = cents < 0
  const a = Math.abs(cents)
  return `${neg ? '-' : ''}¥${Math.floor(a / 100)}.${String(a % 100).padStart(2, '0')}`
}

export function when(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (isNaN(d.getTime())) return String(iso)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}
