// 成员管理与邀请家人（MP-06）：有效期选项与自定义时间校验、名额、文案、mock 接口
const test = require('node:test')
const assert = require('node:assert/strict')
const p = require('../.test-build/members/period')
const { ApiClient } = require('../.test-build/api/client')
const { ErrorCode } = require('../.test-build/api/errors')
const { createMockServer } = require('../.test-build/mock/server')

const T0 = Date.parse('2026-11-28T10:00:30+08:00')
const HOUR = 3_600_000
const DAY = 24 * HOUR

test('时间选择器与东八区互转', () => {
  assert.deepEqual(p.pickerParts(Date.parse('2026-12-31T16:30:00Z')), { date: '2027-01-01', time: '00:30' })
  assert.equal(p.fromPicker('2026-12-01', '08:05'), '2026-12-01T08:05:00+08:00')
  assert.equal(p.fromPicker('2026-12-1', '08:05'), null)
  assert.equal(p.fromPicker('2026-12-01', '8:05'), null)
  assert.equal(p.fromPicker('2026-13-01', '08:05'), null)
  assert.equal(p.fromPicker('', ''), null)
  assert.equal(p.shortDateTime('2026-12-01T08:05:00+08:00', T0), '12-01 08:05')
  assert.equal(p.shortDateTime('2027-01-02T08:05:00+08:00', T0), '2027-01-02 08:05')
  assert.equal(p.shortDateTime('bad', T0), 'bad')
})

test('有效期选项：永久 / 1 / 7 / 30 天 / 自定义（PRD 5.5②）', () => {
  assert.deepEqual(p.PERIOD_OPTIONS.map((o) => o.label), ['永久', '1 天', '7 天', '30 天', '自定义'])
  const r = p.defaultRange(T0)
  // 取整到下一分钟，结束为 7 天后
  assert.deepEqual(r, { fromDate: '2026-11-28', fromTime: '10:01', toDate: '2026-12-05', toTime: '10:01' })
  assert.deepEqual(p.defaultRange(Date.parse('2026-11-28T10:01:00+08:00')).fromTime, '10:01', '整分钟不进位')
  assert.deepEqual(p.buildPeriod('PERMANENT', r, T0), { ok: true, period: { type: 'PERMANENT' } })
  assert.deepEqual(p.buildPeriod('DAY_1', r, T0), { ok: true, period: { type: 'RELATIVE', durationDays: 1 } })
  assert.deepEqual(p.buildPeriod('DAY_7', r, T0), { ok: true, period: { type: 'RELATIVE', durationDays: 7 } })
  assert.deepEqual(p.buildPeriod('DAY_30', r, T0), { ok: true, period: { type: 'RELATIVE', durationDays: 30 } })
  assert.deepEqual(p.buildPeriod('CUSTOM', r, T0), {
    ok: true,
    period: { type: 'CUSTOM', validFrom: '2026-11-28T10:01:00+08:00', validTo: '2026-12-05T10:01:00+08:00' },
  })
})

test('自定义时间校验：止晚于起、止晚于当前；起可以早于当前', () => {
  const r = (fromDate, fromTime, toDate, toTime) => ({ fromDate, fromTime, toDate, toTime })
  assert.deepEqual(p.buildPeriod('CUSTOM', r('2026-11-28', '10:00', '2026-11-28', '10:00'), T0), { ok: false, message: '结束时间需晚于开始时间' })
  assert.deepEqual(p.buildPeriod('CUSTOM', r('2026-11-28', '10:00', '2026-11-28', '09:59'), T0), { ok: false, message: '结束时间需晚于开始时间' })
  // 结束时间早于（等于）当前：PRD 6.1 第 7 页异常态
  assert.deepEqual(p.buildPeriod('CUSTOM', r('2026-11-27', '08:00', '2026-11-28', '10:00'), Date.parse('2026-11-28T10:00:00+08:00')), {
    ok: false,
    message: '结束时间需晚于当前时间',
  })
  assert.equal(p.buildPeriod('CUSTOM', r('2026-11-27', '08:00', '2026-11-28', '10:01'), T0).ok, true, '起早于当前也可以')
  assert.deepEqual(p.buildPeriod('CUSTOM', r('', '', '2026-12-01', '08:00'), T0), { ok: false, message: '请选择开始和结束时间' })
  assert.deepEqual(p.buildPeriod('CUSTOM', r('2026-11-28', '10:00', '', ''), T0), { ok: false, message: '请选择开始和结束时间' })
})

test('修改成员时的初始选项', () => {
  assert.equal(p.choiceForMember({ validFrom: '2026-11-01T00:00:00+08:00', validTo: null }, T0).choice, 'PERMANENT')
  assert.deepEqual(p.choiceForMember({ validFrom: '2026-11-29T10:00:00+08:00', validTo: '2026-12-06T18:30:00+08:00' }, T0), {
    choice: 'CUSTOM',
    range: { fromDate: '2026-11-29', fromTime: '10:00', toDate: '2026-12-06', toTime: '18:30' },
  })
})

test('有效期文案：邀请与成员', () => {
  assert.equal(p.periodText({ type: 'PERMANENT' }, T0), '永久')
  assert.equal(p.periodText({ type: 'RELATIVE', durationDays: 7 }, T0), '7 天（接受后起算）')
  assert.equal(p.periodText({ type: 'CUSTOM', validFrom: '2026-12-01T08:00:00+08:00', validTo: '2026-12-31T18:00:00+08:00' }, T0), '12-01 08:00 至 12-31 18:00')
  // 生效中沿用首页口径
  assert.equal(p.memberValidity({ validFrom: '2026-11-01T00:00:00+08:00', validTo: null }, T0), '永久有效')
  assert.equal(p.memberValidity({ validFrom: '2026-11-01T00:00:00+08:00', validTo: '2026-12-31T18:00:00+08:00' }, T0), '有效期至 12-31 18:00')
  assert.equal(p.memberValidity({ validFrom: '2026-11-01T00:00:00+08:00', validTo: new Date(T0 + 5 * HOUR).toISOString() }, T0), '剩余 5 小时')
  // 尚未开始
  assert.equal(p.memberValidity({ validFrom: '2026-11-29T10:00:00+08:00', validTo: '2026-12-06T10:00:00+08:00' }, T0), '11-29 10:00 起生效 至 12-06 10:00')
  assert.equal(p.memberValidity({ validFrom: '2026-11-29T10:00:00+08:00', validTo: null }, T0), '11-29 10:00 起生效，永久')
  assert.equal(p.memberValidity({ validFrom: new Date(T0).toISOString(), validTo: null }, T0), '永久有效', '开始时刻即生效')
})

test('名额：满了置灰并提示（含待接受的邀请）', () => {
  assert.deepEqual(p.quota({ used: 2, limit: 5 }), { text: '已用 2 / 5', full: false, tip: '' })
  assert.deepEqual(p.quota({ used: 5, limit: 5 }), {
    text: '已用 5 / 5',
    full: true,
    tip: '名额已满（最多 5 人，含待接受的邀请），撤销成员或撤回邀请后可再邀请',
  })
  assert.equal(p.quota({ used: 6, limit: 5 }).full, true, '后台调低上限时')
})

test('备注与失败处理', () => {
  assert.equal(p.remarkProblem(''), null)
  assert.equal(p.remarkProblem(` ${'爸'.repeat(32)} `), null)
  assert.equal(p.remarkProblem('爸'.repeat(33)), '备注最多 32 个字')
  assert.equal(p.memberFailure(ErrorCode.MEMBER_LIMIT_REACHED), 'refresh')
  assert.equal(p.memberFailure(ErrorCode.NOT_FOUND), 'refresh')
  assert.equal(p.memberFailure(ErrorCode.NOT_OWNER), 'leave')
  assert.equal(p.memberFailure(ErrorCode.DEVICE_NOT_FOUND), 'leave')
  assert.equal(p.memberFailure(ErrorCode.INVALID_PERIOD), 'show')
  assert.equal(p.memberFailure(ErrorCode.DEVICE_DISABLED), 'show')
  assert.deepEqual(p.shareMessage({ id: 1, token: 't', expireAt: '', title: '张先生邀请你使用「家里车位」', path: '/pages/invite/accept?token=t' }), {
    title: '张先生邀请你使用「家里车位」',
    path: '/pages/invite/accept?token=t',
  })
})

// ------------------------------------------------------------ mock 接口

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

test('mock 成员列表：生效成员 + 待接受邀请占名额；仅主人', async () => {
  const { client } = setup()
  const o = await client.get('/devices/1/members')
  assert.equal(o.limit, 5)
  assert.equal(o.used, 3)
  assert.deepEqual(o.members.map((m) => m.nickname), ['张大爷', '李阿姨'])
  assert.equal(o.invites.length, 1)
  assert.deepEqual(o.invites[0].period, { type: 'RELATIVE', durationDays: 7 })
  await assert.rejects(client.get('/devices/2/members'), code(ErrorCode.NOT_OWNER))
})

test('mock 邀请：生成卡片占名额，满了 30010；有效期不合法 30011；撤回释放名额', async () => {
  const { client, tick } = setup()
  const card = await client.post('/devices/1/invites', { period: { type: 'PERMANENT' }, remark: ' 妹妹 ' })
  assert.match(card.path, /^\/pages\/invite\/accept\?token=.{43}$/)
  assert.equal(card.title, '张先生邀请你使用「家里车位」')
  await client.post('/devices/1/invites', { period: { type: 'RELATIVE', durationDays: 30 } })
  assert.equal((await client.get('/devices/1/members')).used, 5)
  await assert.rejects(client.post('/devices/1/invites', { period: { type: 'PERMANENT' } }), code(ErrorCode.MEMBER_LIMIT_REACHED))
  await assert.rejects(client.post('/devices/1/invites', { period: { type: 'RELATIVE', durationDays: 3 } }), code(ErrorCode.INVALID_PERIOD))
  await assert.rejects(
    client.post('/devices/1/invites', { period: { type: 'CUSTOM', validFrom: '2026-11-27T00:00:00+08:00', validTo: '2026-11-28T10:00:00+08:00' } }),
    code(ErrorCode.INVALID_PERIOD),
  )
  await assert.rejects(client.post('/devices/1/invites', { period: { type: 'PERMANENT' }, remark: '爸'.repeat(33) }), code(ErrorCode.BAD_REQUEST))
  await client.delete(`/devices/1/invites/${card.id}`)
  assert.equal((await client.get('/devices/1/members')).used, 4)
  // 已撤回的再撤回视为成功
  await client.delete(`/devices/1/invites/${card.id}`)
  // 卡片 24 小时后过期，自动释放名额
  tick(DAY)
  assert.equal((await client.get('/devices/1/members')).used, 2)
  await assert.rejects(client.delete('/devices/1/invites/21'), code(ErrorCode.NOT_FOUND))
})

test('mock 修改与撤销：立即生效；已结束的 10004', async () => {
  const { client, tick } = setup()
  const m = await client.patch('/devices/1/members/12', { period: { type: 'RELATIVE', durationDays: 1 }, remark: '' })
  assert.equal(m.remark, null)
  assert.ok(Date.parse(m.validFrom) <= T0, '尚未开始的改为立即开始')
  assert.equal(Date.parse(m.validTo), T0 + DAY)
  assert.equal((await client.patch('/devices/1/members/11', { remark: '老爸' })).validTo, null)
  await assert.rejects(client.patch('/devices/1/members/11', {}), code(ErrorCode.BAD_REQUEST))
  await client.delete('/devices/1/members/11')
  await client.delete('/devices/1/members/11')
  await assert.rejects(client.patch('/devices/1/members/11', { remark: 'x' }), code(ErrorCode.NOT_FOUND))
  // 到期那一刻起不再列出，不能修改或撤销
  tick(DAY)
  const o = await client.get('/devices/1/members')
  assert.deepEqual(o.members, [])
  await assert.rejects(client.delete('/devices/1/members/12'), code(ErrorCode.NOT_FOUND))
})
