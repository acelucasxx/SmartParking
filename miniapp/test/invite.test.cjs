// 接受邀请（MP-07）：token、四种失效态、接受失败、接受后去向、mock 接口
const test = require('node:test')
const assert = require('node:assert/strict')
const a = require('../.test-build/invite/accept')
const { ApiClient } = require('../.test-build/api/client')
const { ErrorCode } = require('../.test-build/api/errors')
const { createMockServer, MOCK_INVITE_TOKENS: TOKENS } = require('../.test-build/mock/server')

const T0 = Date.parse('2026-11-28T10:00:00+08:00')
const DAY = 86_400_000

test('token：URL 安全 Base64，22–64 位', () => {
  assert.equal(a.tokenValid('q3J9vX2mT8sLk4Wz1Rb7Yc'), true)
  assert.equal(a.tokenValid('A'.repeat(43)), true)
  assert.equal(a.tokenValid('a-b_'.repeat(16)), true)
  assert.equal(a.tokenValid('A'.repeat(21)), false)
  assert.equal(a.tokenValid('A'.repeat(65)), false)
  assert.equal(a.tokenValid('A'.repeat(30) + '+/'), false)
  assert.equal(a.tokenValid('A'.repeat(30) + '='), false)
  assert.equal(a.tokenValid(''), false)
  assert.equal(a.tokenValid(undefined), false)
})

test('失效态覆盖 PRD 的四种，另有自己发出的邀请', () => {
  assert.deepEqual(Object.keys(a.INVALID).sort(), ['ACCEPTED', 'ALREADY_MEMBER', 'CANCELLED', 'EXPIRED', 'OWN_INVITE'])
  assert.equal(a.INVALID.EXPIRED.title, '邀请已过期')
  assert.equal(a.INVALID.ACCEPTED.title, '邀请已被他人接受')
  assert.equal(a.INVALID.CANCELLED.title, '邀请已撤销')
  assert.equal(a.INVALID.ALREADY_MEMBER.title, '你已是该设备的成员')
  // 已是成员、自己发出的：引导回我的车位
  assert.equal(a.INVALID.ALREADY_MEMBER.action, 'home')
  assert.equal(a.INVALID.OWN_INVITE.action, 'home')
  assert.equal(a.INVALID.EXPIRED.action, 'none')
})

test('接受失败（服务端再次校验）→ 失效态或说明', () => {
  const f = (code, msg = 'm') => a.acceptFailure(code, msg)
  assert.deepEqual(f(ErrorCode.INVITE_EXPIRED), { kind: 'state', state: 'EXPIRED' })
  assert.deepEqual(f(ErrorCode.INVITE_USED), { kind: 'state', state: 'ACCEPTED' })
  assert.deepEqual(f(ErrorCode.INVITE_CANCELLED), { kind: 'state', state: 'CANCELLED' })
  assert.deepEqual(f(ErrorCode.ALREADY_MEMBER), { kind: 'state', state: 'ALREADY_MEMBER' })
  assert.deepEqual(f(ErrorCode.CANNOT_ACCEPT_OWN), { kind: 'state', state: 'OWN_INVITE' })
  assert.equal(f(ErrorCode.MEMBER_LIMIT_REACHED).invalid.title, '成员名额已满')
  assert.equal(f(ErrorCode.DEVICE_DISABLED).invalid.title, '设备已停用')
  assert.deepEqual(f(ErrorCode.NOT_FOUND), { kind: 'invalid', invalid: a.NOT_FOUND })
  assert.deepEqual(f(ErrorCode.NETWORK, '网络异常'), { kind: 'retry', message: '网络异常' })
})

test('接受后：自定义有效期尚未开始的不进控制页', () => {
  assert.deepEqual(a.afterAccept({ type: 'PERMANENT' }, T0), { started: true, text: '现在就可以使用了' })
  assert.equal(a.afterAccept({ type: 'RELATIVE', durationDays: 7 }, T0).started, true)
  assert.deepEqual(a.afterAccept({ type: 'CUSTOM', validFrom: '2026-11-29T08:00:00+08:00', validTo: '2026-12-06T08:00:00+08:00' }, T0), {
    started: false,
    text: '将于 11-29 08:00 起生效，届时会出现在“我的车位”',
  })
  assert.equal(a.afterAccept({ type: 'CUSTOM', validFrom: '2026-11-28T10:00:00+08:00', validTo: '2026-12-06T08:00:00+08:00' }, T0).started, true, '开始时刻即生效')
  assert.equal(a.previewPeriod({ period: { type: 'RELATIVE', durationDays: 30 } }, T0), '30 天（接受后起算）')
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

test('mock 预览：各状态的演示 token', async () => {
  const { client } = setup()
  for (const t of Object.values(TOKENS)) assert.equal(a.tokenValid(t), true, t)
  const states = {}
  for (const [k, t] of Object.entries(TOKENS)) states[k] = (await client.get(`/invites/${t}`)).state
  assert.deepEqual(states, { VALID: 'VALID', FUTURE: 'VALID', EXPIRED: 'EXPIRED', ACCEPTED: 'ACCEPTED', CANCELLED: 'CANCELLED', ALREADY_MEMBER: 'ALREADY_MEMBER' })
  const p = await client.get(`/invites/${TOKENS.VALID}`)
  assert.deepEqual([p.deviceName, p.ownerNickname, p.remark, p.period.type], ['王先生的车位', '王先生', '小张', 'RELATIVE'])
  await assert.rejects(client.get('/invites/notExistToken_0000000000'), code(ErrorCode.NOT_FOUND))
})

test('mock 接受：成为被授权人出现在我的车位；再看为已是成员；失效的按错误码拒绝', async () => {
  const { client } = setup()
  const card = await client.post(`/invites/${TOKENS.VALID}/accept`)
  assert.deepEqual([card.id, card.role, card.name], [31, 'MEMBER', '王先生的车位'])
  assert.equal(Date.parse(card.validTo), T0 + 7 * DAY, '7 天从接受时起算')
  assert.ok((await client.get('/devices')).some((c) => c.id === 31))
  assert.equal((await client.get(`/invites/${TOKENS.VALID}`)).state, 'ALREADY_MEMBER')
  await assert.rejects(client.post(`/invites/${TOKENS.VALID}/accept`), code(ErrorCode.ALREADY_MEMBER))
  await assert.rejects(client.post(`/invites/${TOKENS.EXPIRED}/accept`), code(ErrorCode.INVITE_EXPIRED))
  await assert.rejects(client.post(`/invites/${TOKENS.ACCEPTED}/accept`), code(ErrorCode.INVITE_USED))
  await assert.rejects(client.post(`/invites/${TOKENS.CANCELLED}/accept`), code(ErrorCode.INVITE_CANCELLED))
  await assert.rejects(client.post(`/invites/${TOKENS.ALREADY_MEMBER}/accept`), code(ErrorCode.ALREADY_MEMBER))
  // 尚未开始的：返回卡片，但车位列表先不显示
  const future = await client.post(`/invites/${TOKENS.FUTURE}/accept`)
  assert.equal(future.id, 32)
  assert.ok(!(await client.get('/devices')).some((c) => c.id === 32))
  // 虽未出现在列表里，再打开卡片也是“已是成员”，不能重复接受
  assert.equal((await client.get(`/invites/${TOKENS.FUTURE}`)).state, 'ALREADY_MEMBER')
  await assert.rejects(client.post(`/invites/${TOKENS.FUTURE}/accept`), code(ErrorCode.ALREADY_MEMBER))
})

test('mock：自己发出的邀请不能接受；邀请过期后预览为已过期', async () => {
  const { client, tick } = setup()
  const own = await client.post('/devices/1/invites', { period: { type: 'PERMANENT' } })
  const token = own.path.split('token=')[1]
  assert.equal((await client.get(`/invites/${token}`)).state, 'OWN_INVITE')
  await assert.rejects(client.post(`/invites/${token}/accept`), code(ErrorCode.CANNOT_ACCEPT_OWN))
  // 演示邀请 20 小时后截止：截止那一刻起即过期
  tick(20 * 3_600_000 - 1)
  assert.equal((await client.get(`/invites/${TOKENS.VALID}`)).state, 'VALID')
  tick(1)
  assert.equal((await client.get(`/invites/${TOKENS.VALID}`)).state, 'EXPIRED')
  await assert.rejects(client.post(`/invites/${TOKENS.VALID}/accept`), code(ErrorCode.INVITE_EXPIRED))
})
