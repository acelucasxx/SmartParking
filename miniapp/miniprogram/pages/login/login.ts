import { api, isApiError } from '../../api/index'
import { AGREED_KEY, agreedCurrent, bindPhone, navigation, phoneResult, redirectTarget } from '../../auth/flow'
import { AGREEMENT_VERSION } from '../../content/agreements'

/**
 * 登录授权页（PRD 6.1 第 1 页）：静默登录 → 阅读并同意协议 → 授权手机号 → 回到来源页面。
 * 异常态：登录失败 / 网络异常（可重试）、拒绝授权（可再次授权或暂不授权）、获取手机号失败。
 */
type Phase = 'logging-in' | 'login-failed' | 'ready' | 'binding'

Page({
  data: {
    phase: 'logging-in' as Phase,
    agreed: false,
    /** 页面上的提示：登录失败原因、拒绝授权说明、获取手机号失败原因 */
    notice: '',
    redirect: '',
  },

  onLoad(query: Record<string, string | undefined>) {
    this.setData({ redirect: redirectTarget(query.redirect), agreed: agreedCurrent(wx.getStorageSync(AGREED_KEY)) })
    this.login()
  },

  async login() {
    this.setData({ phase: 'logging-in', notice: '' })
    try {
      const session = await api.ensureLogin()
      if (session.user.phoneBound) {
        this.done()
        return
      }
      this.setData({ phase: 'ready' })
    } catch (e) {
      // 账号停用由全局 onBanned 跳转到停用提示页
      if (isApiError(e) && e.action === 'banned') return
      this.setData({ phase: 'login-failed', notice: isApiError(e) ? e.message : '登录失败，请重试' })
    }
  },

  onToggleAgree() {
    const agreed = !this.data.agreed
    this.setData({ agreed, notice: '' })
    if (agreed) wx.setStorageSync(AGREED_KEY, AGREEMENT_VERSION)
    else wx.removeStorageSync(AGREED_KEY)
  },

  onNeedAgree() {
    this.setData({ notice: '请先阅读并同意《用户协议》和《隐私政策》' })
  },

  onOpenAgreement(e: WechatMiniprogram.BaseEvent) {
    const type = e.currentTarget.dataset.type as string
    wx.navigateTo({ url: `/pages/agreement/agreement?type=${type}` })
  },

  async onGetPhoneNumber(e: WechatMiniprogram.ButtonGetPhoneNumber) {
    const result = phoneResult(e.detail)
    if (result.kind === 'denied') {
      this.setData({ notice: '需要手机号才能绑定和管理车位，你可以稍后再授权' })
      return
    }
    if (result.kind === 'failed') {
      this.setData({ notice: result.message })
      return
    }
    this.setData({ phase: 'binding', notice: '' })
    try {
      await bindPhone(api, result.code)
      wx.showToast({ title: '授权成功', icon: 'success' })
      this.done()
    } catch (err) {
      if (isApiError(err) && err.action === 'banned') return
      // 动态 code 只能用一次：失败后请用户重新点按钮
      this.setData({ phase: 'ready', notice: isApiError(err) ? err.message : '获取手机号失败，请重试' })
    }
  },

  onSkip() {
    const pages = getCurrentPages()
    if (pages.length > 1) wx.navigateBack()
    else wx.switchTab({ url: '/pages/index/index' })
  },

  done() {
    const nav = navigation(this.data.redirect)
    if (nav.method === 'switchTab') wx.switchTab({ url: nav.url })
    else wx.redirectTo({ url: nav.url })
  },
})
