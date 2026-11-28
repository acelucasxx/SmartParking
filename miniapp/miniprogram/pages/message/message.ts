import { api, isApiError } from '../../api/index'
import type { Message, Page as Paged } from '../../api/types'
import { refreshBadge, showBadge } from '../../messages/badge'
import { type MessageRow, markRead, mergeMessages, messageRow } from '../../messages/messages'

/**
 * 消息中心（PRD 6.1 第 13 页）：授权 / 撤销 / 到期 / 转让 / 解绑 / 故障等站内消息，最新的在前；点开标记已读，
 * 设备仍可用的进入控制页。异常态：空态。
 */
const SIZE = 20

Page({
  data: {
    rows: [] as MessageRow[],
    unread: 0,
    loading: true,
    loadingMore: false,
    more: false,
    error: '',
  },
  list: [] as Message[],
  page: 0,
  seq: 0,

  onShow() {
    // 每次进入都刷新：其他页面的操作可能产生了新消息
    void this.reload()
  },

  onPullDownRefresh() {
    void this.reload().finally(() => wx.stopPullDownRefresh())
  },

  onReachBottom() {
    if (this.data.more && !this.data.loadingMore && !this.data.loading) void this.loadPage(this.page + 1)
  },

  reload() {
    this.list = []
    this.page = 0
    this.setData({ loading: !this.data.rows.length, error: '' })
    return this.loadPage(1)
  },

  async loadPage(p: number) {
    const mine = ++this.seq
    if (p > 1) this.setData({ loadingMore: true })
    try {
      const [res, count] = await Promise.all([
        api.get<Paged<Message>>('/messages', { page: p, size: SIZE }),
        p === 1 ? refreshBadge() : Promise.resolve(null),
      ])
      if (mine !== this.seq) return
      this.list = mergeMessages(p === 1 ? [] : this.list, res.items)
      this.page = p
      this.render({ more: res.page * res.size < res.total, unread: count ?? this.data.unread })
    } catch (e) {
      if (mine !== this.seq) return
      const message = isApiError(e) ? e.message : '加载失败'
      if (p === 1 && !this.list.length) this.setData({ loading: false, error: message })
      else {
        this.setData({ loading: false, loadingMore: false })
        wx.showToast({ title: message, icon: 'none' })
      }
    }
  },

  render(extra: { more?: boolean; unread?: number } = {}) {
    const now = Date.now()
    this.setData({ rows: this.list.map((m) => messageRow(m, now)), loading: false, loadingMore: false, ...extra })
  },

  async onTap(e: WechatMiniprogram.BaseEvent) {
    const id = e.currentTarget.dataset.id as number
    const row = this.data.rows.find((r) => r.id === id)
    if (!row) return
    if (row.unread) {
      // 先在本地标记，失败也不打扰（下次进入会以服务端为准）
      this.list = markRead(this.list, [id])
      const unread = Math.max(0, this.data.unread - 1)
      this.render({ unread })
      showBadge(unread)
      api.post('/messages/read', { ids: [id] }).catch(() => undefined)
    }
    if (row.deviceId) wx.navigateTo({ url: `/pages/device/device?id=${row.deviceId}` })
  },

  async onReadAll() {
    if (!this.data.unread) return
    try {
      await api.post('/messages/read', { all: true })
      this.list = markRead(this.list, 'all')
      this.render({ unread: 0 })
      showBadge(0)
    } catch (e) {
      wx.showToast({ title: isApiError(e) ? e.message : '操作失败', icon: 'none' })
    }
  },
})
