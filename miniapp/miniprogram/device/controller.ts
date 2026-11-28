import type { ApiClient } from '../api/client'
import { ApiError, ErrorCode, isApiError } from '../api/errors'
import type { Command, DeviceDetail, DeviceStatus, GateAction } from '../api/types'
import { idempotencyKey } from '../utils/format'
import { type CommandPhase, commandPhase, nextDelay, type PrimaryButton, primaryButton, rejection } from './control'

/**
 * 控制页的轮询编排：进入页面加载详情，之后每 5 秒刷新状态；点击后每 1 秒轮询指令，到终态后刷新一次详情
 * （闸杆状态、最近一次操作）。同一时间只有一条轮询链，页面隐藏时停止。
 *
 * 依赖都可注入，测试里用 mock 服务端加可控时钟驱动（test/device.test.cjs）。
 */

export interface Timers {
  set(fn: () => void, ms: number): unknown
  clear(handle: unknown): void
}

export interface ControlState {
  device: DeviceDetail | null
  /** 本页发起（或进入时发现）的指令的阶段；终态会一直显示到下一次点击 */
  phase: CommandPhase | null
  button: PrimaryButton | null
  /** 点击被拒等一次性提示 */
  notice: string | null
  /** 最近一次刷新失败：继续显示旧数据，并提示可能不是最新 */
  stale: boolean
  /** 首次加载失败的原因，页面显示重试 */
  loadError: string | null
  /** 设备或授权已不可用（不存在、授权到期 / 撤销），页面提示后返回首页 */
  left: string | null
  /** 提交中：防止连点 */
  submitting: boolean
}

export interface ControllerOptions {
  api: Pick<ApiClient, 'get' | 'post'>
  deviceId: number
  onChange: (state: ControlState) => void
  now?: () => number
  timers?: Timers
  newKey?: () => string
}

const defaultTimers: Timers = { set: (fn, ms) => setTimeout(fn, ms), clear: (h) => clearTimeout(h as number) }

export class DeviceController {
  private readonly now: () => number
  private readonly timers: Timers
  private readonly newKey: () => string
  private state: ControlState = {
    device: null,
    phase: null,
    button: null,
    notice: null,
    stale: false,
    loadError: null,
    left: null,
    submitting: false,
  }
  /** 正在跟踪的指令与开始等待的时刻 */
  private tracked: { command: Command; since: number } | null = null
  /** 看不到的未终结指令（被授权人看不到别人的指令）：只按设备状态等它结束 */
  private readonly hidden = new Set<number>()
  private timer: unknown = null
  /** 每次重启或停止轮询链加一，旧链上在途请求返回后不再续约 */
  private loop = 0

  constructor(private readonly options: ControllerOptions) {
    this.now = options.now ?? Date.now
    this.timers = options.timers ?? defaultTimers
    this.newKey = options.newKey ?? (() => idempotencyKey())
  }

  get snapshot(): ControlState {
    return this.state
  }

  private get base(): string {
    return `/devices/${this.options.deviceId}`
  }

  /** onShow：首次进入加载详情，之后回到页面立即刷新 */
  start(): Promise<void> {
    if (this.state.left) return Promise.resolve()
    return this.restart(0)
  }

  /** onHide / onUnload */
  stop(): void {
    this.loop++
    this.cancelTimer()
  }

  /** 首次加载失败后的重试 */
  retry(): Promise<void> {
    this.update({ loadError: null })
    return this.start()
  }

  async control(action: GateAction): Promise<void> {
    const s = this.state
    // 提交中、执行中按钮都不是 control，连点在这里挡掉
    if (!s.device || s.button?.kind !== 'control') return
    this.cancelTimer()
    const loop = ++this.loop
    const since = this.now()
    const pending = { action, status: 'PENDING' } as Command
    this.tracked = null
    this.update({ submitting: true, notice: null, phase: commandPhase(pending, 0) })
    const body = { action, idempotencyKey: this.newKey() }
    try {
      let command: Command
      try {
        command = await this.options.api.post<Command>(`${this.base}/commands`, body)
      } catch (e) {
        // 网络抖动：同一幂等键重试一次，不会产生第二条指令
        if (!isApiError(e) || e.code !== ErrorCode.NETWORK) throw e
        command = await this.options.api.post<Command>(`${this.base}/commands`, body)
      }
      this.tracked = { command, since }
      const phase = commandPhase(command, this.now() - since)
      this.update({ submitting: false, phase })
      if (phase.done) await this.reloadDetail()
    } catch (e) {
      await this.rejected(e)
    }
    // 提交期间页面被隐藏（loop 已变）就不再续约
    if (loop === this.loop && !this.state.left) this.schedule(loop, nextDelay(this.state.phase, this.state.device?.pendingCommandId))
  }

  // ------------------------------------------------------------ 轮询

  private async restart(delay: number): Promise<void> {
    this.cancelTimer()
    const loop = ++this.loop
    if (delay > 0) {
      this.schedule(loop, delay)
      return
    }
    await this.tick(loop)
  }

  private schedule(loop: number, ms: number): void {
    this.cancelTimer()
    this.timer = this.timers.set(() => {
      this.timer = null
      void this.tick(loop)
    }, ms)
  }

  private cancelTimer(): void {
    if (this.timer !== null) this.timers.clear(this.timer)
    this.timer = null
  }

  private async tick(loop: number): Promise<void> {
    try {
      if (!this.state.device) await this.loadDetail()
      else if (this.tracked && !this.state.phase?.done) await this.pollCommand()
      else await this.refreshStatus()
      if (this.state.stale) this.update({ stale: false })
    } catch (e) {
      if (!this.handleError(e)) return
    }
    if (loop !== this.loop || this.state.left || !this.state.device) return
    this.schedule(loop, nextDelay(this.state.phase, this.state.device.pendingCommandId))
  }

  private async loadDetail(): Promise<void> {
    let device: DeviceDetail
    try {
      device = await this.options.api.get<DeviceDetail>(this.base)
    } catch (e) {
      if (isApiError(e) && (e.action === 'retry' || e.action === 'show')) {
        this.update({ loadError: e.message })
        return
      }
      throw e
    }
    this.update({ device, loadError: null })
    await this.adoptPending()
  }

  private async reloadDetail(): Promise<void> {
    const device = await this.options.api.get<DeviceDetail>(this.base)
    this.update({ device })
  }

  private async pollCommand(): Promise<void> {
    const t = this.tracked!
    let command: Command
    try {
      command = await this.options.api.get<Command>(`${this.base}/commands/${t.command.id}`)
    } catch (e) {
      // 指令看不到了（不应发生）：不当作设备不可用，改为按设备状态等待
      if (isApiError(e) && e.code === ErrorCode.NOT_FOUND) {
        this.hidden.add(t.command.id)
        this.tracked = null
        this.update({ phase: null })
        await this.refreshStatus()
        return
      }
      throw e
    }
    this.tracked = { command, since: t.since }
    const phase = commandPhase(command, this.now() - t.since)
    this.update({ phase })
    if (phase.done) await this.reloadDetail()
  }

  private async refreshStatus(): Promise<void> {
    const status = await this.options.api.get<DeviceStatus>(`${this.base}/status`)
    const before = this.state.device!
    this.update({ device: { ...before, ...status } })
    // 别人的指令刚结束：刷新详情，拿到最近一次操作
    if (before.pendingCommandId && !status.pendingCommandId) await this.reloadDetail()
    else await this.adoptPending()
  }

  /** 设备上有未终结的指令（离开页面前自己点的，或家人点的）：能看到就接着跟踪 */
  private async adoptPending(): Promise<void> {
    const id = this.state.device?.pendingCommandId
    if (!id || this.hidden.has(id) || this.tracked?.command.id === id) return
    try {
      const command = await this.options.api.get<Command>(`${this.base}/commands/${id}`)
      this.tracked = { command, since: this.now() }
      this.update({ phase: commandPhase(command, 0), notice: null })
    } catch (e) {
      if (isApiError(e) && e.code === ErrorCode.NOT_FOUND) this.hidden.add(id)
      else throw e
    }
  }

  // ------------------------------------------------------------ 错误

  private async rejected(e: unknown): Promise<void> {
    const err = isApiError(e) ? e : new ApiError(ErrorCode.NETWORK)
    this.tracked = null
    this.update({ submitting: false, phase: null })
    if (err.action === 'banned') {
      this.stop()
      return
    }
    const how = rejection(err.code)
    if (how === 'leave') {
      this.leave(err.message)
      return
    }
    this.update({ notice: err.message })
    if (how === 'refresh') {
      try {
        await this.refreshStatus()
      } catch (refreshError) {
        this.handleError(refreshError)
      }
    }
  }

  /** @returns 是否继续轮询 */
  private handleError(e: unknown): boolean {
    if (!isApiError(e)) {
      this.update({ stale: true })
      return true
    }
    if (e.action === 'banned') {
      this.stop()
      return false
    }
    if (e.action === 'leave') {
      this.leave(e.message)
      return false
    }
    // 停用、故障时状态接口仍可用，这里只剩网络与服务异常：保留旧数据，下一轮再试
    this.update({ stale: true })
    return true
  }

  private leave(message: string): void {
    this.stop()
    this.update({ left: message })
  }

  private update(patch: Partial<ControlState>): void {
    const next = { ...this.state, ...patch }
    next.button = next.device ? primaryButton(next.device, next.submitting ? { ...pendingPhase, done: false } : next.phase) : null
    this.state = next
    this.options.onChange(next)
  }
}

const pendingPhase: CommandPhase = { kind: 'sending', done: false, text: '', tone: 'info', animation: null }
