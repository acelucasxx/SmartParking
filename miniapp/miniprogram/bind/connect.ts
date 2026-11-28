import type { ApiClient } from '../api/client'
import { isApiError } from '../api/errors'
import type { DeviceStatus } from '../api/types'
import type { Timers } from '../device/controller'
import { CONNECT_POLL_MS, CONNECT_SLOW_POLL_MS, type Connection, connection } from './flow'

/**
 * 绑定结果页的“等待设备联网”（PRD 5.2⑤⑥）：绑定后每 2 秒查一次状态，收到状态帧即“联网成功”；
 * 60 秒仍未联网提示检查电池与安装位置（仍算绑定成功，可先离开），之后每 5 秒继续查，联网了照样转为成功。
 */
export interface ConnectState {
  connection: Connection
  status: DeviceStatus | null
  /** 设备已不可用（如被解绑），页面提示后回首页 */
  left: string | null
}

export interface ConnectOptions {
  api: Pick<ApiClient, 'get'>
  deviceId: number
  /** 绑定成功的时刻（手机时钟） */
  boundAt: number
  onChange: (state: ConnectState) => void
  now?: () => number
  timers?: Timers
}

const defaultTimers: Timers = { set: (fn, ms) => setTimeout(fn, ms), clear: (h) => clearTimeout(h as number) }

export class ConnectWatcher {
  private readonly now: () => number
  private readonly timers: Timers
  private state: ConnectState = { connection: 'waiting', status: null, left: null }
  private timer: unknown = null
  private loop = 0

  constructor(private readonly options: ConnectOptions) {
    this.now = options.now ?? Date.now
    this.timers = options.timers ?? defaultTimers
  }

  get snapshot(): ConnectState {
    return this.state
  }

  /** onShow：立即查一次 */
  start(): Promise<void> {
    if (this.state.connection === 'connected' || this.state.left) return Promise.resolve()
    this.cancel()
    return this.tick(++this.loop)
  }

  /** onHide / onUnload */
  stop(): void {
    this.loop++
    this.cancel()
  }

  private async tick(loop: number): Promise<void> {
    try {
      const status = await this.options.api.get<DeviceStatus>(`/devices/${this.options.deviceId}/status`)
      this.emit({ status, connection: connection(status, this.options.boundAt, this.now()) })
    } catch (e) {
      if (isApiError(e) && (e.action === 'leave' || e.action === 'banned')) {
        // 不再续约；离开后 start() 也不再查
        if (e.action === 'leave') this.emit({ left: e.message })
        return
      }
      // 网络异常：照常按时间判定是否超时，下一轮再查
      this.emit({ connection: connection(this.state.status, this.options.boundAt, this.now()) })
    }
    if (loop !== this.loop || this.state.connection === 'connected') return
    const delay = this.state.connection === 'waiting' ? CONNECT_POLL_MS : CONNECT_SLOW_POLL_MS
    this.timer = this.timers.set(() => {
      this.timer = null
      void this.tick(loop)
    }, delay)
  }

  private cancel(): void {
    if (this.timer !== null) this.timers.clear(this.timer)
    this.timer = null
  }

  private emit(patch: Partial<ConnectState>): void {
    this.state = { ...this.state, ...patch }
    this.options.onChange(this.state)
  }
}
