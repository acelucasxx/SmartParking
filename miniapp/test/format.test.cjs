// 展示用的纯函数：徽标、电量、标题、时间、有效期、幂等键；以及错误码处理方式
const test = require('node:test')
const assert = require('node:assert/strict')
const f = require('../.test-build/utils/format')
const { errorAction, defaultMessage, ApiError, ErrorCode } = require('../.test-build/api/errors')

test('在线徽标：停用、故障优先于在线状态', () => {
  assert.deepEqual(f.onlineBadge(false, 'ONLINE', 'FAULT'), { label: '已停用', tone: 'danger' })
  assert.deepEqual(f.onlineBadge(true, 'ONLINE', 'FAULT'), { label: '故障', tone: 'danger' })
  assert.deepEqual(f.onlineBadge(true, 'ONLINE', 'CLOSED'), { label: '在线', tone: 'success' })
  assert.deepEqual(f.onlineBadge(true, 'SLEEP', 'CLOSED'), { label: '休眠', tone: 'warning' })
  assert.deepEqual(f.onlineBadge(true, 'OFFLINE', 'CLOSED'), { label: '离线', tone: 'muted' })
})

test('闸杆徽标覆盖全部状态', () => {
  const labels = ['OPENED', 'CLOSED', 'OPENING', 'CLOSING', 'STOPPED', 'FAULT', 'UNKNOWN'].map((s) => f.gateBadge(s).label)
  assert.deepEqual(labels, ['已抬杆', '已落杆', '抬杆中', '落杆中', '中途停止', '故障', '状态未知'])
  assert.equal(f.gateBadge('FAULT').tone, 'danger')
})

test('电量：未知为“—”，四格向上取整，≤ 20% 低电量', () => {
  assert.deepEqual(f.battery(null), { text: '—', bars: -1, low: false })
  assert.deepEqual(f.battery(undefined), { text: '—', bars: -1, low: false })
  assert.deepEqual(f.battery(NaN), { text: '—', bars: -1, low: false })
  assert.deepEqual(f.battery(0), { text: '0%', bars: 0, low: true })
  assert.deepEqual(f.battery(1), { text: '1%', bars: 1, low: true })
  assert.deepEqual(f.battery(20), { text: '20%', bars: 1, low: true })
  assert.deepEqual(f.battery(21), { text: '21%', bars: 1, low: false })
  assert.deepEqual(f.battery(26), { text: '26%', bars: 2, low: false })
  assert.deepEqual(f.battery(75), { text: '75%', bars: 3, low: false })
  assert.deepEqual(f.battery(76), { text: '76%', bars: 4, low: false })
  assert.deepEqual(f.battery(150), { text: '100%', bars: 4, low: false })
  assert.deepEqual(f.battery(-5), { text: '0%', bars: 0, low: true })
  assert.equal(f.battery(85.6).text, '86%')
})

test('卡片标题：没名字用 SN 后 4 位', () => {
  assert.equal(f.deviceTitle({ name: '家里车位', sn: 'SP2612000123' }), '家里车位')
  assert.equal(f.deviceTitle({ name: '  ', sn: 'SP2612000123' }), '车位 0123')
  assert.equal(f.deviceTitle({ name: null, sn: 'SP2612000123' }), '车位 0123')
})

test('列表时间按东八区：今天 / 昨天 / 今年 / 往年', () => {
  const now = Date.parse('2026-11-28T09:00:00+08:00')
  assert.equal(f.formatTime('2026-11-28T00:05:00+08:00', now), '今天 00:05')
  // UTC 前一天 16:30 就是东八区今天 00:30
  assert.equal(f.formatTime('2026-11-27T16:30:00Z', now), '今天 00:30')
  assert.equal(f.formatTime('2026-11-27T23:59:00+08:00', now), '昨天 23:59')
  assert.equal(f.formatTime('2026-11-26T08:00:00+08:00', now), '11-26 08:00')
  assert.equal(f.formatTime('2025-12-31T08:00:00+08:00', now), '2025-12-31')
  assert.equal(f.formatTime('bad', now), 'bad')
})

test('有效期：永久 / 到期时刻 / 已到期', () => {
  const now = Date.parse('2026-11-28T09:00:00+08:00')
  assert.equal(f.validity(null, now), '永久有效')
  assert.equal(f.validity(undefined, now), '永久有效')
  assert.equal(f.validity('2026-12-31T18:00:00+08:00', now), '有效期至 12-31 18:00')
  assert.equal(f.validity('2027-01-02T08:00:00+08:00', now), '有效期至 2027-01-02 08:00')
  assert.equal(f.validity('2026-11-28T09:00:00+08:00', now), '已到期')
  assert.equal(f.validity('oops', now), 'oops')
})

test('幂等键为 UUID v4 格式，每次不同', () => {
  const a = f.idempotencyKey()
  assert.match(a, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  assert.notEqual(a, f.idempotencyKey())
  assert.equal(f.idempotencyKey(() => 0), '00000000-0000-4000-8000-000000000000')
  assert.equal(f.idempotencyKey(() => 0.999), 'ffffffff-ffff-4fff-bfff-ffffffffffff')
})

test('错误码 → 页面处理方式', () => {
  assert.equal(errorAction(ErrorCode.UNAUTHORIZED), 'relogin')
  assert.equal(errorAction(ErrorCode.USER_BANNED), 'banned')
  for (const c of [ErrorCode.NOT_FOUND, ErrorCode.DEVICE_NOT_FOUND, ErrorCode.ACCESS_EXPIRED]) assert.equal(errorAction(c), 'leave')
  for (const c of [ErrorCode.DEVICE_DISABLED, ErrorCode.DEVICE_FAULT]) assert.equal(errorAction(c), 'support')
  for (const c of [ErrorCode.NETWORK, ErrorCode.BAD_RESPONSE, ErrorCode.INTERNAL_ERROR, ErrorCode.TOO_MANY_REQUESTS, ErrorCode.WX_LOGIN_FAILED, ErrorCode.WX_PHONE_FAILED]) {
    assert.equal(errorAction(c), 'retry')
  }
  for (const c of [ErrorCode.DEVICE_OFFLINE, ErrorCode.DEVICE_SLEEPING, ErrorCode.COMMAND_IN_PROGRESS, ErrorCode.ACTIVATE_CODE_WRONG, ErrorCode.NOT_OWNER, 12345]) {
    assert.equal(errorAction(c), 'show')
  }
})

test('默认提示与 README 错误码表一致；空提示用默认文案', () => {
  assert.equal(defaultMessage(ErrorCode.ACTIVATE_LOCKED), '错误次数过多，请 30 分钟后再试')
  assert.equal(defaultMessage(ErrorCode.TRANSFER_PENDING_EXISTS), '已有进行中的转让')
  assert.equal(defaultMessage(99999), '操作失败，请稍后重试')
  assert.equal(new ApiError(ErrorCode.DEVICE_FAULT, '   ').message, '设备故障，请联系客服')
  assert.equal(new ApiError(ErrorCode.DEVICE_FAULT, '电机故障 E3').message, '电机故障 E3')
  const e = new ApiError(ErrorCode.NOT_OWNER)
  assert.ok(e instanceof Error)
  assert.equal(e.name, 'ApiError')
  assert.equal(e.status, 0)
})
