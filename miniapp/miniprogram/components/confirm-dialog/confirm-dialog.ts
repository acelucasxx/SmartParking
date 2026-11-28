/**
 * 确认弹窗，用于解绑、转让、撤销等不可撤回的操作（wx.showModal 不能做倒计时和危险样式）。
 *
 * - danger：确认按钮为红色
 * - countdown：确认按钮需等待的秒数，防止误点（如解绑 3 秒）
 * - 事件：confirm、cancel；点遮罩等同取消。是否关闭由页面通过 show 控制
 */
Component({
  properties: {
    show: { type: Boolean, value: false },
    title: { type: String, value: '' },
    content: { type: String, value: '' },
    confirmText: { type: String, value: '确定' },
    cancelText: { type: String, value: '取消' },
    danger: { type: Boolean, value: false },
    countdown: { type: Number, value: 0 },
    loading: { type: Boolean, value: false },
  },
  data: { remaining: 0 },
  observers: {
    show(show: boolean) {
      this.clearTimer()
      if (show && this.data.countdown > 0) this.startCountdown(this.data.countdown)
      else this.setData({ remaining: 0 })
    },
  },
  lifetimes: {
    detached() {
      this.clearTimer()
    },
  },
  methods: {
    startCountdown(seconds: number) {
      this.setData({ remaining: seconds })
      const timer = setInterval(() => {
        const next = this.data.remaining - 1
        this.setData({ remaining: Math.max(0, next) })
        if (next <= 0) this.clearTimer()
      }, 1000)
      ;(this as unknown as { timer?: number }).timer = timer
    },
    clearTimer() {
      const self = this as unknown as { timer?: number }
      if (self.timer) clearInterval(self.timer)
      self.timer = undefined
    },
    onConfirm() {
      if (this.data.remaining > 0 || this.data.loading) return
      this.triggerEvent('confirm')
    },
    onCancel() {
      if (this.data.loading) return
      this.triggerEvent('cancel')
    },
    noop() {},
  },
})
