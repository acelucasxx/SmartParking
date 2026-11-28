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
