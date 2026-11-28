import { ref } from 'vue'
import type { Paged } from '@/devices/types'

/**
 * 分页列表：load(page) 取一页；请求失败保留上一页数据（错误由请求层提示）。
 * 并发时只采用最后一次请求的结果，避免快速翻页时旧结果覆盖新结果。
 */
export function usePaged<T>(fetchPage: (page: number) => Promise<Paged<T>>) {
  const items = ref<T[]>([]) as { value: T[] }
  const total = ref(0)
  const page = ref(1)
  const loading = ref(false)
  const loaded = ref(false)
  let seq = 0

  async function load(p = page.value) {
    const mine = ++seq
    loading.value = true
    try {
      const data = await fetchPage(p)
      if (mine !== seq) return
      items.value = data.items
      total.value = data.total
      page.value = p
      loaded.value = true
    } catch {
      // 已由请求层提示
    } finally {
      if (mine === seq) loading.value = false
    }
  }

  return { items, total, page, loading, loaded, load }
}
