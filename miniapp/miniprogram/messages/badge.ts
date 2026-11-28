import { api } from '../api/index'
import { badgeText } from './messages'

/** “消息”在 tabBar 里的位置（app.json） */
const MESSAGE_TAB = 1

/**
 * 刷新 tabBar 的未读角标。只能在 tabBar 页面调用（非 tabBar 页面时微信会报错，这里忽略）；失败不影响页面。
 * @returns 未读数，失败为 null
 */
export async function refreshBadge(): Promise<number | null> {
  try {
    const { count } = await api.get<{ count: number }>('/messages/unread-count')
    showBadge(count)
    return count
  } catch {
    return null
  }
}

export function showBadge(count: number): void {
  const text = badgeText(count)
  const noop = () => undefined
  if (text) wx.setTabBarBadge({ index: MESSAGE_TAB, text, fail: noop })
  else wx.removeTabBarBadge({ index: MESSAGE_TAB, fail: noop })
}
