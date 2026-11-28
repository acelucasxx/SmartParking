import { api, isApiError } from '../../api/index'
import type { DeviceDetail, OperationRecord, Page } from '../../api/types'
import { HOME_PAGE } from '../../auth/flow'
import { type Section, dayKey, emptyText, groupByDay, hasMore, mergePage } from '../../records/records'

/**
 * 操作记录（PRD 6.1 第 11 页）：时间、操作人、动作、通道、结果；可按日期筛选。
 * 主人还能看到遥控器、地感、延时自动关等非 App 触发的动作（PRD 8.7，带“约”的为定时查询发现）；被授权人只看本人记录。
 * 异常态：空态。
 */
const SIZE = 20

Page({
  data: {
    deviceId: 0,
    isOwner: false,
    /** 按日筛选，空为全部 */
    date: '',
    today: dayKey(Date.now()),
    sections: [] as Section[],
    loading: true,
    loadingMore: false,
    more: false,
    error: '',
    empty: '',
  },
  records: [] as OperationRecord[],
  page: 0,
  /** 只采用最新一次查询的结果（切换日期时旧请求可能晚到） */
  seq: 0,

  onLoad(query: Record<string, string | undefined>) {
    const id = Number(query.id)
    if (!Number.isInteger(id) || id <= 0) {
      wx.switchTab({ url: HOME_PAGE })
      return
    }
    this.setData({ deviceId: id })
    // 角色决定空态文案与说明；失败不影响列表
    api
      .get<DeviceDetail>(`/devices/${id}`)
      .then((d) => this.setData({ isOwner: d.role === 'OWNER' }))
      .catch(() => undefined)
    void this.reload()
  },

  onPullDownRefresh() {
    void this.reload().finally(() => wx.stopPullDownRefresh())
  },

  onReachBottom() {
    if (this.data.more && !this.data.loadingMore && !this.data.loading) void this.loadPage(this.page + 1)
  },

  reload() {
    this.records = []
    this.page = 0
    this.setData({ loading: true, sections: [], more: false, error: '' })
    return this.loadPage(1)
  },

  async loadPage(p: number) {
    const mine = ++this.seq
    if (p > 1) this.setData({ loadingMore: true })
    try {
      const query: Record<string, string | number> = { page: p, size: SIZE }
      if (this.data.date) query.date = this.data.date
      const res = await api.get<Page<OperationRecord>>(`/devices/${this.data.deviceId}/records`, query)
      if (mine !== this.seq) return
      this.records = mergePage(this.records, res.items)
      this.page = p
      this.setData({
        sections: groupByDay(this.records, Date.now()),
        more: hasMore(res),
        loading: false,
        loadingMore: false,
        empty: this.records.length ? '' : emptyText(this.data.date, this.data.isOwner),
      })
    } catch (e) {
      if (mine !== this.seq) return
      if (isApiError(e) && e.action === 'leave') {
        wx.showToast({ title: e.message, icon: 'none' })
        setTimeout(() => wx.switchTab({ url: HOME_PAGE }), 1500)
        return
      }
      const message = isApiError(e) ? e.message : '加载失败'
      // 首页失败显示重试；加载更多失败只提示
      if (p === 1) this.setData({ loading: false, error: message })
      else {
        this.setData({ loadingMore: false })
        wx.showToast({ title: message, icon: 'none' })
      }
    }
  },

  onPickDate(e: WechatMiniprogram.PickerChange) {
    this.setData({ date: String(e.detail.value) })
    void this.reload()
  },

  onAllDates() {
    if (!this.data.date) return
    this.setData({ date: '' })
    void this.reload()
  },
})
