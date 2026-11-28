// 车位首页（MP-03）：列表、角色标签、有效期、快捷控制、空态与加载失败
const test = require('node:test')
const assert = require('node:assert/strict')
const { HomeController } = require('../.test-build/home/controller')
const { cardViewModel } = require('../.test-build/home/view')
const { ApiClient } = require('../.test-build/api/client')
const { ApiError, ErrorCode } = require('../.test-build/api/errors')
const { createMockServer, MOCK_DONE_MS } = require('../.test-build/mock/server')

const T0 = Date.parse('2026-11-28T10:00:00+08:00')

/** mock 服务端 + 可控时钟 + 手动定时器；wrap 可拦截请求 */
function harness(wrap) {
  let now = T0
  const server = createMockServer(() => now)
  const log = []
  const store = new Map()
  let transport = async (req) => server.handle(req)
  const client = new ApiClient({
    transport: async (req) => {
      if (req.path !== '/auth/login') log.push({ at: now - T0, method: req.method, path: req.path })
      return transport(req)
    },
    store: { get: (k) => store.get(k), set: (k, v) => store.set(k, v), remove: (k) => store.delete(k) },
    now: () => now,
    loginCode: async () => 'code',
  })
  const timers = []
  let seq = 0
  let keys = 0
  const ctrl = new HomeController({
    api: wrap ? wrap(client) : client,
    now: () => now,
    timers: {
      set: (fn, ms) => (timers.push({ id: ++seq, fn, at: now + ms }), seq),
      clear: (id) => {
        const i = timers.findIndex((t) => t.id === id)
        if (i >= 0) timers.splice(i, 1)
      },
    },
    newKey: () => `home-key-${++keys}-0000`,
    onChange: () => undefined,
  })
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
  const card = (id) => ctrl.snapshot.cards.find((c) => c.card.id === id)
  return {
    ctrl, client, server, log, timers, advance, flush, card,
    get state() { return ctrl.snapshot },
    vm: (id) => cardViewModel(card(id), now),
    setTransport: (fn) => (transport = fn),
  }
}

test('列表：主人的在前、被授权的在后；角色标签、有效期、状态与按钮', async () => {
  const h = harness()
  await h.ctrl.start()
  assert.deepEqual(h.state.cards.map((c) => c.card.id), [1, 3, 5, 6, 7, 2, 4])
  assert.equal(h.state.loading, false)

  const own = h.vm(1)
  assert.deepEqual(
    [own.title, own.spaceNo, own.roleLabel, own.roleTone, own.validity, own.online.label, own.gate.label, own.battery],
    ['家里车位', 'B2-108', '我的', 'info', '', '在线', '已落杆', '86%'],
  )
  assert.deepEqual(own.button, { kind: 'control', primary: 'OPEN' })
  assert.deepEqual([own.openPrimary, own.closePrimary], [true, false])
  assert.equal(own.result, null)

  const shared = h.vm(2)
  assert.deepEqual([shared.roleLabel, shared.roleTone, shared.validity], ['家人共享', 'success', '有效期至 12-05 10:00'])
  assert.deepEqual(shared.button, { kind: 'unavailable', reason: '设备休眠中，暂时无法远程控制' })
  // 没起名、未上报电量
  assert.deepEqual([h.vm(3).title, h.vm(3).battery, h.vm(3).batteryPct, h.vm(3).online.label], ['车位 0789', '—', null, '离线'])
  // 停用、故障：只给说明
  assert.deepEqual(h.vm(4).button, { kind: 'support', reason: '设备已停用，请联系客服' })
  assert.equal(h.vm(4).online.label, '已停用')
  assert.deepEqual(h.vm(7).button, { kind: 'support', reason: '设备故障，请联系客服' })
  assert.equal(h.timers.length, 0, '没有执行中的指令时首页不定时刷新')
})

test('快捷抬杆：执行中 → 动画 → 到位，1 秒轮询该指令，结束后刷新列表', async () => {
  const h = harness()
  await h.ctrl.start()
  const p = h.ctrl.control(1, 'OPEN')
  assert.equal(h.card(1).submitting, true)
  assert.equal(h.vm(1).button.kind, 'busy')
  await p
  assert.equal(h.vm(1).button.kind, 'busy')
  assert.equal(h.vm(1).result.text, '正在抬杆…')

  await h.advance(1000)
  assert.equal(h.vm(1).gate.label, '抬杆中', '动画以指令为准')
  await h.advance(1000)
  assert.equal(h.vm(1).result.text, '抬杆成功，已到位')
  assert.equal(h.card(1).card.gateStatus, 'OPENED')
  assert.deepEqual(h.vm(1).button, { kind: 'control', primary: 'CLOSE' })
  assert.deepEqual([h.vm(1).openPrimary, h.vm(1).closePrimary], [false, true])
  assert.deepEqual(h.log.map((r) => [r.at, r.method, r.path]), [
    [0, 'GET', '/devices'],
    [0, 'POST', '/devices/1/commands'],
    [1000, 'GET', '/devices/1/commands/1001'],
    [2000, 'GET', '/devices/1/commands/1001'],
    [2000, 'GET', '/devices'],
  ])
  assert.equal(h.timers.length, 0, '结束后不再轮询')

  // 结果显示到下一次进入页面
  await h.advance(60_000)
  assert.equal(h.vm(1).result.text, '抬杆成功，已到位')
  await h.ctrl.start()
  assert.equal(h.vm(1).result, null)
})

test('两张卡片同时执行：各自跟踪，一张结束只刷新一次列表', async () => {
  const h = harness()
  await h.ctrl.start()
  await h.ctrl.control(1, 'OPEN')
  await h.advance(500)
  await h.ctrl.control(5, 'OPEN')
  assert.equal(h.timers.length, 1, '同一时间只有一条轮询链')
  await h.advance(MOCK_DONE_MS)
  assert.equal(h.vm(1).result.text, '抬杆成功，已到位')
  assert.equal(h.vm(5).result.text, '闸杆遇阻，请检查现场')
  assert.equal(h.vm(5).result.tone, 'danger')
  assert.equal(h.card(5).card.gateStatus, 'STOPPED')
  assert.equal(h.timers.length, 0)
})

test('一张卡片结束后另一张还在执行：已结束的不再查，也不反复刷新列表', async () => {
  const h = harness()
  await h.ctrl.start()
  await h.ctrl.control(1, 'OPEN')
  await h.ctrl.control(6, 'OPEN')
  await h.advance(15_000)
  assert.equal(h.vm(1).result.text, '抬杆成功，已到位')
  assert.equal(h.card(6).phase.kind, 'timeout')
  assert.deepEqual(h.log.filter((r) => r.path === '/devices').map((r) => r.at), [0, 2000, 15000])
  assert.equal(h.log.filter((r) => r.path === '/devices/1/commands/1001').length, 2)
})

test('已在目标状态：直接成功，刷新列表，不轮询', async () => {
  const h = harness()
  await h.ctrl.start()
  await h.ctrl.control(1, 'CLOSE')
  assert.equal(h.vm(1).result.text, '已是落杆状态')
  assert.deepEqual(h.log.map((r) => r.path), ['/devices', '/devices/1/commands', '/devices'])
  assert.equal(h.timers.length, 0)
})

test('连点、执行中、不可控的卡片：不发请求', async () => {
  const h = harness()
  await h.ctrl.start()
  await Promise.all([h.ctrl.control(1, 'OPEN'), h.ctrl.control(1, 'OPEN'), h.ctrl.control(1, 'CLOSE')])
  await h.ctrl.control(1, 'CLOSE')
  for (const id of [2, 3, 4, 7, 99]) await h.ctrl.control(id, 'OPEN')
  assert.equal(h.log.filter((r) => r.method === 'POST').length, 1)
})

test('点击被拒：卡片上提示并刷新列表；授权到期则提示且卡片消失；其余就地提示', async () => {
  const fail = (code) => (client) => ({ get: (p, q) => client.get(p, q), post: async () => { throw new ApiError(code) } })
  const h = harness(fail(ErrorCode.DEVICE_OFFLINE))
  await h.ctrl.start()
  await h.ctrl.control(1, 'OPEN')
  assert.deepEqual(h.vm(1).result, { text: '设备离线，可在现场使用蓝牙', tone: 'warning' })
  assert.equal(h.card(1).phase, null)
  assert.equal(h.card(1).submitting, false)
  assert.deepEqual(h.log.map((r) => r.path), ['/devices', '/devices'])
  assert.equal(h.state.toast, null)

  // 授权到期：列表刷新后卡片不在了（这里用 mock 的“设备不存在”模拟刷新后被移除）
  let gone = false
  const h2 = harness((client) => ({
    get: async (p, q) => {
      const r = await client.get(p, q)
      return gone ? r.filter((c) => c.id !== 1) : r
    },
    post: async () => {
      gone = true
      throw new ApiError(ErrorCode.ACCESS_EXPIRED)
    },
  }))
  await h2.ctrl.start()
  await h2.ctrl.control(1, 'OPEN')
  assert.equal(h2.state.toast, '授权已到期或已被撤销')
  assert.equal(h2.card(1), undefined)
  h2.ctrl.clearToast()
  assert.equal(h2.state.toast, null)

  const h3 = harness(fail(ErrorCode.TOO_MANY_REQUESTS))
  await h3.ctrl.start()
  await h3.ctrl.control(1, 'OPEN')
  assert.equal(h3.vm(1).result.text, '操作过于频繁，请稍后再试')
  assert.deepEqual(h3.log.map((r) => r.path), ['/devices'])
  assert.equal(h3.vm(1).button.kind, 'control')
  // 下一次进入页面提示清掉
  await h3.ctrl.start()
  assert.equal(h3.vm(1).result, null)
})

test('提交时网络抖动：同一幂等键重试一次', async () => {
  const keys = []
  let fails = 1
  const h = harness((client) => ({
    get: (p, q) => client.get(p, q),
    post: async (p, data) => {
      keys.push(data.idempotencyKey)
      if (fails-- > 0) throw new ApiError(ErrorCode.NETWORK)
      return client.post(p, data)
    },
  }))
  await h.ctrl.start()
  await h.ctrl.control(1, 'OPEN')
  assert.equal(keys.length, 2)
  assert.equal(keys[0], keys[1])
  assert.equal(h.vm(1).result.text, '正在抬杆…')
})

test('空态与加载失败：失败可重试；已有列表时刷新失败保留旧列表', async () => {
  const empty = harness((client) => ({ get: async () => [], post: (p, d) => client.post(p, d) }))
  await empty.ctrl.start()
  assert.deepEqual(empty.state.cards, [])
  assert.equal(empty.state.loadError, null)

  const h = harness()
  h.setTransport(async () => {
    throw new Error('offline')
  })
  const p = h.ctrl.start()
  assert.equal(h.state.loading, true)
  await p
  assert.deepEqual([h.state.cards, h.state.loading, h.state.loadError], [null, false, '网络异常，请检查网络后重试'])
  h.setTransport(async (req) => h.server.handle(req))
  await h.ctrl.start()
  assert.equal(h.state.loadError, null)
  assert.equal(h.state.cards.length, 7)

  h.setTransport(async () => ({ status: 500, body: { code: ErrorCode.INTERNAL_ERROR, message: '系统繁忙' } }))
  await h.ctrl.start()
  assert.equal(h.state.stale, true)
  assert.equal(h.state.loadError, null)
  assert.equal(h.state.cards.length, 7)
  h.setTransport(async (req) => h.server.handle(req))
  await h.ctrl.start()
  assert.equal(h.state.stale, false)

  // 业务错误也给出原因
  const forbidden = harness()
  forbidden.setTransport(async () => ({ status: 403, body: { code: ErrorCode.FORBIDDEN, message: '无权查看' } }))
  await forbidden.ctrl.start()
  assert.equal(forbidden.state.loadError, '无权查看')
})

test('轮询中网络异常：标记可能不是最新，继续轮询直到结束', async () => {
  const h = harness()
  await h.ctrl.start()
  await h.ctrl.control(1, 'OPEN')
  h.setTransport(async () => {
    throw new Error('offline')
  })
  await h.advance(1000)
  assert.equal(h.state.stale, true)
  assert.equal(h.timers.length, 1)
  h.setTransport(async (req) => h.server.handle(req))
  await h.advance(1000)
  assert.equal(h.vm(1).result.text, '抬杆成功，已到位')
  assert.equal(h.state.stale, false)
})

test('轮询中授权失效：停止跟踪该卡片，刷新列表', async () => {
  let expired = false
  const h = harness((client) => ({
    get: async (p, q) => {
      if (expired && p.includes('/commands/')) throw new ApiError(ErrorCode.ACCESS_EXPIRED)
      const r = await client.get(p, q)
      return expired && p === '/devices' ? r.filter((c) => c.id !== 1) : r
    },
    post: (p, d) => client.post(p, d),
  }))
  await h.ctrl.start()
  await h.ctrl.control(1, 'OPEN')
  expired = true
  await h.advance(1000)
  assert.equal(h.card(1), undefined)
  assert.equal(h.timers.length, 0)
})

test('进入时卡片上有未终结的指令：能看到就跟踪；看不到的按列表 1 秒刷新等它结束', async () => {
  const h = harness()
  await h.client.ensureLogin()
  await h.client.post('/devices/1/commands', { action: 'OPEN', idempotencyKey: 'earlier-key-1' })
  await h.ctrl.start()
  assert.equal(h.vm(1).button.kind, 'busy')
  assert.equal(h.vm(1).result.text, '正在抬杆…')
  await h.advance(MOCK_DONE_MS)
  assert.equal(h.vm(1).result.text, '抬杆成功，已到位')

  let tries = 0
  const hidden = harness((client) => ({
    get: async (p, q) => {
      if (p.includes('/commands/')) {
        tries++
        throw new ApiError(ErrorCode.NOT_FOUND)
      }
      return client.get(p, q)
    },
    post: (p, d) => client.post(p, d),
  }))
  await hidden.client.ensureLogin()
  await hidden.client.post('/devices/1/commands', { action: 'OPEN', idempotencyKey: 'family-key-1' })
  await hidden.ctrl.start()
  assert.equal(hidden.vm(1).button.kind, 'busy')
  assert.equal(hidden.vm(1).result, null)
  await hidden.advance(MOCK_DONE_MS)
  assert.equal(hidden.vm(1).button.kind, 'control')
  assert.equal(hidden.card(1).card.gateStatus, 'OPENED')
  assert.equal(tries, 1, '看不到的指令只试一次')
  assert.deepEqual(hidden.log.filter((r) => r.path === '/devices').map((r) => r.at), [0, 1000, 2000])
  assert.equal(hidden.timers.length, 0)

  // 查未终结指令时网络异常：标记可能不是最新
  const flaky = harness((client) => ({
    get: async (p, q) => {
      if (p.includes('/commands/')) throw new ApiError(ErrorCode.NETWORK)
      return client.get(p, q)
    },
    post: (p, d) => client.post(p, d),
  }))
  await flaky.client.ensureLogin()
  await flaky.client.post('/devices/1/commands', { action: 'OPEN', idempotencyKey: 'flaky-key-1' })
  await flaky.ctrl.start()
  assert.equal(flaky.state.stale, true)
  assert.equal(flaky.state.cards.length, 7)

  // 列表刷新在途时页面隐藏：返回后不续约
  const inflight = harness((client) => ({
    get: async (p, q) => {
      if (p.includes('/commands/')) throw new ApiError(ErrorCode.NOT_FOUND)
      return client.get(p, q)
    },
    post: (p, d) => client.post(p, d),
  }))
  await inflight.client.ensureLogin()
  await inflight.client.post('/devices/1/commands', { action: 'OPEN', idempotencyKey: 'inflight-key-1' })
  await inflight.ctrl.start()
  let release
  inflight.setTransport((req) => new Promise((resolve) => (release = () => resolve(inflight.server.handle(req)))))
  await inflight.advance(1000)
  inflight.ctrl.stop()
  release()
  await inflight.flush()
  assert.equal(inflight.timers.length, 0)
})

test('等满上限仍未终结：不再轮询指令，按列表等设备释放', async () => {
  let posted = false
  const h = harness((client) => ({
    get: async (p, q) => {
      const r = await client.get(p, q)
      if (p === '/devices') return r.map((c) => (c.id === 1 && posted ? { ...c, pendingCommandId: 1001 } : c))
      return p.includes('/commands/') ? { ...r, status: 'EXECUTING', gateStatus: 'OPENING' } : r
    },
    post: (p, d) => ((posted = true), client.post(p, d)),
  }))
  await h.ctrl.start()
  await h.ctrl.control(1, 'OPEN')
  await h.advance(35_000)
  assert.equal(h.card(1).phase.kind, 'unconfirmed')
  const polls = h.log.filter((r) => r.path.includes('/commands/')).length
  await h.advance(3000)
  assert.equal(h.log.filter((r) => r.path.includes('/commands/')).length, polls, '不再轮询指令')
  assert.ok(h.log.filter((r) => r.path === '/devices' && r.at > 35_000).length >= 3, '按 1 秒刷新列表')
})

test('页面隐藏：停止轮询，在途请求返回后不续约；重新进入继续跟踪', async () => {
  const h = harness()
  await h.ctrl.start()
  await h.ctrl.control(1, 'OPEN')
  h.ctrl.stop()
  assert.equal(h.timers.length, 0)
  await h.advance(5000)
  assert.equal(h.log.length, 2)

  // 在途时停止
  const h2 = harness()
  await h2.ctrl.start()
  await h2.ctrl.control(1, 'OPEN')
  let release
  h2.setTransport((req) => new Promise((resolve) => (release = () => resolve(h2.server.handle(req)))))
  await h2.advance(1000)
  h2.ctrl.stop()
  release()
  await h2.flush()
  assert.equal(h2.timers.length, 0)
  h2.setTransport(async (req) => h2.server.handle(req))
  // 离开期间指令结束：重新进入后再查一次指令拿到结果，之后不再轮询
  await h2.advance(1000)
  await h2.ctrl.start()
  assert.equal(h2.card(1).card.gateStatus, 'OPENED')
  assert.equal(h2.timers.length, 1)
  await h2.advance(1000)
  assert.equal(h2.vm(1).result.text, '抬杆成功，已到位')
  assert.equal(h2.timers.length, 0)

  // 提交在途时隐藏
  const h3 = harness()
  await h3.ctrl.start()
  h3.setTransport((req) => new Promise((resolve) => (release = () => resolve(h3.server.handle(req)))))
  const p = h3.ctrl.control(1, 'OPEN')
  await h3.flush()
  h3.ctrl.stop()
  release()
  await p
  assert.equal(h3.timers.length, 0)
})

test('重新进入时仍在执行的指令接着跟踪，已结束的结果清掉', async () => {
  const h = harness()
  await h.ctrl.start()
  await h.ctrl.control(1, 'OPEN')
  await h.ctrl.control(5, 'OPEN')
  await h.advance(MOCK_DONE_MS)
  assert.equal(h.card(5).phase.kind, 'failed')
  await h.ctrl.control(1, 'CLOSE')
  h.ctrl.stop()
  await h.ctrl.start()
  assert.equal(h.card(5).phase, null, '已结束的清掉')
  assert.equal(h.card(1).phase.kind, 'sending', '执行中的保留')
  assert.equal(h.timers.length, 1)
})
