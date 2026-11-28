import { api } from '../../api/index'

/** 账号已停用（10010，PRD 5.8②）：所有接口都会拒绝，只保留联系客服与退出登录 */
Page({
  data: { leaving: false },
  async onLogout() {
    this.setData({ leaving: true })
    await api.logout()
    wx.showToast({ title: '已退出', icon: 'none' })
    this.setData({ leaving: false })
  },
})
