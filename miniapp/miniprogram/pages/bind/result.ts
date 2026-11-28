import { api, isApiError } from '../../api/index'
import type { DeviceCard, DeviceDetail } from '../../api/types'
import { ConnectWatcher, type ConnectState } from '../../bind/connect'
import { NAME_MAX, settingsProblem } from '../../bind/flow'
import { HOME_PAGE } from '../../auth/flow'

/**
 * 绑定结果页（PRD 5.2⑤⑥、6.1 第 5 页）：绑定成功 → 等待联网 → 联网成功；设置设备名称、车位号。
 * 异常态：60 秒未联网（仍算绑定成功，可先离开）。
 */
Page({
  data: {
    id: 0,
    sn: '',
    connection: 'waiting' as ConnectState['connection'],
    name: '',
    spaceNo: '',
    nameMax: NAME_MAX,
    saving: false,
    notice: '',
  },
  watcher: undefined as ConnectWatcher | undefined,

  onLoad(query: Record<string, string | undefined>) {
    const id = Number(query.id)
    const at = Number(query.at)
    if (!Number.isInteger(id) || id <= 0) {
      wx.switchTab({ url: HOME_PAGE })
      return
    }
    this.setData({ id })
    this.watcher = new ConnectWatcher({
      api,
      deviceId: id,
      boundAt: Number.isFinite(at) && at > 0 ? at : Date.now(),
      onChange: (s) => {
        if (s.left) {
          wx.showToast({ title: s.left, icon: 'none' })
          setTimeout(() => wx.switchTab({ url: HOME_PAGE }), 1500)
          return
        }
        this.setData({ connection: s.connection })
      },
    })
    api
      .get<DeviceDetail>(`/devices/${id}`)
      .then((d) => this.setData({ sn: d.sn, name: d.name ?? '', spaceNo: d.spaceNo ?? '' }))
      .catch(() => undefined)
  },

  onShow() {
    void this.watcher?.start()
  },

  onHide() {
    this.watcher?.stop()
  },

  onUnload() {
    this.watcher?.stop()
  },

  onNameInput(e: WechatMiniprogram.Input) {
    this.setData({ name: e.detail.value, notice: '' })
  },

  onSpaceInput(e: WechatMiniprogram.Input) {
    this.setData({ spaceNo: e.detail.value, notice: '' })
  },

  /** 保存名称与车位号（都可留空，之后在设备设置里改），然后进入控制页 */
  async onDone() {
    if (this.data.saving) return
    const problem = settingsProblem(this.data.name, this.data.spaceNo)
    if (problem) {
      this.setData({ notice: problem })
      return
    }
    const name = this.data.name.trim()
    const spaceNo = this.data.spaceNo.trim()
    this.setData({ saving: true })
    try {
      if (name || spaceNo) await api.patch<DeviceCard>(`/devices/${this.data.id}`, { name, spaceNo })
      wx.redirectTo({ url: `/pages/device/device?id=${this.data.id}` })
    } catch (e) {
      this.setData({ notice: isApiError(e) ? e.message : '保存失败，请重试' })
    } finally {
      this.setData({ saving: false })
    }
  },

  /** 未联网时允许先离开（PRD 5.2⑥） */
  onLeave() {
    wx.switchTab({ url: HOME_PAGE })
  },
})
