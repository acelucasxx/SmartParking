import { api } from '../../api/index'
import { HOME_PAGE } from '../../auth/flow'
import { DeviceController, type ControlState } from '../../device/controller'
import { viewModel } from '../../device/view'
import type { GateAction } from '../../api/types'

/**
 * 设备控制页（PRD 6.1 第 3 页）：闸杆状态、在线状态、车位有无车、电量、信号、最近一次操作，大按钮抬杆 / 落杆。
 * 异常态：离线、休眠、故障、执行中、超时、遇阻、已停用。逻辑在 device/controller.ts，这里只渲染与跳转。
 */
Page({
  data: { vm: null as ReturnType<typeof viewModel>, loadError: '', loading: true },
  controller: undefined as DeviceController | undefined,
  shown: false,

  onLoad(query: Record<string, string | undefined>) {
    const id = Number(query.id)
    if (!Number.isInteger(id) || id <= 0) {
      this.leave('设备不存在')
      return
    }
    this.controller = new DeviceController({ api, deviceId: id, onChange: (s) => this.render(s) })
  },

  onShow() {
    void this.controller?.start()
    // 从设备设置返回：名称、车位号可能改了
    if (this.shown) void this.controller?.refreshDetail()
    this.shown = true
  },

  onHide() {
    this.controller?.stop()
  },

  onUnload() {
    this.controller?.stop()
  },

  onPullDownRefresh() {
    void this.controller?.start().finally(() => wx.stopPullDownRefresh())
  },

  render(state: ControlState) {
    if (state.left) {
      this.leave(state.left)
      return
    }
    const vm = viewModel(state, Date.now())
    this.setData({ vm, loadError: state.loadError ?? '', loading: !vm && !state.loadError })
    if (vm) wx.setNavigationBarTitle({ title: vm.title })
  },

  onRetry() {
    void this.controller?.retry()
  },

  onControl(e: WechatMiniprogram.BaseEvent) {
    const action = e.currentTarget.dataset.action as GateAction
    wx.vibrateShort({ type: 'light' })
    void this.controller?.control(action)
  },

  onBle() {
    // 蓝牙控制页在 MP-12（依赖固件 EXT-04 / EXT-05）
    wx.showToast({ title: '蓝牙控制即将上线', icon: 'none' })
  },

  onMembers() {
    const id = this.data.vm?.id
    if (id) wx.navigateTo({ url: `/pages/members/members?id=${id}` })
  },

  onTransfer() {
    const id = this.data.vm?.id
    if (id) wx.navigateTo({ url: `/pages/transfer/start?id=${id}` })
  },

  onSettings() {
    const id = this.data.vm?.id
    if (id) wx.navigateTo({ url: `/pages/device/settings?id=${id}` })
  },

  onRecords() {
    const id = this.data.vm?.id
    if (id) wx.navigateTo({ url: `/pages/device/records?id=${id}` })
  },

  leave(message: string) {
    wx.showToast({ title: message, icon: 'none' })
    setTimeout(() => wx.switchTab({ url: HOME_PAGE }), 1500)
  },
})
