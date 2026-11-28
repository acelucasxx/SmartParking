import { api, isApiError } from '../../api/index'
import type { DeviceDetail } from '../../api/types'
import { HOME_PAGE } from '../../auth/flow'
import { QUIT_CONFIRM, TEXT_MAX, UNBIND_CONFIRM, devicePatch, leaveFailure } from '../../device/settings'
import { validity } from '../../utils/format'

/**
 * 设备设置（PRD 6.1 第 10 页）：
 * - 主人：名称、车位号（可改）、SN、型号、固件版本，解绑（二次确认）
 * - 被授权人：只看信息与“退出授权”
 */
Page({
  data: {
    deviceId: 0,
    loading: true,
    error: '',
    isOwner: false,
    sn: '',
    model: '',
    fwVersion: '',
    ownerNickname: '',
    validity: '',
    enabled: true,
    name: '',
    spaceNo: '',
    textMax: TEXT_MAX,
    saving: false,
    notice: '',
    confirm: { show: false, title: '', content: '', confirmText: '', countdown: 0 },
    busy: false,
  },
  original: { name: null as string | null | undefined, spaceNo: null as string | null | undefined },

  onLoad(query: Record<string, string | undefined>) {
    const id = Number(query.id)
    if (!Number.isInteger(id) || id <= 0) {
      wx.switchTab({ url: HOME_PAGE })
      return
    }
    this.setData({ deviceId: id })
    void this.load()
  },

  async load() {
    this.setData({ loading: true, error: '' })
    try {
      const d = await api.get<DeviceDetail>(`/devices/${this.data.deviceId}`)
      this.original = { name: d.name, spaceNo: d.spaceNo }
      this.setData({
        loading: false,
        isOwner: d.role === 'OWNER',
        sn: d.sn,
        model: d.model,
        fwVersion: d.fwVersion || '—',
        ownerNickname: d.ownerNickname ?? '',
        validity: validity(d.validTo, Date.now()),
        enabled: d.enabled,
        name: d.name ?? '',
        spaceNo: d.spaceNo ?? '',
      })
    } catch (e) {
      if (isApiError(e) && e.action === 'leave') {
        this.gone(e.message)
        return
      }
      this.setData({ loading: false, error: isApiError(e) ? e.message : '加载失败' })
    }
  },

  onNameInput(e: WechatMiniprogram.Input) {
    this.setData({ name: e.detail.value, notice: '' })
  },

  onSpaceInput(e: WechatMiniprogram.Input) {
    this.setData({ spaceNo: e.detail.value, notice: '' })
  },

  async onSave() {
    if (this.data.saving) return
    const built = devicePatch(this.original, this.data.name, this.data.spaceNo)
    if (!built.ok) {
      this.setData({ notice: built.message })
      return
    }
    if (!built.patch) {
      wx.showToast({ title: '没有修改', icon: 'none' })
      return
    }
    this.setData({ saving: true })
    try {
      await api.patch(`/devices/${this.data.deviceId}`, built.patch)
      this.original = { name: built.patch.name ?? this.original.name, spaceNo: 'spaceNo' in built.patch ? built.patch.spaceNo || null : this.original.spaceNo }
      wx.showToast({ title: '已保存', icon: 'success' })
    } catch (e) {
      if (isApiError(e) && leaveFailure(e.code) === 'gone') this.gone(e.message)
      else this.setData({ notice: isApiError(e) ? e.message : '保存失败，请重试' })
    } finally {
      this.setData({ saving: false })
    }
  },

  onUnbind() {
    this.setData({ confirm: { show: true, ...UNBIND_CONFIRM } })
  },

  onQuit() {
    this.setData({ confirm: { show: true, ...QUIT_CONFIRM } })
  },

  onConfirmCancel() {
    if (!this.data.busy) this.setData({ 'confirm.show': false })
  },

  async onConfirm() {
    this.setData({ busy: true })
    try {
      if (this.data.isOwner) await api.post(`/devices/${this.data.deviceId}/unbind`)
      else await api.delete(`/devices/${this.data.deviceId}/membership`)
      wx.showToast({ title: this.data.isOwner ? '已解绑' : '已退出', icon: 'success' })
      setTimeout(() => wx.switchTab({ url: HOME_PAGE }), 800)
    } catch (e) {
      if (isApiError(e) && leaveFailure(e.code) === 'gone') this.gone(e.message)
      else wx.showToast({ title: isApiError(e) ? e.message : '操作失败，请重试', icon: 'none' })
    } finally {
      this.setData({ busy: false, 'confirm.show': false })
    }
  },

  /** 已不是主人或授权已结束：回首页，列表会随之刷新 */
  gone(message: string) {
    wx.showToast({ title: message, icon: 'none' })
    setTimeout(() => wx.switchTab({ url: HOME_PAGE }), 1500)
  },
})
