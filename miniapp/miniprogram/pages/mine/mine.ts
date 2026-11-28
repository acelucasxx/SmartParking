import { api, isApiError } from '../../api/index'
import { loginUrl } from '../../auth/flow'

/** 我的：目前只有账号与手机号授权入口；个人信息、帮助与客服在 MP-11 */
Page({
  data: { nickname: '', phone: '', phoneBound: false, error: '' },
  onShow() {
    this.load()
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
})
