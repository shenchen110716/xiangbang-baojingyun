import { ref, shallowRef } from 'vue'

/**
 * 把「点一下 → 调接口 → 显示结果或错误」这套重复了几十遍的东西收成一处。
 *
 * 关键是 seq:并发点击时后发的请求可能先回来,不做序号校验的话
 * 界面会显示**过期请求的结果**,而且看不出哪里不对。
 */
export function useAction<T>(fn: (...args: any[]) => Promise<T>) {
  const loading = ref(false)
  const error = ref('')
  const data = shallowRef<T | null>(null)
  const done = ref(false)
  let seq = 0

  async function run(...args: any[]) {
    const mine = ++seq
    loading.value = true; error.value = ''; done.value = false
    try {
      const r = await fn(...args)
      if (mine !== seq) return           // 已有更新的请求发出,丢弃这次的结果
      data.value = r; done.value = true
      return r
    } catch (e: any) {
      if (mine !== seq) return
      error.value = e?.message || String(e)
    } finally {
      if (mine === seq) loading.value = false
    }
  }

  return { loading, error, data, done, run }
}
