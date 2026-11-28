import { api, isApiError } from '../../api/index'
import {
  type CustomRange,
  type MemberOverview,
  PERIOD_OPTIONS,
  type PeriodChoice,
  REMARK_MAX,
  type ShareCard,
  buildPeriod,
  choiceForMember,
  defaultRange,
  memberFailure,
  pickerParts,
  remarkProblem,
  shareMessage,
} from '../../members/period'

/**
 * 邀请家人（PRD 5.5、6.1 第 7 页）：选择有效期（永久 / 1 / 7 / 30 天 / 自定义起止）、填备注、生成分享卡片。
 * 带 memberId 时为修改该成员的有效期与备注（立即生效）。
 * 异常态：自定义时间早于当前（前端拦下，服务端 30011 兜底）、名额已满（30010）。
 */
Page({
  data: {
    deviceId: 0,
    memberId: 0,
    /** 修改时显示成员昵称 */
    memberName: '',
    loading: false,
    options: PERIOD_OPTIONS,
    choice: 'DAY_7' as PeriodChoice,
    range: defaultRange(Date.now()) as CustomRange,
    /** 日期选择器下限：今天 */
    today: pickerParts(Date.now()).date,
    remark: '',
    remarkMax: REMARK_MAX,
    submitting: false,
    notice: '',
    /** 生成的分享卡片：token 只出现这一次，离开本页后只能撤回重发 */
    card: null as ShareCard | null,
  },

  onLoad(query: Record<string, string | undefined>) {
    const deviceId = Number(query.id)
    const memberId = Number(query.memberId ?? 0)
    this.setData({ deviceId, memberId: Number.isInteger(memberId) && memberId > 0 ? memberId : 0 })
    if (this.data.memberId) {
      wx.setNavigationBarTitle({ title: '修改有效期' })
      void this.loadMember()
    }
  },

  async loadMember() {
    this.setData({ loading: true })
    try {
      const o = await api.get<MemberOverview>(`/devices/${this.data.deviceId}/members`)
      const m = o.members.find((x) => x.id === this.data.memberId)
      if (!m) {
        this.back('该授权已结束')
        return
      }
      const init = choiceForMember(m, Date.now())
      this.setData({ loading: false, memberName: m.nickname, remark: m.remark ?? '', choice: init.choice, range: init.range })
    } catch (e) {
      this.back(isApiError(e) ? e.message : '加载失败')
    }
  },

  onChoose(e: WechatMiniprogram.BaseEvent) {
    this.setData({ choice: e.currentTarget.dataset.value as PeriodChoice, notice: '' })
  },

  onPick(e: WechatMiniprogram.PickerChange) {
    const field = (e.currentTarget.dataset as { field: keyof CustomRange }).field
    this.setData({ [`range.${field}`]: String(e.detail.value), notice: '' })
  },

  onRemark(e: WechatMiniprogram.Input) {
    this.setData({ remark: e.detail.value, notice: '' })
  },

  async onSubmit() {
    if (this.data.submitting) return
    const problem = remarkProblem(this.data.remark)
    if (problem) {
      this.setData({ notice: problem })
      return
    }
    const built = buildPeriod(this.data.choice, this.data.range, Date.now())
    if (!built.ok) {
      this.setData({ notice: built.message })
      return
    }
    const remark = this.data.remark.trim()
    this.setData({ submitting: true, notice: '' })
    try {
      if (this.data.memberId) {
        // 修改：空备注表示清空
        await api.patch(`/devices/${this.data.deviceId}/members/${this.data.memberId}`, { period: built.period, remark })
        wx.showToast({ title: '已修改', icon: 'success' })
        setTimeout(() => wx.navigateBack(), 800)
      } else {
        const card = await api.post<ShareCard>(`/devices/${this.data.deviceId}/invites`, remark ? { period: built.period, remark } : { period: built.period })
        this.setData({ card })
      }
    } catch (e) {
      if (!isApiError(e)) {
        this.setData({ notice: '提交失败，请重试' })
        return
      }
      if (e.action === 'banned') return
      const how = memberFailure(e.code)
      if (how === 'show') this.setData({ notice: e.message })
      else this.back(e.message)
    } finally {
      this.setData({ submitting: false })
    }
  },

  /** 分享卡片（open-type="share" 的按钮与右上角菜单都走这里） */
  onShareAppMessage() {
    if (this.data.card) return shareMessage(this.data.card)
    // 还没生成卡片时不分享邀请，只分享小程序首页
    return { title: '智能车位', path: '/pages/index/index' }
  },

  onDone() {
    wx.navigateBack()
  },

  back(message: string) {
    wx.showToast({ title: message, icon: 'none' })
    setTimeout(() => wx.navigateBack(), 1500)
  },
})
