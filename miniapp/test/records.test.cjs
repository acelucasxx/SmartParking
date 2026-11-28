// 操作记录（MP-10）：按日分组、每条展示、分页合并、mock 的可见范围与按日筛选
const test = require('node:test')
const assert = require('node:assert/strict')
const r = require('../.test-build/records/records')
const { ApiClient } = require('../.test-build/api/client')
const { ErrorCode } = require('../.test-build/api/errors')
const { createMockServer } = require('../.test-build/mock/server')

const T0 = Date.parse('2026-11-28T10:00:00+08:00') // 周六
const DAY = 86_400_000

const app = (patch = {}) => ({
  id: 'c-1',
  at: '2026-11-28T09:05:30+08:00',
  approx: false,
  source: 'APP',
  action: 'OPEN',
  operatorName: '张先生',
  operatorRole: 'OWNER',
  channel: 'REMOTE',
  result: 'SUCCESS',
  resultCode: null,
  durationMs: 1240,
  ...patch,
})
const event = (patch = {}) => ({
  id: 'e-1',
  at: '2026-11-28T08:00:00+08:00',
  approx: false,
  source: 'REMOTE_CONTROL',
  action: 'CLOSE',
  operatorName: null,
  operatorRole: null,
  channel: null,
  result: null,
  resultCode: null,
  durationMs: null,
  ...patch,
})

test('东八区日期与分组标题', () => {
  assert.equal(r.dayKey(Date.parse('2026-11-28T16:30:00Z')), '2026-11-29')
  assert.equal(r.dayLabel('2026-11-28', T0), '今天')
  assert.equal(r.dayLabel('2026-11-27', T0), '昨天')
  assert.equal(r.dayLabel('2026-11-26', T0), '11-26 周四')
  assert.equal(r.dayLabel('2026-11-22', T0), '11-22 周日')
  assert.equal(r.dayLabel('2025-12-31', T0), '2025-12-31 周三')
  assert.equal(r.dayLabel('bad', T0), 'bad')
  // 跨零点：东八区 00:30 已是新的一天
  assert.equal(r.dayLabel('2026-11-29', Date.parse('2026-11-28T16:30:00Z')), '今天')
})

test('App 指令：操作人、角色、通道、结果、耗时', () => {
  assert.deepEqual(r.recordRow(app()), {
    id: 'c-1', time: '09:05', action: '抬杆', who: '张先生 · 远程', role: '主人', result: '成功', tone: 'success', duration: '1.2 秒',
  })
  const m = r.recordRow(app({ operatorName: null, operatorRole: 'MEMBER', channel: 'BLE', action: 'CLOSE' }))
  assert.deepEqual([m.action, m.who, m.role], ['落杆', '微信用户 · 蓝牙', '家人'])
  assert.equal(r.recordRow(app({ operatorRole: null })).role, '')
  assert.deepEqual(pick(r.recordRow(app({ resultCode: 'ALREADY_IN_TARGET', durationMs: 0 }))), ['已在目标状态', 'success', ''])
  assert.deepEqual(pick(r.recordRow(app({ result: 'FAILED', resultCode: 'OBSTRUCTED' }))), ['闸杆遇阻', 'danger', '1.2 秒'])
  assert.deepEqual(pick(r.recordRow(app({ result: 'FAILED', resultCode: null }))), ['失败', 'danger', '1.2 秒'])
  assert.deepEqual(pick(r.recordRow(app({ result: 'TIMEOUT', resultCode: 'TIMEOUT', durationMs: 15000 }))), ['超时', 'warning', '15.0 秒'])
  assert.deepEqual(pick(r.recordRow(app({ result: null, durationMs: null }))), ['执行中', 'muted', ''])
})
const pick = (row) => [row.result, row.tone, row.duration]

test('非 App 动作：触发方式，没有操作人与结果；定时查询发现的带“约”', () => {
  assert.deepEqual(r.recordRow(event()), {
    id: 'e-1', time: '08:00', action: '落杆', who: '遥控器', role: '', result: '', tone: 'muted', duration: '',
  })
  assert.equal(r.recordRow(event({ source: 'GROUND_SENSOR', approx: true })).time, '约 08:00')
  assert.equal(r.recordRow(event({ source: 'GROUND_SENSOR' })).who, '地感')
  assert.equal(r.recordRow(event({ source: 'UNKNOWN' })).who, '现场操作')
  assert.equal(r.recordRow(event({ durationMs: 500 })).duration, '', '非 App 不显示耗时')
})

test('按日分组保持倒序；分页合并按 id 去重', () => {
  const list = [
    app({ id: 'c-3', at: '2026-11-28T09:00:00+08:00' }),
    event({ id: 'e-2', at: '2026-11-28T00:10:00+08:00' }),
    event({ id: 'e-1', at: '2026-11-27T23:50:00+08:00' }),
    app({ id: 'c-1', at: '2026-11-25T08:00:00+08:00' }),
  ]
  const sections = r.groupByDay(list, T0)
  assert.deepEqual(sections.map((s) => [s.label, s.rows.map((x) => x.id)]), [
    ['今天', ['c-3', 'e-2']],
    ['昨天', ['e-1']],
    ['11-25 周三', ['c-1']],
  ])
  assert.deepEqual(r.groupByDay([], T0), [])
  const merged = r.mergePage([app({ id: 'c-3' }), app({ id: 'c-2' })], [app({ id: 'c-2' }), app({ id: 'c-1' })])
  assert.deepEqual(merged.map((x) => x.id), ['c-3', 'c-2', 'c-1'])
  assert.equal(r.hasMore({ page: 1, size: 20, total: 21 }), true)
  assert.equal(r.hasMore({ page: 1, size: 20, total: 20 }), false)
  assert.equal(r.hasMore({ page: 2, size: 20, total: 40 }), false)
})

test('空态文案', () => {
  assert.equal(r.emptyText('2026-11-28', true), '这一天没有操作记录')
  assert.equal(r.emptyText('', true), '还没有操作记录')
  assert.equal(r.emptyText('', false), '你还没有操作过这台设备')
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
  return { client }
}

test('mock：主人看到非 App 动作，倒序；按日筛选；格式不对 10001', async () => {
  const { client } = setup()
  const all = await client.get('/devices/1/records', { page: 1, size: 20 })
  assert.equal(all.total, 4)
  // 今天 02:02 延时自动关、02:00 遥控器、昨天 05:00 地感、3 天前遇阻
  assert.deepEqual(all.items.map((x) => x.source), ['AUTO_CLOSE', 'REMOTE_CONTROL', 'GROUND_SENSOR', 'OBSTRUCTED'])
  const times = all.items.map((x) => Date.parse(x.at))
  assert.deepEqual(times, [...times].sort((a, b) => b - a), '倒序')
  assert.equal(all.items.find((x) => x.source === 'GROUND_SENSOR').approx, true)
  // 控制页的最近一次操作与列表第一条一致
  assert.equal((await client.get('/devices/1')).lastOperation.id, all.items[0].id)

  const today = await client.get('/devices/1/records', { page: 1, size: 20, date: '2026-11-28' })
  assert.deepEqual(today.items.map((x) => x.source), ['AUTO_CLOSE', 'REMOTE_CONTROL'])
  assert.equal((await client.get('/devices/1/records', { date: '2026-11-20' })).total, 0)
  await assert.rejects(client.get('/devices/1/records', { date: '2026-11-1' }), (e) => e.code === ErrorCode.BAD_REQUEST)

  // App 指令与非 App 动作合并，按时间倒序
  await client.post('/devices/1/commands', { action: 'OPEN', idempotencyKey: 'records-key-1' })
  const merged = await client.get('/devices/1/records', { page: 1, size: 2 })
  assert.equal(merged.total, 5)
  assert.equal(merged.items[0].source, 'APP')
})

test('mock：被授权人看不到非 App 动作', async () => {
  const { client } = setup()
  const res = await client.get('/devices/2/records', { page: 1, size: 20 })
  assert.equal(res.total, 0)
})
