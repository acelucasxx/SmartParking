/**
 * 后台的时间显示：统一按东八区（接口时间带 +08:00），与浏览器所在时区无关。解析失败原样返回，空为“—”。
 * 不依赖 Vue，可在 Node 里直接单测。
 */
const OFFSET_MS = 8 * 3_600_000
const pad = (n: number) => String(n).padStart(2, '0')

export function formatDateTime(iso: string | null | undefined, withSeconds = false): string {
  if (!iso) return '—'
  const ms = Date.parse(iso)
  if (Number.isNaN(ms)) return iso
  const d = new Date(ms + OFFSET_MS)
  const date = `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`
  const time = `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}${withSeconds ? `:${pad(d.getUTCSeconds())}` : ''}`
  return `${date} ${time}`
}

/**
 * 日期范围选择器（YYYY-MM-DD，按东八区的自然日）→ 接口的 from（含）/ to（不含）：
 * 结束日当天也包含在内，所以 to 取结束日的次日 0 点。
 */
export function dayRange(
  range: [string, string] | null | undefined,
): { from: string; to: string } | null {
  if (!range || !range[0] || !range[1]) return null
  const re = /^\d{4}-\d{2}-\d{2}$/
  if (!re.test(range[0]) || !re.test(range[1])) return null
  const [start, end] = range[0] <= range[1] ? range : [range[1], range[0]]
  const next = new Date(Date.parse(`${end}T00:00:00Z`) + 86_400_000)
  if (Number.isNaN(next.getTime())) return null
  const nextDay = `${next.getUTCFullYear()}-${pad(next.getUTCMonth() + 1)}-${pad(next.getUTCDate())}`
  return { from: `${start}T00:00:00+08:00`, to: `${nextDay}T00:00:00+08:00` }
}
