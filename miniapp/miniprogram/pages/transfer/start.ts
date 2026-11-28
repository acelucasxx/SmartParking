import { api, isApiError } from '../../api/index'
import { HOME_PAGE } from '../../auth/flow'
import { type ShareCard, shareMessage, shortDateTime } from '../../members/period'
import { CONFIRM_COUNTDOWN, type PendingTransfer, RISKS, startFailure } from '../../transfer/transfer'

/**
 * 转让设备 · 发起（PRD 5.6、6.1 第 9 页，仅主人）：阅读风险提示 → 二次确认（倒计时）→ 生成转让卡片发给接收人；
 * 进行中的转让可取消。异常态：卡片已过期（自动视为没有进行中的转让，可重新发起）、已取消。
 */
type Phase = 'loading' | 'error' | 'intro' | 'pending' | 'card'

Page({
  data: {
    deviceId: 0,
    phase: 'loading' as Phase,
    error: '',
    risks: RISKS,
    agreed: false,
    notice: '',
    pendingExpire: '',
    card: null as ShareCard | null,
    confirm: { show: false, kind: '' as '' | 'start' | 'cancel', title: '', content: '', confirmText: '', countdown: 0 },
    busy: false,
  },

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
    this.setData({ phase: 'loading', notice: '' })
    try {
      const pending = await api.get<PendingTransfer | null>(`/devices/${this.data.deviceId}/transfer`)
      if (pending) this.setData({ phase: 'pending', pendingExpire: `${shortDateTime(pending.expireAt, Date.now())} 前有效` })
      else this.setData({ phase: 'intro', agreed: false })
    } catch (e) {
      if (isApiError(e) && (e.action === 'leave' || e.code === 30004)) {
        this.leave(e.message)
        return
      }
      this.setData({ phase: 'error', error: isApiError(e) ? e.message : '加载失败' })
    }
  },

  onToggleAgree() {
    this.setData({ agreed: !this.data.agreed, notice: '' })
  },

  onStart() {
    if (!this.data.agreed) {
      this.setData({ notice: '请先阅读并勾选“我已了解转让的影响”' })
      return
    }
    this.setData({
      confirm: {
        show: true,
        kind: 'start',
        title: '确认转让',
        content: '对方接收后你将失去这台设备的全部权限，现有家人也会被清空。确认生成转让卡片？',
        confirmText: '生成卡片',
        countdown: CONFIRM_COUNTDOWN,
      },
    })
  },

  onCancelTransfer() {
    this.setData({
      confirm: { show: true, kind: 'cancel', title: '取消转让', content: '取消后对方打开卡片会提示转让已取消。', confirmText: '取消转让', countdown: 0 },
    })
  },

  onDialogCancel() {
    if (!this.data.busy) this.setData({ 'confirm.show': false })
  },

  async onDialogConfirm() {
    const kind = this.data.confirm.kind
    this.setData({ busy: true })
    try {
      if (kind === 'start') {
        const card = await api.post<ShareCard>(`/devices/${this.data.deviceId}/transfer`)
        this.setData({ phase: 'card', card })
      } else {
        await api.delete(`/devices/${this.data.deviceId}/transfer`)
        wx.showToast({ title: '已取消转让', icon: 'success' })
        await this.load()
      }
    } catch (e) {
      if (!isApiError(e) || e.action === 'banned') return
      if (kind === 'cancel') {
        // 已过期或已被接收（10004）：重新加载看最新状态
        await this.load()
        return
      }
      const how = startFailure(e.code)
      if (how === 'reload') await this.load()
      else if (how === 'leave') this.leave(e.message)
      else this.setData({ notice: e.message })
    } finally {
      this.setData({ busy: false, 'confirm.show': false })
    }
  },

  onShareAppMessage() {
    if (this.data.card) return shareMessage(this.data.card)
    return { title: '智能车位', path: '/pages/index/index' }
  },

  onDone() {
    wx.navigateBack()
  },

  leave(message: string) {
    wx.showToast({ title: message, icon: 'none' })
    setTimeout(() => wx.switchTab({ url: HOME_PAGE }), 1500)
  },
})
