import { api, onBanned } from './api/index'
import { BANNED_PAGE } from './auth/flow'

App<IAppOption>({
  globalData: { banned: false },
  onLaunch() {
    // 启动即静默登录；失败不打断启动，页面发请求时会再次尝试。手机号在需要时才授权（auth/index.ts requirePhone）
    api.ensureLogin().catch(() => undefined)
    onBanned(() => {
      this.globalData.banned = true
      wx.reLaunch({ url: BANNED_PAGE })
    })
  },
})
