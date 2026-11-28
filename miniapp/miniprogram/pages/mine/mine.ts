import { api, isApiError } from '../../api/index'
import { loginUrl } from '../../auth/flow'
import { refreshBadge } from '../../messages/badge'

/** 显示的版本号，与 package.json 一致；发布时一并修改 */
const VERSION = '0.1.0'

/**
 * 我的（PRD 6.1 第 13 页）：个人信息（昵称、手机号脱敏 / 授权手机号）、帮助、客服、协议与隐私政策、版本。
 */
Page({
  data: { nickname: '', phone: '', phoneBound: false, error: '', version: VERSION },

  onShow() {
    void this.load()
    void refreshBadge()
  },

  async load() {
    try {
      const session = await api.ensureLogin()
      const u = session.user
      this.setData({ nickname: u.nickname, phone: u.phoneMasked ?? '', phoneBound: u.phoneBound, error: '' })
    } catch (e) {
      this.setData({ error: isApiError(e) ? e.message : '加载失败' })
    }
  },

  onBindPhone() {
    wx.navigateTo({ url: loginUrl('/pages/mine/mine') })
  },

  onHelp() {
    wx.navigateTo({ url: '/pages/mine/help' })
  },

  onAgreement(e: WechatMiniprogram.BaseEvent) {
    wx.navigateTo({ url: `/pages/agreement/agreement?type=${e.currentTarget.dataset.type}` })
  },
})
