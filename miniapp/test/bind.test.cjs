// 扫码绑定（MP-05）：二维码取 SN、激活码输入、预检与绑定失败、等待联网
const test = require('node:test')
const assert = require('node:assert/strict')
const flow = require('../.test-build/bind/flow')
const { ConnectWatcher } = require('../.test-build/bind/connect')
const { ApiClient } = require('../.test-build/api/client')
const { ApiError, ErrorCode } = require('../.test-build/api/errors')
const { createMockServer, MOCK_BINDABLE, MOCK_TAKEN_SN, toIso } = require('../.test-build/mock/server')

const T0 = Date.parse('2026-11-28T10:00:00+08:00')

test('从扫码结果取 SN：不认域名，只认 sn 参数；也接受直接是 SN 的码', () => {
  assert.equal(flow.parseScan('https://smartparking.invalid/bind?sn=SP2612000888'), 'SP2612000888')
  assert.equal(flow.parseScan('https://qr.example.com/d?x=1&SN=sp2612000888&y=2#frag'), 'SP2612000888')
  assert.equal(flow.parseScan('https://qr.example.com/d?sn=SP2612%20000888'), 'SP2612000888', '编码的空白去掉')
  assert.equal(flow.parseScan('  sp2612000888  '), 'SP2612000888')
  assert.equal(flow.parseScan('SP26 1200 0888'), 'SP2612000888')
  for (const bad of [
    '',
    null,
    undefined,
    'https://qr.example.com/d',
    'https://qr.example.com/d?id=SP2612000888',
    'https://qr.example.com/d?sn=',
    'https://qr.example.com/d?sn',
    'https://qr.example.com/d#sn=SP2612000888',
    'https://qr.example.com/d?sn=SP-2612',
    'https://qr.example.com/d?sn=%E0%A4%A',
    'SP26',
    'SP2612000888SP2612000888SP26120008',
    '微信号 abc',
  ]) {
    assert.equal(flow.parseScan(bad), null, String(bad))
  }
  assert.equal(flow.parseScan('https://qr.example.com/d?xsn=1&sn=AB12CD34'), 'AB12CD34', '参数名要完全匹配')
  // 长度边界 8–32
  assert.equal(flow.parseScan('A'.repeat(32)), 'A'.repeat(32))
  assert.equal(flow.parseScan('A'.repeat(33)), null)
  assert.equal(flow.parseScan('A'.repeat(7)), null)
  // # 之后的不算查询串
  assert.equal(flow.parseScan('https://qr.example.com/d?a=1#&sn=SP2612000888'), null)
  // 第一个 sn 参数说了算，坏的就不认，不往后找
  assert.equal(flow.parseScan('https://qr.example.com/d?sn=%E0%A4%A&sn=SP2612000888'), null)
})

test('绑定页的 SN 来源：小程序内跳转 ?sn=，扫机身码直接打开 ?q=', () => {
  assert.equal(flow.snFromLaunch({ sn: 'sp2612000888' }), 'SP2612000888')
  const q = encodeURIComponent('https://smartparking.invalid/bind?sn=SP2612000888')
  assert.equal(flow.snFromLaunch({ q, scancode_time: '1764295200' }), 'SP2612000888')
  assert.equal(flow.snFromLaunch({ sn: 'SP2612000888', q: encodeURIComponent('https://x/b?sn=OTHER0001') }), 'SP2612000888', 'sn 优先')
  assert.equal(flow.snFromLaunch({ q: '%E0%A4%A' }), null)
  assert.equal(flow.snFromLaunch({ sn: 'bad' }), null)
  assert.equal(flow.snFromLaunch({}), null)
  assert.equal(flow.bindPageUrl('SP2612000888'), '/pages/bind/bind?sn=SP2612000888')
})

test('激活码输入：去空白与符号、转大写、最多 8 位', () => {
  assert.equal(flow.normalizeCode('k7q2 m9xa'), 'K7Q2M9XA')
  assert.equal(flow.normalizeCode('K7Q2-M9XA-EXTRA'), 'K7Q2M9XA')
  assert.equal(flow.normalizeCode('激活码k7'), 'K7')
  assert.equal(flow.codeComplete('K7Q2M9XA'), true)
  assert.equal(flow.codeComplete('K7Q2M9X'), false)
  assert.equal(flow.codeComplete('k7q2m9xa'), false, '输入框里已转大写')
  assert.equal(flow.codeComplete('K7Q2M9XAB'), false)
})

test('预检结果 → 页面状态', () => {
  const p = (state, deviceId) => flow.previewView({ sn: 'SP2612000888', model: 'SPG-100', state, deviceId })
  assert.equal(p('BINDABLE'), 'input')
  assert.equal(p('BOUND_BY_ME', 9), 'mine')
  assert.equal(p('BOUND_BY_ME', null), 'taken', '没有 deviceId 进不了控制页')
  assert.equal(p('BOUND_BY_OTHER'), 'taken')
  assert.equal(p('DISABLED'), 'disabled')
  assert.equal(flow.VIEW_TEXT.taken.title, '该设备已有主人')
})

test('绑定失败：已被他人绑定、停用、不存在切换页面；锁定禁用输入；码错误就地提示剩余次数', () => {
  const f = (code, message) => flow.bindFailure(new ApiError(code, message))
  assert.deepEqual(f(ErrorCode.DEVICE_ALREADY_BOUND), { kind: 'view', view: 'taken' })
  assert.deepEqual(f(ErrorCode.DEVICE_DISABLED), { kind: 'view', view: 'disabled' })
  assert.deepEqual(f(ErrorCode.DEVICE_NOT_FOUND), { kind: 'view', view: 'not-found' })
  assert.deepEqual(f(ErrorCode.ACTIVATE_LOCKED, '错误次数过多，请 12 分钟后再试'), { kind: 'locked', message: '错误次数过多，请 12 分钟后再试' })
  assert.deepEqual(f(ErrorCode.ACTIVATE_CODE_WRONG, '激活码错误，还可尝试 3 次'), { kind: 'retry', message: '激活码错误，还可尝试 3 次' })
  assert.deepEqual(f(ErrorCode.BAD_REQUEST, '请先授权手机号'), { kind: 'need-phone' })
  assert.deepEqual(f(ErrorCode.BAD_REQUEST), { kind: 'retry', message: '请求参数不合法' })
  assert.deepEqual(f(ErrorCode.NETWORK), { kind: 'retry', message: '网络异常，请检查网络后重试' })
})

test('联网判定：只认绑定前后窗口内的状态帧；60 秒未到为等待，之后为超时', () => {
  const at = T0
  const st = (ms) => ({ statusRefreshedAt: ms === null ? null : toIso(ms) })
  assert.equal(flow.connection(null, at, at), 'waiting')
  assert.equal(flow.connection(st(null), at, at + flow.CONNECT_WAIT_MS - 1), 'waiting')
  assert.equal(flow.connection(st(null), at, at + flow.CONNECT_WAIT_MS), 'timeout')
  assert.equal(flow.connection(st(at + 1000), at, at + 2000), 'connected')
  // 手机时钟比服务器快一点：仍算
  assert.equal(flow.connection(st(at - flow.CLOCK_SKEW_MS), at, at + 1000), 'connected')
  // 解绑前留下的旧刷新时刻：不算
  assert.equal(flow.connection(st(at - flow.CLOCK_SKEW_MS - 1000), at, at + 1000), 'waiting')
  assert.equal(flow.connection(st(at - 3_600_000), at, at + flow.CONNECT_WAIT_MS), 'timeout')
  assert.equal(flow.connection({ statusRefreshedAt: 'bad' }, at, at), 'waiting')
  assert.equal(flow.connection(st(at + 70_000), at, at + 70_000), 'connected', '超时后联网仍转为成功')
})

test('名称与车位号：最多 32 个字，可留空', () => {
  assert.equal(flow.settingsProblem('', ''), null)
  assert.equal(flow.settingsProblem('家'.repeat(32), ' B2-108 '), null)
  assert.equal(flow.settingsProblem(` ${'家'.repeat(32)} `, ''), null, '首尾空白不计')
  assert.equal(flow.settingsProblem('家'.repeat(33), ''), '名称最多 32 个字')
  assert.equal(flow.settingsProblem('', 'B'.repeat(33)), '车位号最多 32 个字')
})

// ------------------------------------------------------------ mock 接口与等待联网

function setup() {
  let now = T0
  const server = createMockServer(() => now)
  const store = new Map()
  const log = []
  let transport = async (req) => server.handle(req)
  const client = new ApiClient({
    transport: async (req) => {
      if (req.path !== '/auth/login') log.push({ at: now - T0, path: req.path })
      return transport(req)
    },
    store: { get: (k) => store.get(k), set: (k, v) => store.set(k, v), remove: (k) => store.delete(k) },
    now: () => now,
    loginCode: async () => 'code',
  })
  const timers = []
  let seq = 0
  const fakeTimers = {
    set: (fn, ms) => (timers.push({ id: ++seq, fn, at: now + ms }), seq),
    clear: (id) => {
      const i = timers.findIndex((t) => t.id === id)
      if (i >= 0) timers.splice(i, 1)
    },
  }
  const flush = async () => {
    for (let i = 0; i < 20; i++) await new Promise((r) => setImmediate(r))
  }
  async function advance(ms) {
    const end = now + ms
    for (;;) {
      timers.sort((a, b) => a.at - b.at)
      const next = timers[0]
      if (!next || next.at > end) break
      timers.shift()
      now = next.at
      next.fn()
      await flush()
    }
    now = end
  }
  return { client, server, log, timers, fakeTimers, advance, now: () => now, setTransport: (fn) => (transport = fn) }
}

const code = (c) => (e) => e.code === c
const [GOOD_SN, SILENT_SN] = Object.keys(MOCK_BINDABLE)

test('mock 预检：待激活 / 本人已绑定 / 已有主人 / 停用 / 不存在', async () => {
  const { client } = setup()
  assert.deepEqual(await client.get(`/bind/${GOOD_SN.toLowerCase()}`), { sn: GOOD_SN, model: 'SPG-100', state: 'BINDABLE', deviceId: null })
  assert.deepEqual((await client.get('/bind/SP2612000123')).state, 'BOUND_BY_ME')
  assert.equal((await client.get('/bind/SP2612000123')).deviceId, 1)
  assert.equal((await client.get(`/bind/${MOCK_TAKEN_SN}`)).state, 'BOUND_BY_OTHER')
  assert.equal((await client.get('/bind/SP2612000456')).state, 'BOUND_BY_OTHER', '被授权的设备对本人而言已有主人')
  assert.equal((await client.get('/bind/SP2612000999')).state, 'DISABLED')
  await assert.rejects(client.get('/bind/SP0000000000'), code(ErrorCode.DEVICE_NOT_FOUND))
})

test('mock 绑定：需先授权手机号；码错误带剩余次数，第 5 次锁定 30 分钟', async () => {
  const { client } = setup()
  const bind = (sn, activateCode) => client.post('/bind', { sn, activateCode })
  await assert.rejects(bind(GOOD_SN, MOCK_BINDABLE[GOOD_SN].code), (e) => e.code === ErrorCode.BAD_REQUEST && e.message === '请先授权手机号')
  await client.post('/auth/phone', { code: '13912341234' })
  await assert.rejects(bind(GOOD_SN, 'short'), code(ErrorCode.BAD_REQUEST))
  for (let left = 4; left >= 1; left--) {
    await assert.rejects(bind(GOOD_SN, 'WRONG000'), (e) => e.code === ErrorCode.ACTIVATE_CODE_WRONG && e.message === `激活码错误，还可尝试 ${left} 次`)
  }
  await assert.rejects(bind(GOOD_SN, 'WRONG000'), (e) => e.code === ErrorCode.ACTIVATE_LOCKED && e.message === '错误次数过多，请 30 分钟后再试')
  // 锁定期间对的码也不行
  await assert.rejects(bind(GOOD_SN, MOCK_BINDABLE[GOOD_SN].code), code(ErrorCode.ACTIVATE_LOCKED))
  // 其他 SN 不受影响
  const card = await bind(SILENT_SN, MOCK_BINDABLE[SILENT_SN].code.toLowerCase())
  assert.deepEqual([card.sn, card.role, card.gateStatus, card.onlineState], [SILENT_SN, 'OWNER', 'UNKNOWN', 'OFFLINE'])
  // 重复提交：本人已是主人直接成功
  assert.equal((await bind(SILENT_SN, 'WRONG000')).id, card.id)
  await assert.rejects(bind(MOCK_TAKEN_SN, 'WRONG000'), code(ErrorCode.DEVICE_ALREADY_BOUND))
  await assert.rejects(bind('SP2612000999', 'WRONG000'), code(ErrorCode.DEVICE_DISABLED))
  await assert.rejects(bind('SP0000000000', 'WRONG000'), code(ErrorCode.DEVICE_NOT_FOUND))
  const cards = await client.get('/devices')
  assert.ok(cards.some((c) => c.id === card.id && c.role === 'OWNER'), '绑定后出现在我的车位')
})

test('等待联网：每 2 秒查一次，收到状态帧即成功并停止', async () => {
  const h = setup()
  await h.client.post('/auth/phone', { code: '13912341234' })
  const card = await h.client.post('/bind', { sn: GOOD_SN, activateCode: MOCK_BINDABLE[GOOD_SN].code })
  const states = []
  const w = new ConnectWatcher({ api: h.client, deviceId: card.id, boundAt: h.now(), now: h.now, timers: h.fakeTimers, onChange: (s) => states.push(s.connection) })
  await w.start()
  assert.equal(w.snapshot.connection, 'waiting')
  await h.advance(10_000)
  assert.equal(w.snapshot.connection, 'connected')
  assert.equal(w.snapshot.status.onlineState, 'ONLINE')
  assert.deepEqual(h.log.filter((r) => r.path.endsWith('/status')).map((r) => r.at), [0, 2000, 4000])
  assert.equal(h.timers.length, 0)
  await w.start()
  assert.equal(h.log.filter((r) => r.path.endsWith('/status')).length, 3, '已成功不再查')
})

test('等待联网：60 秒未联网提示超时，之后每 5 秒继续查；页面隐藏即停', async () => {
  const h = setup()
  await h.client.post('/auth/phone', { code: '13912341234' })
  const card = await h.client.post('/bind', { sn: SILENT_SN, activateCode: MOCK_BINDABLE[SILENT_SN].code })
  const w = new ConnectWatcher({ api: h.client, deviceId: card.id, boundAt: h.now(), now: h.now, timers: h.fakeTimers, onChange: () => undefined })
  await w.start()
  await h.advance(58_000)
  assert.equal(w.snapshot.connection, 'waiting')
  await h.advance(2000)
  assert.equal(w.snapshot.connection, 'timeout')
  const before = h.log.length
  await h.advance(10_000)
  assert.deepEqual(h.log.slice(before).map((r) => r.at), [65_000, 70_000])
  w.stop()
  assert.equal(h.timers.length, 0)
  await h.advance(30_000)
  assert.equal(h.log.length, before + 2)
})

test('等待联网：网络异常继续等并照常判超时；设备不可用则离开', async () => {
  const h = setup()
  await h.client.ensureLogin()
  h.setTransport(async () => {
    throw new Error('offline')
  })
  const w = new ConnectWatcher({ api: h.client, deviceId: 1, boundAt: h.now(), now: h.now, timers: h.fakeTimers, onChange: () => undefined })
  await w.start()
  assert.equal(w.snapshot.connection, 'waiting')
  assert.equal(h.timers.length, 1)
  await h.advance(60_000)
  assert.equal(w.snapshot.connection, 'timeout')
  h.setTransport(async (req) => h.server.handle(req))
  await h.advance(5000)
  // 设备 1 的状态帧在绑定（T0）前 1 分钟内，属于窗口内
  assert.equal(w.snapshot.connection, 'connected')

  const h2 = setup()
  await h2.client.ensureLogin()
  const gone = new ConnectWatcher({ api: h2.client, deviceId: 404, boundAt: h2.now(), now: h2.now, timers: h2.fakeTimers, onChange: () => undefined })
  await gone.start()
  assert.equal(gone.snapshot.left, '设备不存在')
  assert.equal(h2.timers.length, 0)
  await gone.start()
  assert.equal(h2.log.filter((r) => r.path.endsWith('/status')).length, 1, '离开后不再查')
})

test('等待联网：查询在途时页面隐藏，返回后不续约', async () => {
  const h = setup()
  await h.client.ensureLogin()
  let release
  h.setTransport((req) => new Promise((resolve) => (release = () => resolve(h.server.handle(req)))))
  // 绑定时刻设在设备 3 最近一次状态帧之后，使其一直处于等待
  const w = new ConnectWatcher({ api: h.client, deviceId: 3, boundAt: h.now() + 600_000, now: h.now, timers: h.fakeTimers, onChange: () => undefined })
  const p = w.start()
  await new Promise((r) => setImmediate(r))
  await new Promise((r) => setImmediate(r))
  w.stop()
  release()
  await p
  assert.equal(h.timers.length, 0)
})
