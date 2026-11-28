import type { ApiClient } from '../api/client'
import { ApiError, ErrorCode, isApiError } from '../api/errors'
import type { Command, DeviceCard, GateAction } from '../api/types'
import { type CommandPhase, commandPhase, POLL_MS, primaryButton, rejection } from '../device/control'
import { idempotencyKey } from '../utils/format'
import type { Timers } from '../device/controller'

/**
 * 车位首页（MP-03，PRD 6.1 第 2 页）的编排：进入页面加载“我的车位”，卡片上快捷抬杆 / 落杆。
 *
 * 首页不定时刷新（每次 onShow 与下拉时刷新）；只有卡片上有指令在执行时，每 1 秒轮询该指令，
 * 结束后刷新一次列表。按钮、指令阶段、文案与控制页共用 device/control.ts。
 */

export interface CardState {
  card: DeviceCard
  /** 在首页发起（或进入时发现）的指令阶段；终态显示到下一次进入页面 */
  phase: CommandPhase | null
  /** 点击被拒的提示 */
  notice: string | null
  submitting: boolean
}

export interface HomeState {
  /** null：还没加载成功过 */
  cards: CardState[] | null
  loading: boolean
  /** 首次加载失败的原因，页面显示重试 */
  loadError: string | null
  /** 已有列表时刷新失败：保留旧列表并提示 */
  stale: boolean
  /** 一次性提示（如授权已到期，卡片随之消失），页面弹 toast 后调用 clearToast */
  toast: string | null
}

export interface HomeOptions {
  api: Pick<ApiClient, 'get' | 'post'>
  onChange: (state: HomeState) => void
  now?: () => number
  timers?: Timers
  newKey?: () => string
}

const defaultTimers: Timers = { set: (fn, ms) => setTimeout(fn, ms), clear: (h) => clearTimeout(h as number) }

export class HomeController {
  private readonly now: () => number
  private readonly timers: Timers
  private readonly newKey: () => string
  private state: HomeState = { cards: null, loading: false, loadError: null, stale: false, toast: null }
  /** 设备 ID → 正在跟踪的指令 */
  private readonly tracked = new Map<number, { command: Command; since: number }>()
  /** 看不到的未终结指令（家人发起的）：只按列表里的 pendingCommandId 等它结束 */
  private readonly hidden = new Set<number>()
  private timer: unknown = null
  private loop = 0

  constructor(private readonly options: HomeOptions) {
    this.now = options.now ?? Date.now
    this.timers = options.timers ?? defaultTimers
    this.newKey = options.newKey ?? (() => idempotencyKey())
  }

  get snapshot(): HomeState {
    return this.state
  }

  /** onShow / 下拉刷新：上次的结果提示清掉，执行中的继续跟踪 */
  async start(): Promise<void> {
    const loop = this.restart()
    for (const id of [...this.tracked.keys()]) {
      const t = this.tracked.get(id)!
      if (commandPhase(t.command, this.now() - t.since).done) this.tracked.delete(id)
    }
    this.patchCards((c) => ({ ...c, notice: null, phase: this.tracked.has(c.card.id) ? c.phase : null }))
    await this.reload(loop, true)
  }

  stop(): void {
    this.loop++
    this.cancelTimer()
  }

  clearToast(): void {
    if (this.state.toast) this.emit({ toast: null })
  }

  async control(deviceId: number, action: GateAction): Promise<void> {
    const cs = this.find(deviceId)
    // 提交中、执行中按钮都不是 control，连点在这里挡掉
    if (!cs || primaryButton(view(cs.card), cs.phase).kind !== 'control') return
    const loop = this.restart()
    const since = this.now()
    this.tracked.delete(deviceId)
    this.patchCard(deviceId, { submitting: true, notice: null, phase: commandPhase({ action, status: 'PENDING' } as Command, 0) })
    const body = { action, idempotencyKey: this.newKey() }
    const path = `/devices/${deviceId}/commands`
    try {
      let command: Command
      try {
        command = await this.options.api.post<Command>(path, body)
      } catch (e) {
        // 网络抖动：同一幂等键重试一次
        if (!isApiError(e) || e.code !== ErrorCode.NETWORK) throw e
        command = await this.options.api.post<Command>(path, body)
      }
      this.tracked.set(deviceId, { command, since })
      const phase = commandPhase(command, this.now() - since)
      this.patchCard(deviceId, { submitting: false, phase })
      if (phase.done) await this.reload(loop, false)
    } catch (e) {
      await this.rejected(loop, deviceId, e)
    }
    if (loop === this.loop) this.scheduleIfNeeded(loop)
  }

  // ------------------------------------------------------------ 加载与轮询

  /** 开一条新的轮询链，旧链上的在途请求返回后不再续约 */
  private restart(): number {
    this.cancelTimer()
    return ++this.loop
  }

  private async reload(loop: number, showLoading: boolean): Promise<void> {
    if (showLoading && !this.state.cards) this.emit({ loading: true, loadError: null })
    try {
      const cards = await this.options.api.get<DeviceCard[]>('/devices')
      const before = new Map((this.state.cards ?? []).map((c) => [c.card.id, c]))
      // 已不在列表里的设备（授权到期、被撤销、已解绑）不再跟踪
      const ids = new Set(cards.map((c) => c.id))
      for (const id of [...this.tracked.keys()]) if (!ids.has(id)) this.tracked.delete(id)
      this.emit({
        cards: cards.map((card) => {
          const old = before.get(card.id)
          return { card, phase: old?.phase ?? null, notice: old?.notice ?? null, submitting: old?.submitting ?? false }
        }),
        loading: false,
        loadError: null,
        stale: false,
      })
      await this.adoptPending()
    } catch (e) {
      if (isApiError(e) && e.action === 'banned') {
        this.stop()
        return
      }
      const message = isApiError(e) ? e.message : new ApiError(ErrorCode.NETWORK).message
      if (this.state.cards) this.emit({ loading: false, stale: true })
      else this.emit({ loading: false, loadError: message })
    }
    if (loop === this.loop) this.scheduleIfNeeded(loop)
  }

  /** 卡片上有未终结的指令：能看到就跟踪 */
  private async adoptPending(): Promise<void> {
    for (const cs of this.state.cards ?? []) {
      const id = cs.card.pendingCommandId
      if (!id || this.hidden.has(id) || this.tracked.get(cs.card.id)?.command.id === id) continue
      try {
        const command = await this.options.api.get<Command>(`/devices/${cs.card.id}/commands/${id}`)
        this.tracked.set(cs.card.id, { command, since: this.now() })
        this.patchCard(cs.card.id, { phase: commandPhase(command, 0) })
      } catch (e) {
        if (isApiError(e) && e.code === ErrorCode.NOT_FOUND) this.hidden.add(id)
        else throw e
      }
    }
  }

  /** 有在执行的指令（自己的或看不到的）才 1 秒后再查，否则不定时刷新 */
  private scheduleIfNeeded(loop: number): void {
    if (!this.state.cards) return
    const busy = this.state.cards.some((c) => (c.phase && !c.phase.done) || c.card.pendingCommandId)
    if (!busy) return
    this.cancelTimer()
    this.timer = this.timers.set(() => {
      this.timer = null
      void this.tick(loop)
    }, POLL_MS)
  }

  private async tick(loop: number): Promise<void> {
    let finished = false
    let failed = false
    for (const [deviceId, t] of [...this.tracked]) {
      // 卡片上已是终态的不再查
      if (this.find(deviceId)?.phase?.done) continue
      // 等满上限：不再查，标为“暂未收到结果”
      const waited = commandPhase(t.command, this.now() - t.since)
      if (waited.done) {
        this.patchCard(deviceId, { phase: waited })
        finished = true
        continue
      }
      try {
        const command = await this.options.api.get<Command>(`/devices/${deviceId}/commands/${t.command.id}`)
        this.tracked.set(deviceId, { command, since: t.since })
        const phase = commandPhase(command, this.now() - t.since)
        this.patchCard(deviceId, { phase })
        if (phase.done) finished = true
      } catch (e) {
        if (isApiError(e) && e.action === 'banned') {
          this.stop()
          return
        }
        // 指令或设备看不到了（授权到期等）：交给列表刷新处理
        if (isApiError(e) && e.action === 'leave') {
          this.tracked.delete(deviceId)
          this.patchCard(deviceId, { phase: null })
          finished = true
        } else failed = true
      }
    }
    if (loop !== this.loop) return
    if (failed) this.emit({ stale: true })
    // 指令结束，或设备上的指令不在跟踪中（家人的、或等满上限仍未终结）：刷新列表拿最新状态
    const waitingList = (this.state.cards ?? []).some((c) => c.card.pendingCommandId && !(c.phase && !c.phase.done))
    if (finished || waitingList) await this.reload(loop, false)
    else this.scheduleIfNeeded(loop)
  }

  private cancelTimer(): void {
    if (this.timer !== null) this.timers.clear(this.timer)
    this.timer = null
  }

  // ------------------------------------------------------------ 错误

  private async rejected(loop: number, deviceId: number, e: unknown): Promise<void> {
    const err = isApiError(e) ? e : new ApiError(ErrorCode.NETWORK)
    this.patchCard(deviceId, { submitting: false, phase: null })
    if (err.action === 'banned') {
      this.stop()
      return
    }
    const how = rejection(err.code)
    if (how === 'leave') {
      // 授权到期、被撤销：提示后刷新列表，卡片随之消失
      this.emit({ toast: err.message })
      await this.reload(loop, false)
      return
    }
    this.patchCard(deviceId, { notice: err.message })
    if (how === 'refresh') await this.reload(loop, false)
  }

  // ------------------------------------------------------------ 状态

  private find(deviceId: number): CardState | undefined {
    return this.state.cards?.find((c) => c.card.id === deviceId)
  }

  private patchCard(deviceId: number, patch: Partial<CardState>): void {
    this.patchCards((c) => (c.card.id === deviceId ? { ...c, ...patch } : c))
  }

  private patchCards(fn: (c: CardState) => CardState): void {
    if (this.state.cards) this.emit({ cards: this.state.cards.map(fn) })
  }

  private emit(patch: Partial<HomeState>): void {
    this.state = { ...this.state, ...patch }
    this.options.onChange(this.state)
  }
}

/** 卡片没有 bleAvailable：首页不放蓝牙入口，离线、休眠只显示原因，蓝牙在控制页 */
export function view(card: DeviceCard) {
  return {
    enabled: card.enabled,
    onlineState: card.onlineState,
    gateStatus: card.gateStatus,
    bleAvailable: false,
    pendingCommandId: card.pendingCommandId ?? null,
  }
}
