import { computed, ref, watch, type Ref } from 'vue'

/** Client-side pagination over an already-filtered list. Every list view in
 * the app renders from an in-memory array (loaded in full, then filtered),
 * so pagination here is just a slice — no server round-trip needed. Resets
 * to page 1 whenever the source list changes (e.g. a new search/filter). */
export function usePagedList<T>(source: Ref<T[]>, defaultPageSize = 20) {
  const page = ref(1)
  const pageSize = ref(defaultPageSize)

  watch(source, () => {
    page.value = 1
  })

  const total = computed(() => source.value.length)
  const paged = computed(() => {
    const start = (page.value - 1) * pageSize.value
    return source.value.slice(start, start + pageSize.value)
  })

  return { page, pageSize, total, paged }
}
