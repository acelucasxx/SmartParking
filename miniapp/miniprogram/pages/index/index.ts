import { api, isApiError } from '../../api/index'
import type { DeviceCard } from '../../api/types'
import { deviceTitle, onlineBadge } from '../../utils/format'

/** 车位首页（MP-03 待实现）。先放一个最简列表，用于进入设备控制页（MP-04） */
Page({
  data: { items: [] as Array<{ id: number; title: string; badge: string }>, error: '' },
  onShow() {
    this.load()
  },
  async load() {
    try {
      const cards = await api.get<DeviceCard[]>('/devices')
      const items = cards.map((c) => ({ id: c.id, title: deviceTitle(c), badge: onlineBadge(c.enabled, c.onlineState, c.gateStatus).label }))
      this.setData({ items, error: '' })
    } catch (e) {
      this.setData({ error: isApiError(e) ? e.message : '加载失败' })
    }
  },
  onOpen(e: WechatMiniprogram.BaseEvent) {
    wx.navigateTo({ url: `/pages/device/device?id=${e.currentTarget.dataset.id}` })
  },
})
