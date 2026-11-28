/**
 * 文件下载的纯逻辑：从 Content-Disposition 取文件名；下载接口出错时返回的是 JSON 信封而不是文件。
 * 不依赖浏览器 API，可在 Node 里直接单测。
 */

/**
 * 优先取 RFC 5987 的 `filename*=UTF-8''…`（服务端中文文件名用它），其次 `filename="…"`，都没有用 fallback。
 */
export function filenameFromDisposition(
  header: string | null | undefined,
  fallback: string,
): string {
  if (!header) return fallback
  const star = /filename\*\s*=\s*([^']*)'[^']*'([^;]+)/i.exec(header)
  if (star) {
    try {
      const name = decodeURIComponent(star[2].trim().replace(/^"|"$/g, ''))
      if (name) return sanitize(name, fallback)
    } catch {
      // 编码坏了：退回 filename=
    }
  }
  const plain = /filename\s*=\s*("([^"]*)"|[^;]+)/i.exec(header)
  const name = plain ? (plain[2] ?? plain[1]).trim() : ''
  // RFC 2047 编码（=?UTF-8?Q?…?=）浏览器无法直接用，宁可用 fallback
  if (!name || name.startsWith('=?')) return fallback
  return sanitize(name, fallback)
}

/** 去掉路径部分，防止文件名里带目录 */
function sanitize(name: string, fallback: string): string {
  const base = name.split(/[/\\]/).pop()?.trim()
  return base || fallback
}

/** 下载接口的响应是不是错误信封（按 Content-Type 判断） */
export function isJsonResponse(contentType: string | null | undefined): boolean {
  return !!contentType && /application\/(problem\+)?json/i.test(contentType)
}
