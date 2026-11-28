import { api, isApiError } from '../../api/index'
import type { DeviceCard } from '../../api/types'
import { loginUrl, needPhone } from '../../auth/flow'
import {
  type BindPreview,
  type BindView,
  CODE_LENGTH,
  RESULT_PAGE,
  VIEW_TEXT,
  bindFailure,
  bindPageUrl,
  codeComplete,
  normalizeCode,
  previewView,
  snFromLaunch,
} from '../../bind/flow'
import { scanSn } from '../../bind/scan'

/**
 * 扫码绑定页（PRD 5.2、6.1 第 4 页）：显示 SN、输入 8 位激活码。
 * 异常态：码错误（带剩余次数）、连续输错锁定、已被他人绑定、SN 不存在；另有已停用、本人已绑定（直接进控制页）。
 *
 * 入口：微信扫机身二维码直接打开（?q=），或首页“添加车位”扫码后跳转（?sn=）。
 */
type Phase = 'loading' | 'invalid' | 'error' | BindView

Page({
  data: {
    phase: 'loading' as Phase,
    sn: '',
    model: '',
    code: '',
    codeLength: CODE_LENGTH,
    submitting: false,
    /** 激活码错误、网络异常等就地提示 */
    notice: '',
    /** 错误次数过多：输入框禁用 */
    locked: false,
    title: '',
    desc: '',
  },

  onLoad(query: Record<string, string | undefined>) {
    const sn = snFromLaunch(query)
    if (!sn) {
      this.setData({ phase: 'invalid' })
      return
    }
    this.setData({ sn })
    this.load()
  },

  async load() {
    this.setData({ phase: 'loading', notice: '' })
    try {
      const session = await api.ensureLogin()
      const preview = await api.get<BindPreview>(`/bind/${this.data.sn}`)
      const view = previewView(preview)
      if (view === 'mine') {
        wx.showToast({ title: '你已绑定该设备', icon: 'none' })
        wx.redirectTo({ url: `/pages/device/device?id=${preview.deviceId}` })
        return
      }
      // 绑定前必须授权手机号（PRD 5.2②）：替换本页去授权，授权后回到本页，避免页面栈里出现两个绑定页
      if (view === 'input' && needPhone(session.user) === 'need-phone') {
        wx.redirectTo({ url: loginUrl(bindPageUrl(this.data.sn)) })
        return
      }
      this.show(view, preview.model)
    } catch (e) {
      if (isApiError(e) && e.action === 'banned') return
      if (isApiError(e) && e.action === 'leave') {
        this.show('not-found', '')
        return
      }
      this.setData({ phase: 'error', notice: isApiError(e) ? e.message : '加载失败，请重试' })
    }
  },

  show(view: BindView, model: string) {
    const text = view === 'input' || view === 'mine' ? { title: '', desc: '' } : VIEW_TEXT[view]
    this.setData({ phase: view, model, title: text.title, desc: text.desc })
  },

  onCodeInput(e: WechatMiniprogram.Input) {
    const code = normalizeCode(e.detail.value)
    this.setData({ code, notice: '' })
    // 返回值即输入框显示的值：转大写、去掉空格等
    return code
  },

  async onSubmit() {
    if (this.data.submitting || this.data.locked) return
    if (!codeComplete(this.data.code)) {
      this.setData({ notice: `请输入 ${CODE_LENGTH} 位激活码` })
      return
    }
    this.setData({ submitting: true, notice: '' })
    try {
      const card = await api.post<DeviceCard>('/bind', { sn: this.data.sn, activateCode: this.data.code })
      wx.redirectTo({ url: `${RESULT_PAGE}?id=${card.id}&at=${Date.now()}` })
    } catch (e) {
      if (!isApiError(e)) {
        this.setData({ notice: '绑定失败，请重试' })
        return
      }
      if (e.action === 'banned') return
      const failure = bindFailure(e)
      if (failure.kind === 'view') this.show(failure.view, this.data.model)
      else if (failure.kind === 'locked') this.setData({ locked: true, notice: failure.message, code: '' })
      else if (failure.kind === 'need-phone') wx.redirectTo({ url: loginUrl(bindPageUrl(this.data.sn)) })
      else this.setData({ notice: failure.message, code: e.code === 30001 ? '' : this.data.code })
    } finally {
      this.setData({ submitting: false })
    }
  },

  async onScanAgain() {
    const sn = await scanSn()
    if (sn) wx.redirectTo({ url: bindPageUrl(sn) })
  },

  onHome() {
    wx.switchTab({ url: '/pages/index/index' })
  },
})
