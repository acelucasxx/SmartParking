// mock 层：通过真实的 ApiClient 走一遍，验证页面开发时能拿到的数据与异常态
const test = require('node:test')
const assert = require('node:assert/strict')
const { ApiClient } = require('../.test-build/api/client')
const { ErrorCode } = require('../.test-build/api/errors')
const { createMockServer, MOCK_SENT_MS, MOCK_DONE_MS, MOCK_TIMEOUT_MS, toIso } = require('../.test-build/mock/server')

function setup() {
  let now = Date.parse('2026-11-28T10:00:00+08:00')
  const server = createMockServer(() => now)
  const store = new Map()
  const client = new ApiClient({
    transport: async (req) => server.handle(req),
    store: { get: (k) => store.get(k), set: (k, v) => store.set(k, v), remove: (k) => store.delete(k) },
    now: () => now,
    loginCode: async () => 'code',
  })
  return { client, server, tick: (ms) => (now += ms) }
}

const key = () => `key-${Math.random().toString(16).slice(2)}`
const code = (c) => (e) => e.code === c

test('登录后能拿到示例设备，覆盖主人 / 被授权人、在线 / 休眠 / 离线 / 停用，以及遇阻、超时、故障的演示设备', async () => {
  const { client } = setup()
  const cards = await client.get('/devices')
  assert.equal(cards.length, 7)
  assert.deepEqual(
    cards.map((c) => [c.role, c.enabled, c.onlineState, c.gateStatus]),
    [
      ['OWNER', true, 'ONLINE', 'CLOSED'],
      ['OWNER', true, 'OFFLINE', 'CLOSED'],
      ['OWNER', true, 'ONLINE', 'CLOSED'],
      ['OWNER', true, 'ONLINE', 'CLOSED'],
      ['OWNER', true, 'ONLINE', 'FAULT'],
      ['MEMBER', true, 'SLEEP', 'OPENED'],
      ['MEMBER', false, 'ONLINE', 'CLOSED'],
    ],
  )
  assert.deepEqual(cards.map((c) => c.id), [1, 3, 5, 6, 7, 2, 4], '主人的在前，被授权的在后')
  assert.equal(cards[1].batteryPct, null)
  assert.ok(!('model' in cards[0]), '卡片只含卡片字段')
  const detail = await client.get('/devices/1')
  assert.equal(detail.model, 'SPG-100')
  assert.equal(detail.bleAvailable, false)
  const status = await client.get('/devices/1/status')
  assert.deepEqual(Object.keys(status).sort(), [
    'batteryPct', 'enabled', 'gateStatus', 'id', 'lastSeenAt', 'occupied', 'onlineState', 'pendingCommandId', 'signalLevel', 'statusRefreshedAt',
  ])
})

test('抬杆：已下发 → 执行中（抬杆中）→ 到位成功，设备状态与最近操作随之更新', async () => {
  const { client, tick } = setup()
  const k = key()
  const cmd = await client.post('/devices/1/commands', { action: 'OPEN', idempotencyKey: k })
  assert.equal(cmd.status, 'PENDING')
  assert.equal((await client.get('/devices/1/status')).pendingCommandId, cmd.id)
  await assert.rejects(client.post('/devices/1/commands', { action: 'CLOSE', idempotencyKey: key() }), code(ErrorCode.COMMAND_IN_PROGRESS))
  // 连点（同一幂等键）返回同一条
  assert.equal((await client.post('/devices/1/commands', { action: 'OPEN', idempotencyKey: k })).id, cmd.id)

  tick(MOCK_SENT_MS - 1)
  assert.equal((await client.get(`/devices/1/commands/${cmd.id}`)).status, 'SENT')
  tick(1)
  const executing = await client.get(`/devices/1/commands/${cmd.id}`)
  assert.equal(executing.status, 'EXECUTING')
  assert.equal(executing.gateStatus, 'OPENING')
  assert.equal((await client.get('/devices/1/status')).gateStatus, 'OPENING')

  tick(MOCK_DONE_MS - MOCK_SENT_MS)
  const done = await client.get(`/devices/1/commands/${cmd.id}`)
  assert.equal(done.status, 'SUCCESS')
  assert.equal(done.durationMs, MOCK_DONE_MS)
  const status = await client.get('/devices/1/status')
  assert.equal(status.gateStatus, 'OPENED')
  assert.equal(status.pendingCommandId, null)
  const detail = await client.get('/devices/1')
  assert.equal(detail.lastOperation.id, `c-${cmd.id}`)
  assert.equal(detail.lastOperation.result, 'SUCCESS')
  const records = await client.get('/devices/1/records', { page: 1, size: 10 })
  assert.equal(records.total, 1)
  assert.equal(records.items[0].channel, 'REMOTE')
})

test('已在目标状态直接成功，不占用设备', async () => {
  const { client } = setup()
  const cmd = await client.post('/devices/1/commands', { action: 'CLOSE', idempotencyKey: key() })
  assert.equal(cmd.status, 'SUCCESS')
  assert.equal(cmd.resultCode, 'ALREADY_IN_TARGET')
  assert.equal((await client.get('/devices/1/status')).pendingCommandId, null)
})

test('休眠、离线、停用、参数不对时拒绝控制', async () => {
  const { client } = setup()
  await assert.rejects(client.post('/devices/2/commands', { action: 'OPEN', idempotencyKey: key() }), code(ErrorCode.DEVICE_SLEEPING))
  await assert.rejects(client.post('/devices/3/commands', { action: 'OPEN', idempotencyKey: key() }), code(ErrorCode.DEVICE_OFFLINE))
  await assert.rejects(client.post('/devices/4/commands', { action: 'OPEN', idempotencyKey: key() }), (e) => e.code === ErrorCode.DEVICE_DISABLED && e.action === 'support')
  await assert.rejects(client.post('/devices/7/commands', { action: 'OPEN', idempotencyKey: key() }), (e) => e.code === ErrorCode.DEVICE_FAULT && e.action === 'support')
  await assert.rejects(client.post('/devices/1/commands', { action: 'JUMP', idempotencyKey: key() }), code(ErrorCode.BAD_REQUEST))
  await assert.rejects(client.post('/devices/1/commands', { action: 'OPEN', idempotencyKey: 'short' }), code(ErrorCode.BAD_REQUEST))
  await assert.rejects(client.get('/devices/99'), (e) => e.code === ErrorCode.NOT_FOUND && e.action === 'leave')
  await assert.rejects(client.get('/devices/1/commands/1'), code(ErrorCode.NOT_FOUND))
})

test('改名走 PATCH 改写，被授权人不能改', async () => {
  const { client } = setup()
  const card = await client.patch('/devices/3', { name: '  新车位 ', spaceNo: '' })
  assert.equal(card.name, '新车位')
  assert.equal(card.spaceNo, null)
  await assert.rejects(client.patch('/devices/2', { name: 'x' }), code(ErrorCode.NOT_OWNER))
})

test('消息：倒序分页、未读数、按 ID 或全部标记已读', async () => {
  const { client } = setup()
  const page = await client.get('/messages', { page: 1, size: 1 })
  assert.equal(page.total, 2)
  assert.equal(page.items.length, 1)
  assert.equal(page.items[0].id, 2)
  assert.deepEqual(await client.get('/messages/unread-count'), { count: 1 })
  assert.equal(await client.post('/messages/read', { ids: [1] }), null)
  assert.deepEqual(await client.get('/messages/unread-count'), { count: 0 })
})

test('未实现的接口明确报“mock 未实现”；不带 token 返回 401', async () => {
  const { client, server } = setup()
  await assert.rejects(client.post('/devices/1/transfer', {}), (e) => e.code === ErrorCode.NOT_FOUND && e.message === 'mock 未实现：POST /devices/1/transfer')
  const anon = server.handle({ method: 'GET', path: '/devices', headers: {} })
  assert.equal(anon.status, 401)
  assert.equal(anon.body.code, ErrorCode.UNAUTHORIZED)
  const login = server.handle({ method: 'POST', path: '/auth/login', headers: {}, data: { code: 'fail' } })
  assert.equal(login.body.code, ErrorCode.WX_LOGIN_FAILED)
})

test('演示设备：5 遇阻失败、6 超时，结束后都释放设备', async () => {
  const { client, tick } = setup()
  const a = await client.post('/devices/5/commands', { action: 'OPEN', idempotencyKey: key() })
  const b = await client.post('/devices/6/commands', { action: 'OPEN', idempotencyKey: key() })
  tick(MOCK_DONE_MS)
  const failed = await client.get(`/devices/5/commands/${a.id}`)
  assert.deepEqual([failed.status, failed.resultCode, failed.gateStatus, failed.durationMs], ['FAILED', 'OBSTRUCTED', 'STOPPED', MOCK_DONE_MS])
  assert.equal(failed.resultMessage, '闸杆遇阻，请检查现场')
  assert.equal((await client.get('/devices/5/status')).pendingCommandId, null)
  assert.equal((await client.get('/devices/5')).lastOperation.result, 'FAILED')
  assert.equal((await client.get(`/devices/6/commands/${b.id}`)).status, 'EXECUTING')
  tick(MOCK_TIMEOUT_MS - MOCK_DONE_MS - 1)
  assert.equal((await client.get(`/devices/6/commands/${b.id}`)).status, 'EXECUTING')
  tick(1)
  const timeout = await client.get(`/devices/6/commands/${b.id}`)
  assert.deepEqual([timeout.status, timeout.resultCode, timeout.gateStatus, timeout.durationMs], ['TIMEOUT', 'TIMEOUT', 'UNKNOWN', MOCK_TIMEOUT_MS])
  assert.equal((await client.get('/devices/6/status')).pendingCommandId, null)
  assert.equal((await client.get('/devices/6')).lastOperation.result, 'TIMEOUT')
})

test('返回的是副本：调用方改了不影响 mock 数据', async () => {
  const { client } = setup()
  const d = await client.get('/devices/1')
  d.gateStatus = 'OPENED'
  d.name = 'x'
  assert.equal((await client.get('/devices/1')).gateStatus, 'CLOSED')
  const cmd = await client.post('/devices/1/commands', { action: 'OPEN', idempotencyKey: 'copy-key-1' })
  cmd.status = 'SUCCESS'
  const got = await client.get(`/devices/1/commands/${cmd.id}`)
  assert.equal(got.status, 'SENT')
  got.status = 'FAILED'
  assert.equal((await client.get(`/devices/1/commands/${cmd.id}`)).status, 'SENT')
})

test('toIso 输出东八区', () => {
  assert.equal(toIso(Date.parse('2026-12-31T18:00:00+08:00')), '2026-12-31T18:00:00+08:00')
  assert.equal(toIso(Date.parse('2026-12-31T16:30:05Z')), '2027-01-01T00:30:05+08:00')
})
