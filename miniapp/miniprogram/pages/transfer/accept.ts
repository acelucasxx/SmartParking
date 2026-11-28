import { api, isApiError } from '../../api/index'
import type { DeviceCard } from '../../api/types'
import { HOME_PAGE } from '../../auth/flow'
import { tokenValid } from '../../invite/accept'
import { shortDateTime } from '../../members/period'
import { ACCEPT_NOTES, INVALID, type Invalid, NOT_FOUND, type TransferPreview, acceptFailure } from '../../transfer/transfer'

/**
 * 接收设备（PRD 5.6③、6.1 第 9 页）：设备名称、SN、原主人昵称；确认后成为新主人。
 * 异常态：卡片已过期、已取消；另有已被接收、自己发起的、卡片不存在。从分享卡片打开，离开用 switchTab。
 */
type Phase = 'loading' | 'error' | 'valid' | 'invalid' | 'accepted'

Page({
  data: {
    phase: 'loading' as Phase,
    token: '',
    deviceName: '',
    sn: '',
    fromNickname: '',
    expire: '',
    notes: ACCEPT_NOTES,
    invalid: null as Invalid | null,
    notice: '',
    confirmShow: false,
    accepting: false,
    deviceId: 0,
  },

  onLoad(query: Record<string, string | undefined>) {
    if (!tokenValid(query.token)) {
      this.setData({ phase: 'invalid', invalid: NOT_FOUND })
      return
    }
    this.setData({ token: query.token })
    void this.load()
  },

  async load() {
    this.setData({ phase: 'loading', notice: '' })
    try {
      const p = await api.get<TransferPreview>(`/transfers/${this.data.token}`)
      this.setData({
        deviceName: p.deviceName,
        sn: p.sn,
        fromNickname: p.fromNickname,
        expire: `${shortDateTime(p.expireAt, Date.now())} 前有效`,
      })
      if (p.state === 'VALID') this.setData({ phase: 'valid' })
      else this.setData({ phase: 'invalid', invalid: INVALID[p.state] })
    } catch (e) {
      if (isApiError(e) && e.action === 'banned') return
      if (isApiError(e) && e.code === 10004) {
        this.setData({ phase: 'invalid', invalid: NOT_FOUND })
        return
      }
      this.setData({ phase: 'error', notice: isApiError(e) ? e.message : '加载失败，请重试' })
    }
  },

  onAccept() {
    this.setData({ confirmShow: true, notice: '' })
  },

  onConfirmCancel() {
    if (!this.data.accepting) this.setData({ confirmShow: false })
  },

  async onConfirm() {
    if (this.data.accepting) return
    this.setData({ accepting: true })
    try {
      const card = await api.post<DeviceCard>(`/transfers/${this.data.token}/accept`)
      this.setData({ phase: 'accepted', deviceId: card.id })
    } catch (e) {
      if (!isApiError(e)) {
        this.setData({ notice: '接收失败，请重试' })
        return
      }
      if (e.action === 'banned') return
      const f = acceptFailure(e.code, e.message)
      if (f.kind === 'state') this.setData({ phase: 'invalid', invalid: INVALID[f.state] })
      else if (f.kind === 'invalid') this.setData({ phase: 'invalid', invalid: f.invalid })
      else this.setData({ notice: f.message })
    } finally {
      this.setData({ accepting: false, confirmShow: false })
    }
  },

  onUse() {
    const id = this.data.deviceId
    wx.switchTab({
      url: HOME_PAGE,
      success: () => {
        if (id) wx.navigateTo({ url: `/pages/device/device?id=${id}` })
      },
    })
  },

  onHome() {
    wx.switchTab({ url: HOME_PAGE })
  },
})
