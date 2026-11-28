import { api, isApiError } from '../../api/index'
import type { DeviceCard } from '../../api/types'
import { HOME_PAGE } from '../../auth/flow'
import {
  INVALID,
  type Invalid,
  type InvitePreview,
  NOT_FOUND,
  acceptFailure,
  afterAccept,
  previewPeriod,
  tokenValid,
} from '../../invite/accept'
import { shortDateTime } from '../../members/period'

/**
 * 接受邀请页（PRD 5.5④、6.1 第 8 页）：设备名称、主人昵称、有效期、接受按钮。
 * 异常态：邀请已过期、已被他人接受、已撤销、自己已是成员；另有自己发出的邀请、邀请不存在。
 * 从分享卡片打开，通常是小程序的第一个页面：离开用 switchTab 回首页。
 */
type Phase = 'loading' | 'error' | 'valid' | 'invalid' | 'accepted'

Page({
  data: {
    phase: 'loading' as Phase,
    token: '',
    deviceName: '',
    ownerNickname: '',
    period: '',
    remark: '',
    expire: '',
    invalid: null as Invalid | null,
    notice: '',
    accepting: false,
    /** 接受成功 */
    acceptedText: '',
    acceptedDeviceId: 0,
  },
  preview: null as InvitePreview | null,

  onLoad(query: Record<string, string | undefined>) {
    const token = query.token
    if (!tokenValid(token)) {
      this.setData({ phase: 'invalid', invalid: NOT_FOUND })
      return
    }
    this.setData({ token })
    void this.load()
  },

  async load() {
    this.setData({ phase: 'loading', notice: '' })
    try {
      const preview = await api.get<InvitePreview>(`/invites/${this.data.token}`)
      this.preview = preview
      const now = Date.now()
      this.setData({
        deviceName: preview.deviceName,
        ownerNickname: preview.ownerNickname,
        period: previewPeriod(preview, now),
        remark: preview.remark ?? '',
        expire: `${shortDateTime(preview.expireAt, now)} 前有效`,
      })
      if (preview.state === 'VALID') this.setData({ phase: 'valid' })
      else this.setData({ phase: 'invalid', invalid: INVALID[preview.state] })
    } catch (e) {
      if (isApiError(e) && e.action === 'banned') return
      // token 不存在（10004）
      if (isApiError(e) && e.code === 10004) {
        this.setData({ phase: 'invalid', invalid: NOT_FOUND })
        return
      }
      this.setData({ phase: 'error', notice: isApiError(e) ? e.message : '加载失败，请重试' })
    }
  },

  async onAccept() {
    if (this.data.accepting || !this.preview) return
    this.setData({ accepting: true, notice: '' })
    try {
      const card = await api.post<DeviceCard>(`/invites/${this.data.token}/accept`)
      const after = afterAccept(this.preview.period, Date.now())
      this.setData({ phase: 'accepted', acceptedText: after.text, acceptedDeviceId: after.started ? card.id : 0 })
    } catch (e) {
      if (!isApiError(e)) {
        this.setData({ notice: '接受失败，请重试' })
        return
      }
      if (e.action === 'banned') return
      const f = acceptFailure(e.code, e.message)
      if (f.kind === 'state') this.setData({ phase: 'invalid', invalid: INVALID[f.state] })
      else if (f.kind === 'invalid') this.setData({ phase: 'invalid', invalid: f.invalid })
      else this.setData({ notice: f.message })
    } finally {
      this.setData({ accepting: false })
    }
  },

  onUse() {
    // 先回首页（重建页面栈），再进控制页
    const id = this.data.acceptedDeviceId
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
