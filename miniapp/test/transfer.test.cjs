// 设备转让（MP-08）：风险提示、失效态、接收失败、发起失败、mock 接口
const test = require('node:test')
const assert = require('node:assert/strict')
const t = require('../.test-build/transfer/transfer')
const { tokenValid } = require('../.test-build/invite/accept')
const { ApiClient } = require('../.test-build/api/client')
const { ErrorCode } = require('../.test-build/api/errors')
const { createMockServer, MOCK_TRANSFER_TOKENS: TOKENS } = require('../.test-build/mock/server')

const T0 = Date.parse('2026-11-28T10:00:00+08:00')
const DAY = 86_400_000

test('风险提示覆盖 PRD 5.6：失去权限、清空家人、不需到场不重置激活码、24 小时可取消', () => {
  const text = t.RISKS.join('')
  for (const k of ['失去', '全部清空', '不需要到现场', '不会重置激活码', '24 小时', '取消']) assert.ok(text.includes(k), k)
  assert.ok(t.ACCEPT_NOTES.join('').includes('原主人'))
  assert.equal(t.CONFIRM_COUNTDOWN, 3)
})

test('失效态：已过期、已被接收、已取消、自己发起的', () => {
  assert.deepEqual(Object.keys(t.INVALID).sort(), ['ACCEPTED', 'CANCELLED', 'EXPIRED', 'OWN_TRANSFER'])
  assert.equal(t.INVALID.EXPIRED.title, '转让卡片已过期')
  assert.equal(t.INVALID.CANCELLED.title, '转让已取消')
})

test('接收失败 → 失效态或说明', () => {
  const f = (code, msg = 'm') => t.acceptFailure(code, msg)
  assert.deepEqual(f(ErrorCode.TRANSFER_EXPIRED), { kind: 'state', state: 'EXPIRED' })
  assert.deepEqual(f(ErrorCode.TRANSFER_USED), { kind: 'state', state: 'ACCEPTED' })
  assert.deepEqual(f(ErrorCode.TRANSFER_CANCELLED), { kind: 'state', state: 'CANCELLED' })
  assert.deepEqual(f(ErrorCode.CANNOT_ACCEPT_OWN), { kind: 'state', state: 'OWN_TRANSFER' })
  assert.equal(f(ErrorCode.DEVICE_DISABLED).invalid.title, '设备已停用')
  assert.deepEqual(f(ErrorCode.NOT_FOUND), { kind: 'invalid', invalid: t.NOT_FOUND })
  assert.deepEqual(f(ErrorCode.NETWORK, '网络异常'), { kind: 'retry', message: '网络异常' })
})

test('发起失败：已有进行中的重新加载，不是主人离开，其余就地提示', () => {
  assert.equal(t.startFailure(ErrorCode.TRANSFER_PENDING_EXISTS), 'reload')
  assert.equal(t.startFailure(ErrorCode.NOT_OWNER), 'leave')
  assert.equal(t.startFailure(ErrorCode.NOT_FOUND), 'leave')
  assert.equal(t.startFailure(ErrorCode.DEVICE_NOT_FOUND), 'leave')
  assert.equal(t.startFailure(ErrorCode.DEVICE_DISABLED), 'show')
})

function setup() {
  let now = T0
  const server = createMockServer(() => now)
  const store = new Map()
  const client = new ApiClient({
    transport: async (req) => server.handle(req),
    store: { get: (k) => store.get(k), set: (k, v) => store.set(k, v), remove: (k) => store.delete(k) },
    now: () => now,
    loginCode: async () => 'code',
  })
  return { client, tick: (ms) => (now += ms) }
}
const code = (c) => (e) => e.code === c

test('mock 发起与取消：同一设备只有一张有效卡片；过期后可重新发起；仅主人', async () => {
  const { client, tick } = setup()
  assert.equal(await client.get('/devices/1/transfer'), null)
  const card = await client.post('/devices/1/transfer')
  assert.match(card.path, /^\/pages\/transfer\/accept\?token=[A-Za-z0-9_-]{22,64}$/)
  assert.equal(tokenValid(card.token), true)
  assert.equal(card.title, '张先生向你转让「家里车位」')
  assert.equal((await client.get('/devices/1/transfer')).id, card.id)
  await assert.rejects(client.post('/devices/1/transfer'), code(ErrorCode.TRANSFER_PENDING_EXISTS))
  // 自己打开自己的卡片
  assert.equal((await client.get(`/transfers/${card.token}`)).state, 'OWN_TRANSFER')
  await assert.rejects(client.post(`/transfers/${card.token}/accept`), code(ErrorCode.CANNOT_ACCEPT_OWN))
  await client.delete('/devices/1/transfer')
  assert.equal(await client.get('/devices/1/transfer'), null)
  assert.equal((await client.get(`/transfers/${card.token}`)).state, 'CANCELLED')
  await assert.rejects(client.delete('/devices/1/transfer'), code(ErrorCode.NOT_FOUND))
  // 过期：视为没有进行中的转让
  const again = await client.post('/devices/1/transfer')
  tick(DAY)
  assert.equal(await client.get('/devices/1/transfer'), null)
  assert.equal((await client.get(`/transfers/${again.token}`)).state, 'EXPIRED')
  await client.post('/devices/1/transfer')
  await assert.rejects(client.get('/devices/2/transfer'), code(ErrorCode.NOT_OWNER))
  await assert.rejects(client.post('/devices/2/transfer'), code(ErrorCode.NOT_OWNER))
})

test('mock 预览与接收：各状态的演示 token', async () => {
  const { client } = setup()
  for (const tk of Object.values(TOKENS)) assert.equal(tokenValid(tk), true, tk)
  const states = {}
  for (const [k, tk] of Object.entries(TOKENS)) states[k] = (await client.get(`/transfers/${tk}`)).state
  assert.deepEqual(states, { VALID: 'VALID', MEMBER: 'VALID', EXPIRED: 'EXPIRED', ACCEPTED: 'ACCEPTED', CANCELLED: 'CANCELLED' })
  const p = await client.get(`/transfers/${TOKENS.VALID}`)
  assert.deepEqual([p.deviceName, p.sn, p.fromNickname], ['吴先生的车位', 'SP2612004101', '吴先生'])

  const card = await client.post(`/transfers/${TOKENS.VALID}/accept`)
  assert.deepEqual([card.id, card.role], [41, 'OWNER'])
  assert.ok((await client.get('/devices')).some((c) => c.id === 41 && c.role === 'OWNER'))
  assert.equal((await client.get(`/transfers/${TOKENS.VALID}`)).state, 'OWN_TRANSFER', '已是主人')
  await assert.rejects(client.post(`/transfers/${TOKENS.VALID}/accept`), code(ErrorCode.CANNOT_ACCEPT_OWN))

  // 原来是被授权人：改为主人，有效期清空
  const member = await client.post(`/transfers/${TOKENS.MEMBER}/accept`)
  assert.deepEqual([member.id, member.role, member.validTo], [2, 'OWNER', null])
  assert.equal((await client.get('/devices')).filter((c) => c.id === 2).length, 1, '不重复')

  await assert.rejects(client.post(`/transfers/${TOKENS.EXPIRED}/accept`), code(ErrorCode.TRANSFER_EXPIRED))
  await assert.rejects(client.post(`/transfers/${TOKENS.ACCEPTED}/accept`), code(ErrorCode.TRANSFER_USED))
  await assert.rejects(client.post(`/transfers/${TOKENS.CANCELLED}/accept`), code(ErrorCode.TRANSFER_CANCELLED))
  await assert.rejects(client.get('/transfers/notExistToken_0000000000'), code(ErrorCode.NOT_FOUND))
})

test('mock：卡片截止那一刻起即过期', async () => {
  const { client, tick } = setup()
  tick(20 * 3_600_000 - 1)
  assert.equal((await client.get(`/transfers/${TOKENS.VALID}`)).state, 'VALID')
  tick(1)
  assert.equal((await client.get(`/transfers/${TOKENS.VALID}`)).state, 'EXPIRED')
  await assert.rejects(client.post(`/transfers/${TOKENS.VALID}/accept`), code(ErrorCode.TRANSFER_EXPIRED))
})
