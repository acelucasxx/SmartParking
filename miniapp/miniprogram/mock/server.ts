import type { HttpRequest, HttpResponse } from '../api/client'
import { ErrorCode } from '../api/errors'
import type {
  Command,
  DeviceCard,
  DeviceDetail,
  DeviceStatus,
  GateAction,
  GateStatus,
  Message,
  OperationRecord,
  Page,
  UserProfile,
} from '../api/types'
import { MockError, MockRouter } from './router'

/**
 * 模拟后端：内存里的几台设备、指令、消息，覆盖首页、控制页、消息中心需要的接口与常见异常态。
 * 指令按时间推进：下发后 0.5 秒“已下发”，2 秒内“执行中”（闸杆动作中），2 秒后到位成功。
 * 其余接口在对应页面任务里补。
 */

/** 指令从下发到到位的模拟耗时 */
export const MOCK_SENT_MS = 500
export const MOCK_DONE_MS = 2000

interface MockDevice extends DeviceDetail {
  signalLevel: number | null
}

interface MockCommand {
  command: Command
  issuedMs: number
  userId: number
}

const DAY = 86_400_000

export interface MockServer {
  handle(request: HttpRequest): HttpResponse
  router: MockRouter
}

/** @param now 毫秒时间戳来源，测试里可控 */
export function createMockServer(now: () => number = Date.now): MockServer {
  // 未授权手机号：可以走一遍登录授权页（MP-02）
  const user: UserProfile = { id: 1, nickname: '张先生', phoneBound: false, phoneMasked: null }
  const iso = (ms: number) => toIso(ms)

  const devices: MockDevice[] = [
    device({ id: 1, sn: 'SP2612000123', name: '家里车位', spaceNo: 'B2-108', role: 'OWNER', batteryPct: 86 }),
    device({
      id: 2,
      sn: 'SP2612000456',
      name: '公司车位',
      spaceNo: 'A1-016',
      role: 'MEMBER',
      validTo: iso(now() + 7 * DAY),
      onlineState: 'SLEEP',
      gateStatus: 'OPENED',
      occupied: true,
      batteryPct: 15,
      ownerNickname: '李女士',
    }),
    device({ id: 3, sn: 'SP2612000789', name: null, role: 'OWNER', onlineState: 'OFFLINE', batteryPct: null }),
    device({ id: 4, sn: 'SP2612000999', name: '老小区车位', role: 'MEMBER', enabled: false, ownerNickname: '王先生' }),
  ]

  const commands: MockCommand[] = []
  /** 指令 ID → 幂等键：连点只产生一条指令 */
  const idempotency = new Map<number, string>()
  let nextCommandId = 1001
  const messages: Message[] = [
    message(1, 'GRANT', 2, '你已获得「公司车位」的使用权限', '主人 李女士 授权你使用该车位，有效期 7 天。', false, now() - 3_600_000),
    message(2, 'FAULT', 1, '「家里车位」电机故障', '设备报告电机故障，已通知运维处理。', true, now() - 2 * DAY),
  ]

  function device(partial: Partial<MockDevice> & Pick<MockDevice, 'id' | 'sn' | 'role'>): MockDevice {
    return {
      name: null,
      spaceNo: null,
      validTo: null,
      enabled: true,
      onlineState: 'ONLINE',
      gateStatus: 'CLOSED',
      occupied: false,
      batteryPct: null,
      pendingCommandId: null,
      model: 'SPG-100',
      fwVersion: '1.0.3',
      signalLevel: 24,
      statusRefreshedAt: iso(now() - 60_000),
      lastSeenAt: iso(now() - 30_000),
      ownerNickname: '张先生',
      bleAvailable: false,
      lastOperation: null,
      ...partial,
    }
  }

  function find(id: string): MockDevice {
    const d = devices.find((x) => String(x.id) === id)
    if (!d) throw new MockError(ErrorCode.NOT_FOUND, '设备不存在', 404)
    return d
  }

  /** 按时间推进指令状态，到位时更新设备 */
  function advance(c: MockCommand): Command {
    const cmd = c.command
    if (cmd.status === 'SUCCESS' || cmd.status === 'FAILED' || cmd.status === 'TIMEOUT') return cmd
    const elapsed = now() - c.issuedMs
    const d = devices.find((x) => x.id === cmd.deviceId)
    if (elapsed >= MOCK_DONE_MS) {
      const target: GateStatus = cmd.action === 'OPEN' ? 'OPENED' : 'CLOSED'
      cmd.status = 'SUCCESS'
      cmd.gateStatus = target
      cmd.finishedAt = iso(c.issuedMs + MOCK_DONE_MS)
      cmd.durationMs = MOCK_DONE_MS
      if (d) {
        d.gateStatus = target
        d.pendingCommandId = null
        d.lastOperation = record(c)
      }
    } else if (elapsed >= MOCK_SENT_MS) {
      cmd.status = 'EXECUTING'
      cmd.gateStatus = cmd.action === 'OPEN' ? 'OPENING' : 'CLOSING'
      if (d) d.gateStatus = cmd.gateStatus
    } else {
      cmd.status = 'SENT'
    }
    return cmd
  }

  function record(c: MockCommand): OperationRecord {
    const cmd = c.command
    return {
      id: `c-${cmd.id}`,
      at: cmd.issuedAt,
      approx: false,
      source: 'APP',
      action: cmd.action,
      operatorName: user.nickname,
      operatorRole: devices.find((d) => d.id === cmd.deviceId)?.role ?? null,
      channel: 'REMOTE',
      result: cmd.status === 'SUCCESS' ? 'SUCCESS' : cmd.status === 'FAILED' ? 'FAILED' : cmd.status === 'TIMEOUT' ? 'TIMEOUT' : null,
      resultCode: cmd.resultCode ?? null,
      durationMs: cmd.durationMs ?? null,
    }
  }

  function settleAll(): void {
    commands.forEach(advance)
  }

  function card(d: MockDevice): DeviceCard {
    const { id, sn, name, spaceNo, role, validTo, enabled, onlineState, gateStatus, occupied, batteryPct, pendingCommandId } = d
    return { id, sn, name, spaceNo, role, validTo, enabled, onlineState, gateStatus, occupied, batteryPct, pendingCommandId }
  }

  function status(d: MockDevice): DeviceStatus {
    const { id, enabled, onlineState, gateStatus, occupied, batteryPct, signalLevel, statusRefreshedAt, lastSeenAt, pendingCommandId } = d
    return { id, enabled, onlineState, gateStatus, occupied, batteryPct, signalLevel, statusRefreshedAt, lastSeenAt, pendingCommandId }
  }

  const router = new MockRouter()
    .on(
      'POST',
      '/auth/login',
      (ctx) => {
        if (ctx.data.code === 'fail') throw new MockError(ErrorCode.WX_LOGIN_FAILED, undefined, 502)
        return { token: `mock-token-${now()}`, expiresAt: iso(now() + 7 * DAY), user: { ...user } }
      },
      { auth: false },
    )
    .on('POST', '/auth/logout', () => null)
    .on('POST', '/auth/phone', (ctx) => {
      // 与服务端 mock 模式一致：1 开头的 11 位数字即该号码，fail 模拟失败，其余为 13800138000
      const code = String(ctx.data.code ?? '')
      if (!code) throw new MockError(ErrorCode.BAD_REQUEST)
      if (code === 'fail') throw new MockError(ErrorCode.WX_PHONE_FAILED, undefined, 502)
      const phone = /^1\d{10}$/.test(code) ? code : '13800138000'
      user.phoneBound = true
      user.phoneMasked = `${phone.slice(0, 3)}****${phone.slice(-4)}`
      return { ...user }
    })
    .on('GET', '/me', () => ({ ...user }))
    .on('GET', '/devices', () => {
      settleAll()
      return devices.map(card)
    })
    .on('GET', '/devices/:id', (ctx) => {
      settleAll()
      return find(ctx.params.id)
    })
    .on('GET', '/devices/:id/status', (ctx) => {
      settleAll()
      return status(find(ctx.params.id))
    })
    .on('PATCH', '/devices/:id', (ctx) => {
      const d = find(ctx.params.id)
      if (d.role !== 'OWNER') throw new MockError(ErrorCode.NOT_OWNER, undefined, 403)
      if (typeof ctx.data.name === 'string') d.name = ctx.data.name.trim() || null
      if (typeof ctx.data.spaceNo === 'string') d.spaceNo = ctx.data.spaceNo.trim() || null
      return card(d)
    })
    .on('POST', '/devices/:id/commands', (ctx) => {
      settleAll()
      const d = find(ctx.params.id)
      const action = ctx.data.action as GateAction
      const key = ctx.data.idempotencyKey
      if ((action !== 'OPEN' && action !== 'CLOSE') || typeof key !== 'string' || key.length < 8) {
        throw new MockError(ErrorCode.BAD_REQUEST)
      }
      const same = commands.find((c) => c.command.deviceId === d.id && idempotency.get(c.command.id) === key)
      if (same) return advance(same)
      if (!d.enabled) throw new MockError(ErrorCode.DEVICE_DISABLED, undefined, 403)
      if (d.gateStatus === 'FAULT') throw new MockError(ErrorCode.DEVICE_FAULT, undefined, 409)
      if (d.onlineState === 'OFFLINE') throw new MockError(ErrorCode.DEVICE_OFFLINE, undefined, 409)
      if (d.onlineState === 'SLEEP') throw new MockError(ErrorCode.DEVICE_SLEEPING, undefined, 409)
      if (d.pendingCommandId) throw new MockError(ErrorCode.COMMAND_IN_PROGRESS, undefined, 409)
      const issuedMs = now()
      const command: Command = {
        id: nextCommandId++,
        deviceId: d.id,
        action,
        channel: 'REMOTE',
        status: 'PENDING',
        gateStatus: d.gateStatus,
        resultCode: null,
        resultMessage: null,
        issuedAt: iso(issuedMs),
        finishedAt: null,
        durationMs: null,
      }
      const target: GateStatus = action === 'OPEN' ? 'OPENED' : 'CLOSED'
      if (d.gateStatus === target) {
        // 已在目标状态：不下发，直接成功
        Object.assign(command, { status: 'SUCCESS', resultCode: 'ALREADY_IN_TARGET', finishedAt: command.issuedAt, durationMs: 0 })
      } else {
        d.pendingCommandId = command.id
      }
      commands.push({ command, issuedMs, userId: user.id })
      idempotency.set(command.id, key)
      return command
    })
    .on('GET', '/devices/:id/commands/:commandId', (ctx) => {
      const d = find(ctx.params.id)
      const c = commands.find((x) => x.command.deviceId === d.id && String(x.command.id) === ctx.params.commandId)
      if (!c) throw new MockError(ErrorCode.NOT_FOUND, '指令不存在', 404)
      return advance(c)
    })
    .on('GET', '/devices/:id/records', (ctx): Page<OperationRecord> => {
      settleAll()
      const d = find(ctx.params.id)
      const all = commands.filter((c) => c.command.deviceId === d.id).reverse().map(record)
      return page(all, ctx.query)
    })
    .on('GET', '/messages', (ctx): Page<Message> => page([...messages].sort((a, b) => b.id - a.id), ctx.query))
    .on('GET', '/messages/unread-count', () => ({ count: messages.filter((m) => !m.read).length }))
    .on('POST', '/messages/read', (ctx) => {
      const ids = Array.isArray(ctx.data.ids) ? (ctx.data.ids as number[]) : null
      for (const m of messages) {
        if (ctx.data.all === true || (ids && ids.includes(m.id))) m.read = true
      }
      return null
    })

  return { handle: (request) => router.handle(request), router }
}

function message(
  id: number,
  type: Message['type'],
  deviceId: number | null,
  title: string,
  content: string,
  read: boolean,
  createdMs: number,
): Message {
  return { id, type, deviceId, title, content, read, createdAt: toIso(createdMs) }
}

function page<T>(all: T[], query: Record<string, string>): Page<T> {
  const p = Math.max(1, Number(query.page) || 1)
  const size = Math.min(100, Math.max(1, Number(query.size) || 20))
  return { items: all.slice((p - 1) * size, p * size), page: p, size, total: all.length }
}

/** 东八区 ISO 8601，与服务端一致，如 2026-12-31T18:00:00+08:00 */
export function toIso(ms: number): string {
  const shifted = new Date(ms + 8 * 3_600_000)
  const pad = (n: number, w = 2) => String(n).padStart(w, '0')
  return (
    `${shifted.getUTCFullYear()}-${pad(shifted.getUTCMonth() + 1)}-${pad(shifted.getUTCDate())}` +
    `T${pad(shifted.getUTCHours())}:${pad(shifted.getUTCMinutes())}:${pad(shifted.getUTCSeconds())}+08:00`
  )
}
