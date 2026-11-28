import { api } from '../../api/index'
import type { GateAction } from '../../api/types'
import { requirePhone } from '../../auth/index'
import { HOME_PAGE } from '../../auth/flow'
import { bindPageUrl } from '../../bind/flow'
import { scanSn } from '../../bind/scan'
import { HomeController, type HomeState } from '../../home/controller'
import { cardViewModel } from '../../home/view'

/**
 * 车位首页（PRD 6.1 第 2 页）：我的车位列表（主人的在前、被授权的在后），卡片带快捷抬杆 / 落杆与“我的 / 家人共享”标签。
 * 异常态：空态引导扫码、列表加载失败。逻辑在 home/，这里只渲染与跳转。
 */
Page({
  data: {
    cards: [] as ReturnType<typeof cardViewModel>[],
    loading: true,
    loadError: '',
    stale: false,
    empty: false,
  },
  controller: undefined as HomeController | undefined,

  onLoad() {
    this.controller = new HomeController({ api, onChange: (s) => this.render(s) })
  },

  onShow() {
    void this.controller?.start()
  },

  onHide() {
    this.controller?.stop()
  },

  onPullDownRefresh() {
    void this.controller?.start().finally(() => wx.stopPullDownRefresh())
  },

  render(state: HomeState) {
    const now = Date.now()
    const cards = (state.cards ?? []).map((c) => cardViewModel(c, now))
    this.setData({
      cards,
      loading: state.loading && !state.cards,
      loadError: state.cards ? '' : state.loadError ?? '',
      stale: state.stale,
      empty: !!state.cards && state.cards.length === 0,
    })
    if (state.toast) {
      wx.showToast({ title: state.toast, icon: 'none' })
      this.controller?.clearToast()
    }
  },

  onRetry() {
    void this.controller?.start()
  },

  onOpen(e: WechatMiniprogram.BaseEvent) {
    wx.navigateTo({ url: `/pages/device/device?id=${e.currentTarget.dataset.id}` })
  },

  onControl(e: WechatMiniprogram.BaseEvent) {
    const { id, action } = e.currentTarget.dataset as { id: number; action: GateAction }
    wx.vibrateShort({ type: 'light' })
    void this.controller?.control(Number(id), action)
  },

  noop() {},

  /** 添加车位：绑定前必须授权手机号（PRD 5.2），授权后回到首页再点一次扫码 */
  async onAdd() {
    if (!(await requirePhone(HOME_PAGE))) return
    const sn = await scanSn()
    if (sn) wx.navigateTo({ url: bindPageUrl(sn) })
  },
})
