import { api, onBanned } from './api/index'

App<IAppOption>({
  globalData: { banned: false },
  onLaunch() {
    // 启动即静默登录；失败不打断启动，页面发请求时会再次尝试。手机号授权、协议页在 MP-02 实现
    api.ensureLogin().catch(() => undefined)
    onBanned(() => {
      this.globalData.banned = true
      // 账号停用提示页在 MP-02 实现，这里先给出提示
      wx.showModal({ title: '账号已停用', content: '如有疑问请联系客服。', showCancel: false })
    })
  },
})
