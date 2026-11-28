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
 * 演示设备 5–7 用来看控制页的异常态：5 每次都遇阻，6 一直不到位直到超时，7 电机故障。
 * 扫码绑定：见 MOCK_BINDABLE（待激活的 SN 与激活码）；同一 SN 连续输错 5 次锁定 30 分钟。
 * 其余接口在对应页面任务里补。
 */

/** 指令从下发到到位的模拟耗时 */
export const MOCK_SENT_MS = 500
export const MOCK_DONE_MS = 2000
/** 与服务端一致：15 秒未到位判超时 */
export const MOCK_TIMEOUT_MS = 15_000

/**
 * 待激活、可绑定的设备（扫码绑定 MP-05）。connectMs 为绑定后多久“联网成功”，null 表示一直不联网（看 60 秒超时）。
 * 已有设备的 SN 预检为：主人的 → 本人已绑定；被授权的 → 已有主人；停用的 → 已停用。另有 SP2612000777 已被他人绑定。
 */
export const MOCK_BINDABLE: Record<string, { code: string; connectMs: number | null }> = {
  SP2612000888: { code: 'K7Q2M9XA', connectMs: 3000 },
  SP2612000666: { code: 'K7Q2M9XB', connectMs: null },
}
export const MOCK_TAKEN_SN = 'SP2612000777'
const BIND_MAX_FAILURES = 5
const BIND_LOCK_MS = 30 * 60_000

/** 演示设备的指令结局 */
type Outcome = 'OBSTRUCTED' | 'TIMEOUT'

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
    device({ id: 5, sn: 'SP2612000501', name: '演示：遇阻', role: 'OWNER', batteryPct: 60 }),
    device({ id: 6, sn: 'SP2612000602', name: '演示：超时', role: 'OWNER', batteryPct: 60 }),
    device({ id: 7, sn: 'SP2612000703', name: '演示：故障', role: 'OWNER', gateStatus: 'FAULT', batteryPct: 60 }),
  ]
  const outcomes = new Map<number, Outcome>([
    [5, 'OBSTRUCTED'],
    [6, 'TIMEOUT'],
  ])

  /** 已绑定的演示设备：设备 ID → 绑定时刻与联网耗时 */
  const bound = new Map<number, { at: number; connectMs: number | null }>()
  const bindFailures = new Map<string, { count: number; lockedUntil: number }>()
  let nextDeviceId = 101

  // 成员管理（MP-06）：设备 1 有两位成员（永久 / 尚未开始）和一张待接受邀请，名额上限 5
  interface MockMember { id: number; deviceId: number; nickname: string; remark: string | null; validFrom: number; validTo: number | null; status: 'ACTIVE' | 'REVOKED' | 'EXPIRED' | 'QUIT' }
  interface MockInvite { id: number; deviceId: number; remark: string | null; period: Record<string, unknown>; expireAt: number; createdAt: number; status: 'PENDING' | 'CANCELLED' }
  const MEMBER_LIMIT = 5
  const members: MockMember[] = [
    { id: 11, deviceId: 1, nickname: '张大爷', remark: '爸爸', validFrom: now() - 3 * DAY, validTo: null, status: 'ACTIVE' },
    { id: 12, deviceId: 1, nickname: '李阿姨', remark: '保洁', validFrom: now() + DAY, validTo: now() + 8 * DAY, status: 'ACTIVE' },
  ]
  const invites: MockInvite[] = [
    { id: 21, deviceId: 1, remark: '妈妈', period: { type: 'RELATIVE', durationDays: 7 }, expireAt: now() + 20 * 3_600_000, createdAt: now() - 4 * 3_600_000, status: 'PENDING' },
  ]
  let nextInviteId = 22

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
    const outcome = outcomes.get(cmd.deviceId)
    const finish = (status: 'FAILED' | 'TIMEOUT', resultCode: 'OBSTRUCTED' | 'TIMEOUT', message: string, at: number, gate: GateStatus) => {
      Object.assign(cmd, { status, resultCode, resultMessage: message, gateStatus: gate, finishedAt: iso(c.issuedMs + at), durationMs: at })
      if (d) {
        d.gateStatus = gate
        d.pendingCommandId = null
        d.lastOperation = record(c)
      }
    }
    if (outcome === 'OBSTRUCTED' && elapsed >= MOCK_DONE_MS) {
      finish('FAILED', 'OBSTRUCTED', '闸杆遇阻，请检查现场', MOCK_DONE_MS, 'STOPPED')
    } else if (outcome === 'TIMEOUT' && elapsed >= MOCK_TIMEOUT_MS) {
      finish('TIMEOUT', 'TIMEOUT', '设备长时间未到位，请查看现场', MOCK_TIMEOUT_MS, 'UNKNOWN')
    } else if (!outcome && elapsed >= MOCK_DONE_MS) {
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

  function ownerDevice(id: string): MockDevice {
    const d = find(id)
    if (d.role !== 'OWNER') throw new MockError(ErrorCode.NOT_OWNER, undefined, 403)
    return d
  }

  /** 生效中：ACTIVE 且未过 validTo（与服务端口径一致，到期那一刻起不再列出） */
  const liveMembers = (deviceId: number) => members.filter((m) => m.deviceId === deviceId && m.status === 'ACTIVE' && (m.validTo === null || m.validTo > now()))
  const liveInvites = (deviceId: number) => invites.filter((i) => i.deviceId === deviceId && i.status === 'PENDING' && i.expireAt > now())

  function memberView(m: MockMember) {
    return { id: m.id, nickname: m.nickname, remark: m.remark, validFrom: iso(m.validFrom), validTo: m.validTo === null ? null : iso(m.validTo), status: m.status }
  }

  /** 校验并换算有效期；不合法 30011。RELATIVE 从 start 起算 */
  function parsePeriod(raw: unknown, start: number): { validFrom: number; validTo: number | null; period: Record<string, unknown> } {
    const p = (raw ?? {}) as Record<string, unknown>
    const invalid = () => new MockError(ErrorCode.INVALID_PERIOD, undefined, 400)
    if (p.type === 'PERMANENT') return { validFrom: start, validTo: null, period: { type: 'PERMANENT' } }
    if (p.type === 'RELATIVE') {
      if (![1, 7, 30].includes(p.durationDays as number)) throw invalid()
      return { validFrom: start, validTo: start + (p.durationDays as number) * DAY, period: { type: 'RELATIVE', durationDays: p.durationDays } }
    }
    if (p.type === 'CUSTOM') {
      const minute = (v: unknown) => Math.floor(Date.parse(String(v)) / 60_000) * 60_000
      const from = minute(p.validFrom)
      const to = minute(p.validTo)
      if (Number.isNaN(from) || Number.isNaN(to) || to <= from || to <= now()) throw invalid()
      return { validFrom: from, validTo: to, period: { type: 'CUSTOM', validFrom: iso(from), validTo: iso(to) } }
    }
    throw invalid()
  }

  function remarkOf(raw: unknown): string | null {
    if (raw === undefined || raw === null) return null
    const r = String(raw).trim()
    if (r.length > 32) throw new MockError(ErrorCode.BAD_REQUEST, '备注最多 32 个字')
    return r || null
  }

  function settleAll(): void {
    commands.forEach(advance)
    // 绑定后到点“联网”：收到状态帧
    for (const [id, b] of bound) {
      if (b.connectMs === null || now() - b.at < b.connectMs) continue
      const d = devices.find((x) => x.id === id)
      if (d && !d.statusRefreshedAt) {
        Object.assign(d, {
          onlineState: 'ONLINE',
          gateStatus: 'CLOSED',
          occupied: false,
          batteryPct: 100,
          signalLevel: 22,
          statusRefreshedAt: iso(b.at + b.connectMs),
          lastSeenAt: iso(b.at + b.connectMs),
        })
      }
    }
  }

  function bindState(sn: string): { state: 'BINDABLE' | 'BOUND_BY_ME' | 'BOUND_BY_OTHER' | 'DISABLED'; device?: MockDevice } {
    const d = devices.find((x) => x.sn === sn)
    if (d) {
      if (d.role === 'OWNER') return { state: 'BOUND_BY_ME', device: d }
      return { state: d.enabled ? 'BOUND_BY_OTHER' : 'DISABLED' }
    }
    if (sn === MOCK_TAKEN_SN) return { state: 'BOUND_BY_OTHER' }
    if (MOCK_BINDABLE[sn]) return { state: 'BINDABLE' }
    throw new MockError(ErrorCode.DEVICE_NOT_FOUND, undefined, 404)
  }

  function normalizedSn(raw: unknown): string {
    const sn = String(raw ?? '').replace(/\s+/g, '').toUpperCase()
    if (!/^[A-Z0-9]{8,32}$/.test(sn)) throw new MockError(ErrorCode.BAD_REQUEST)
    return sn
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
      // 与契约一致：作为主人的设备在前，被授权的在后
      return [...devices.filter((d) => d.role === 'OWNER'), ...devices.filter((d) => d.role !== 'OWNER')].map(card)
    })
    .on('GET', '/devices/:id', (ctx) => {
      settleAll()
      return copy(find(ctx.params.id))
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
      if (same) return copy(advance(same))
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
      return copy(command)
    })
    .on('GET', '/devices/:id/commands/:commandId', (ctx) => {
      const d = find(ctx.params.id)
      const c = commands.find((x) => x.command.deviceId === d.id && String(x.command.id) === ctx.params.commandId)
      if (!c) throw new MockError(ErrorCode.NOT_FOUND, '指令不存在', 404)
      return copy(advance(c))
    })
    .on('GET', '/bind/:sn', (ctx) => {
      const sn = normalizedSn(ctx.params.sn)
      const { state, device: d } = bindState(sn)
      return { sn, model: 'SPG-100', state, deviceId: d?.id ?? null }
    })
    .on('POST', '/bind', (ctx) => {
      const sn = normalizedSn(ctx.data.sn)
      const code = String(ctx.data.activateCode ?? '').replace(/\s+/g, '').toUpperCase()
      if (!/^[0-9A-Z]{8}$/.test(code)) throw new MockError(ErrorCode.BAD_REQUEST)
      if (!user.phoneBound) throw new MockError(ErrorCode.BAD_REQUEST, '请先授权手机号')
      const fail = bindFailures.get(sn)
      if (fail && fail.lockedUntil > now()) {
        const minutes = Math.ceil((fail.lockedUntil - now()) / 60_000)
        throw new MockError(ErrorCode.ACTIVATE_LOCKED, `错误次数过多，请 ${minutes} 分钟后再试`, 429)
      }
      const { state, device: mine } = bindState(sn)
      if (state === 'BOUND_BY_ME') return card(mine!)
      if (state === 'BOUND_BY_OTHER') throw new MockError(ErrorCode.DEVICE_ALREADY_BOUND, undefined, 409)
      if (state === 'DISABLED') throw new MockError(ErrorCode.DEVICE_DISABLED, undefined, 403)
      const pending = MOCK_BINDABLE[sn]
      if (code !== pending.code) {
        const count = (fail && fail.lockedUntil <= now() && fail.lockedUntil > 0 ? 0 : (fail?.count ?? 0)) + 1
        if (count >= BIND_MAX_FAILURES) {
          bindFailures.set(sn, { count, lockedUntil: now() + BIND_LOCK_MS })
          throw new MockError(ErrorCode.ACTIVATE_LOCKED, '错误次数过多，请 30 分钟后再试', 429)
        }
        bindFailures.set(sn, { count, lockedUntil: 0 })
        throw new MockError(ErrorCode.ACTIVATE_CODE_WRONG, `激活码错误，还可尝试 ${BIND_MAX_FAILURES - count} 次`, 400)
      }
      bindFailures.delete(sn)
      const d = device({
        id: nextDeviceId++,
        sn,
        role: 'OWNER',
        onlineState: 'OFFLINE',
        gateStatus: 'UNKNOWN',
        occupied: null,
        signalLevel: null,
        statusRefreshedAt: null,
        lastSeenAt: null,
      })
      devices.push(d)
      bound.set(d.id, { at: now(), connectMs: pending.connectMs })
      return card(d)
    })
    .on('GET', '/devices/:id/members', (ctx) => {
      const d = ownerDevice(ctx.params.id)
      const m = liveMembers(d.id).sort((a, b) => a.validFrom - b.validFrom)
      const inv = liveInvites(d.id).sort((a, b) => a.createdAt - b.createdAt)
      return {
        limit: MEMBER_LIMIT,
        used: m.length + inv.length,
        members: m.map(memberView),
        invites: inv.map((i) => ({ id: i.id, remark: i.remark, period: i.period, expireAt: iso(i.expireAt), createdAt: iso(i.createdAt) })),
      }
    })
    .on('POST', '/devices/:id/invites', (ctx) => {
      const d = ownerDevice(ctx.params.id)
      if (!d.enabled) throw new MockError(ErrorCode.DEVICE_DISABLED, undefined, 403)
      const { period } = parsePeriod(ctx.data.period, now())
      const remark = remarkOf(ctx.data.remark)
      if (liveMembers(d.id).length + liveInvites(d.id).length >= MEMBER_LIMIT) throw new MockError(ErrorCode.MEMBER_LIMIT_REACHED, undefined, 409)
      const invite: MockInvite = { id: nextInviteId++, deviceId: d.id, remark, period, expireAt: now() + DAY, createdAt: now(), status: 'PENDING' }
      invites.push(invite)
      const token = `mockInviteToken${String(invite.id).padStart(28, '0')}`
      const name = d.name || `车位 ${d.sn}`
      return { id: invite.id, token, expireAt: iso(invite.expireAt), path: `/pages/invite/accept?token=${token}`, title: `${user.nickname}邀请你使用「${name}」` }
    })
    .on('DELETE', '/devices/:id/invites/:inviteId', (ctx) => {
      const d = ownerDevice(ctx.params.id)
      const i = invites.find((x) => x.deviceId === d.id && String(x.id) === ctx.params.inviteId)
      if (!i || (i.status === 'PENDING' && i.expireAt <= now())) throw new MockError(ErrorCode.NOT_FOUND, '邀请不存在', 404)
      i.status = 'CANCELLED'
      return null
    })
    .on('PATCH', '/devices/:id/members/:memberId', (ctx) => {
      const d = ownerDevice(ctx.params.id)
      const m = liveMembers(d.id).find((x) => String(x.id) === ctx.params.memberId)
      if (!m) throw new MockError(ErrorCode.NOT_FOUND, '授权不存在或已结束', 404)
      if (!d.enabled) throw new MockError(ErrorCode.DEVICE_DISABLED, undefined, 403)
      const keys = Object.keys(ctx.data)
      if (keys.length === 0 || keys.some((k) => k !== 'period' && k !== 'remark')) throw new MockError(ErrorCode.BAD_REQUEST)
      if ('period' in ctx.data) {
        const p = parsePeriod(ctx.data.period, now())
        // PERMANENT：起不变；RELATIVE：尚未开始的改为立即开始；CUSTOM：起止都按新值
        const type = (ctx.data.period as { type: string }).type
        if (type === 'PERMANENT') m.validTo = null
        else if (type === 'RELATIVE') {
          m.validFrom = Math.min(m.validFrom, now())
          m.validTo = p.validTo
        } else {
          m.validFrom = p.validFrom
          m.validTo = p.validTo
        }
      }
      if ('remark' in ctx.data) m.remark = remarkOf(ctx.data.remark)
      return memberView(m)
    })
    .on('DELETE', '/devices/:id/members/:memberId', (ctx) => {
      const d = ownerDevice(ctx.params.id)
      // 已撤销的再撤销视为成功；已退出、已到期或不属于本设备 10004
      const m = members.find((x) => x.deviceId === d.id && String(x.id) === ctx.params.memberId)
      const live = m && liveMembers(d.id).includes(m)
      if (!m || (!live && m.status !== 'REVOKED')) throw new MockError(ErrorCode.NOT_FOUND, '授权不存在或已结束', 404)
      m.status = 'REVOKED'
      return null
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

/** 返回副本，调用方改了也不影响 mock 里的数据（与真实接口一致） */
function copy<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T
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
