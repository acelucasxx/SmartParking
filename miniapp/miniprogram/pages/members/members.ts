import { api, isApiError } from '../../api/index'
import { HOME_PAGE } from '../../auth/flow'
import {
  type MemberOverview,
  memberValidity,
  periodText,
  quota,
  shortDateTime,
} from '../../members/period'

/**
 * 成员管理（PRD 6.1 第 6 页，仅主人）：被授权人列表（备注、剩余有效期、状态）、已用 / 上限名额、邀请中的邀请。
 * 异常态：名额已满（邀请按钮置灰并提示）。修改有效期与邀请在 pages/members/invite。
 */
interface MemberRow {
  id: number
  nickname: string
  remark: string
  validity: string
  pending: boolean
}

interface InviteRow {
  id: number
  remark: string
  period: string
  expire: string
}

Page({
  data: {
    deviceId: 0,
    loading: true,
    error: '',
    quotaText: '',
    full: false,
    tip: '',
    members: [] as MemberRow[],
    invites: [] as InviteRow[],
    /** 确认弹窗：撤销成员或撤回邀请 */
    confirm: { show: false, kind: '' as '' | 'member' | 'invite', id: 0, title: '', content: '', confirmText: '' },
    busy: false,
  },

  onLoad(query: Record<string, string | undefined>) {
    const id = Number(query.id)
    if (!Number.isInteger(id) || id <= 0) {
      wx.switchTab({ url: HOME_PAGE })
      return
    }
    this.setData({ deviceId: id })
  },

  onShow() {
    // 从邀请页、修改页回来时刷新
    if (this.data.deviceId) void this.load()
  },

  onPullDownRefresh() {
    void this.load().finally(() => wx.stopPullDownRefresh())
  },

  async load() {
    try {
      const o = await api.get<MemberOverview>(`/devices/${this.data.deviceId}/members`)
      const now = Date.now()
      const q = quota(o)
      this.setData({
        loading: false,
        error: '',
        quotaText: q.text,
        full: q.full,
        tip: q.tip,
        members: o.members.map((m) => ({
          id: m.id,
          nickname: m.nickname,
          remark: m.remark ?? '',
          validity: memberValidity(m, now),
          pending: Date.parse(m.validFrom) > now,
        })),
        invites: o.invites.map((i) => ({
          id: i.id,
          remark: i.remark ?? '',
          period: periodText(i.period, now),
          expire: `${shortDateTime(i.expireAt, now)} 前有效`,
        })),
      })
    } catch (e) {
      if (isApiError(e) && (e.action === 'leave' || e.code === 30004)) {
        wx.showToast({ title: e.message, icon: 'none' })
        setTimeout(() => wx.navigateBack({ fail: () => wx.switchTab({ url: HOME_PAGE }) }), 1500)
        return
      }
      this.setData({ loading: false, error: isApiError(e) ? e.message : '加载失败' })
    }
  },

  onInvite() {
    if (this.data.full) {
      wx.showToast({ title: '名额已满', icon: 'none' })
      return
    }
    wx.navigateTo({ url: `/pages/members/invite?id=${this.data.deviceId}` })
  },

  onEdit(e: WechatMiniprogram.BaseEvent) {
    const memberId = e.currentTarget.dataset.id as number
    wx.navigateTo({ url: `/pages/members/invite?id=${this.data.deviceId}&memberId=${memberId}` })
  },

  onRevoke(e: WechatMiniprogram.BaseEvent) {
    const { id, name } = e.currentTarget.dataset as { id: number; name: string }
    this.setData({
      confirm: {
        show: true,
        kind: 'member',
        id,
        title: '撤销授权',
        content: `撤销后「${name}」立即无法使用该车位，并会收到通知。`,
        confirmText: '撤销',
      },
    })
  },

  onCancelInvite(e: WechatMiniprogram.BaseEvent) {
    const id = e.currentTarget.dataset.id as number
    this.setData({
      confirm: { show: true, kind: 'invite', id, title: '撤回邀请', content: '撤回后对方打开卡片会提示邀请已撤销，名额立即释放。', confirmText: '撤回' },
    })
  },

  onConfirmCancel() {
    if (!this.data.busy) this.setData({ 'confirm.show': false })
  },

  async onConfirm() {
    const { kind, id } = this.data.confirm
    this.setData({ busy: true })
    try {
      const path = kind === 'member' ? `/devices/${this.data.deviceId}/members/${id}` : `/devices/${this.data.deviceId}/invites/${id}`
      await api.delete(path)
      wx.showToast({ title: kind === 'member' ? '已撤销' : '已撤回', icon: 'success' })
    } catch (e) {
      // 已结束、已失效（10004）：刷新即可
      if (isApiError(e) && e.code !== 10004) wx.showToast({ title: e.message, icon: 'none' })
    } finally {
      this.setData({ busy: false, 'confirm.show': false })
      void this.load()
    }
  },
})
