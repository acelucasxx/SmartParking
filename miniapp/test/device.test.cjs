// 设备控制页（MP-04）：三态主按钮、指令阶段、轮询节奏、异常态
const test = require('node:test')
const assert = require('node:assert/strict')
const c = require('../.test-build/device/control')
const { DeviceController } = require('../.test-build/device/controller')
const { viewModel } = require('../.test-build/device/view')
const { ApiClient } = require('../.test-build/api/client')
const { ApiError, ErrorCode } = require('../.test-build/api/errors')
const { createMockServer, MOCK_DONE_MS, MOCK_TIMEOUT_MS } = require('../.test-build/mock/server')

const T0 = Date.parse('2026-11-28T10:00:00+08:00')
const online = { enabled: true, onlineState: 'ONLINE', gateStatus: 'CLOSED', bleAvailable: false, pendingCommandId: null }

// ------------------------------------------------------------ 纯逻辑

test('三态主按钮：在线可控、休眠 / 离线给原因或蓝牙、故障 / 停用给客服', () => {
  assert.deepEqual(c.primaryButton(online, null), { kind: 'control', primary: 'OPEN' })
  assert.deepEqual(c.primaryButton({ ...online, gateStatus: 'OPENED' }, null), { kind: 'control', primary: 'CLOSE' })
  for (const gate of ['STOPPED', 'UNKNOWN', 'OPENING', 'CLOSING']) {
    assert.deepEqual(c.primaryButton({ ...online, gateStatus: gate }, null), { kind: 'control', primary: null }, gate)
  }
  // 蓝牙未上线：只显示原因
  assert.deepEqual(c.primaryButton({ ...online, onlineState: 'SLEEP' }, null), { kind: 'unavailable', reason: c.SLEEP_REASON })
  assert.deepEqual(c.primaryButton({ ...online, onlineState: 'OFFLINE' }, null), { kind: 'unavailable', reason: c.OFFLINE_REASON })
  assert.deepEqual(c.primaryButton({ ...online, onlineState: 'OFFLINE', bleAvailable: true }, null), {
    kind: 'ble',
    reason: `${c.OFFLINE_REASON}，可在现场使用蓝牙`,
  })
  // 在线时即使蓝牙可用也走远程
  assert.equal(c.primaryButton({ ...online, bleAvailable: true }, null).kind, 'control')
  // 停用、故障优先于一切，包括执行中
  assert.deepEqual(c.primaryButton({ ...online, enabled: false, pendingCommandId: 9 }, null), { kind: 'support', reason: '设备已停用，请联系客服' })
  assert.deepEqual(c.primaryButton({ ...online, gateStatus: 'FAULT', onlineState: 'OFFLINE' }, null), { kind: 'support', reason: '设备故障，请联系客服' })
  // 执行中：自己的指令未终结，或设备上有别人的指令；优先于休眠
  const moving = c.commandPhase({ action: 'OPEN', status: 'SENT' }, 0)
  assert.equal(c.primaryButton(online, moving).kind, 'busy')
  assert.equal(c.primaryButton({ ...online, onlineState: 'SLEEP', pendingCommandId: 3 }, null).kind, 'busy')
  // 终态不占按钮
  assert.equal(c.primaryButton(online, c.commandPhase({ action: 'OPEN', status: 'SUCCESS' }, 0)).kind, 'control')
})

test('指令阶段：只有到位才算成功；遇阻、超时给出原因；等太久不再卡在执行中', () => {
  const p = (cmd, elapsed = 0) => c.commandPhase({ action: 'OPEN', ...cmd }, elapsed)
  assert.deepEqual(p({ status: 'PENDING' }), { kind: 'sending', done: false, text: '正在抬杆…', tone: 'info', animation: null })
  assert.equal(p({ status: 'SENT' }).kind, 'sending')
  // 还没进入执行中时，闸杆在动不是本指令造成的（如遥控器），不播放动画
  assert.equal(p({ status: 'SENT', gateStatus: 'OPENING' }).kind, 'sending')
  // 执行中但还没回报动作：不播放动画
  assert.equal(p({ status: 'EXECUTING', gateStatus: 'CLOSED' }).kind, 'sending')
  assert.deepEqual(p({ status: 'EXECUTING', gateStatus: 'OPENING' }), { kind: 'moving', done: false, text: '抬杆中…', tone: 'info', animation: 'opening' })
  assert.deepEqual(c.commandPhase({ action: 'CLOSE', status: 'EXECUTING', gateStatus: 'CLOSING' }, 0).animation, 'closing')
  assert.equal(c.commandPhase({ action: 'CLOSE', status: 'EXECUTING', gateStatus: 'CLOSING' }, 0).text, '落杆中…')

  assert.deepEqual(p({ status: 'SUCCESS' }), { kind: 'success', done: true, text: '抬杆成功，已到位', tone: 'success', animation: null })
  assert.equal(p({ status: 'SUCCESS', resultCode: 'ALREADY_IN_TARGET' }).text, '已是抬杆状态')
  assert.equal(c.commandPhase({ action: 'CLOSE', status: 'SUCCESS', resultCode: 'ALREADY_IN_TARGET' }, 0).text, '已是落杆状态')
  assert.equal(c.commandPhase({ action: 'CLOSE', status: 'SUCCESS' }, 0).text, '落杆成功，已到位')

  // 服务端说明优先，没有时按 resultCode 兜底，都没有用通用文案
  assert.deepEqual(p({ status: 'FAILED', resultCode: 'OBSTRUCTED', resultMessage: '  服务端说明 ' }), {
    kind: 'failed', done: true, text: '服务端说明', tone: 'danger', animation: null,
  })
  assert.equal(p({ status: 'FAILED', resultCode: 'OBSTRUCTED', resultMessage: ' ' }).text, '闸杆遇阻，请检查现场')
  assert.equal(p({ status: 'FAILED', resultCode: 'INFRARED' }).text, '检测到障碍物，闸杆已停止')
  assert.equal(p({ status: 'FAILED' }).text, '抬杆失败，请重试')
  assert.equal(p({ status: 'FAILED', resultCode: 'ALREADY_IN_TARGET' }).text, '抬杆失败，请重试')
  assert.deepEqual(p({ status: 'TIMEOUT' }), { kind: 'timeout', done: true, text: '设备长时间未到位，请查看现场', tone: 'warning', animation: null })
  assert.equal(p({ status: 'TIMEOUT', resultMessage: '超时了' }).text, '超时了')

  // 前端等待上限
  assert.equal(p({ status: 'EXECUTING', gateStatus: 'OPENING' }, c.MAX_WAIT_MS - 1).kind, 'moving')
  assert.deepEqual(p({ status: 'EXECUTING', gateStatus: 'OPENING' }, c.MAX_WAIT_MS), {
    kind: 'unconfirmed', done: true, text: '暂未收到设备结果，请稍后在操作记录中查看', tone: 'warning', animation: null,
  })
  assert.equal(p({ status: 'SUCCESS' }, c.MAX_WAIT_MS * 2).kind, 'success', '终态不受等待上限影响')
  assert.ok(c.MAX_WAIT_MS > MOCK_TIMEOUT_MS, '要等得到服务端的超时判定')
})

test('轮询间隔：执行中 1 秒，其余 5 秒', () => {
  const executing = c.commandPhase({ action: 'OPEN', status: 'SENT' }, 0)
  const done = c.commandPhase({ action: 'OPEN', status: 'SUCCESS' }, 0)
  assert.equal(c.nextDelay(null, null), 5000)
  assert.equal(c.nextDelay(done, null), 5000)
  assert.equal(c.nextDelay(executing, null), 1000)
  assert.equal(c.nextDelay(done, 7), 1000)
  assert.equal(c.nextDelay(null, undefined), 5000)
})

test('点击被拒：状态变了就刷新，设备不可用就离开，其余就地提示', () => {
  for (const code of [20003, 20004, 20005, 20006, 20002]) assert.equal(c.rejection(code), 'refresh', code)
  for (const code of [10004, 20001, 30005]) assert.equal(c.rejection(code), 'leave', code)
  for (const code of [10001, 10011, -1, 19999]) assert.equal(c.rejection(code), 'show', code)
})

test('最近一次操作的文案：App 远程 / 蓝牙、非 App 触发标“约”、失败原因', () => {
  const now = T0
  const base = { id: 'c-1', at: '2026-11-28T09:05:00+08:00', approx: false, source: 'APP', action: 'OPEN', operatorName: '张先生', channel: 'REMOTE', result: 'SUCCESS' }
  assert.equal(c.operationText(base, now), '今天 09:05 · 张先生 远程抬杆 · 成功')
  assert.equal(c.operationText({ ...base, channel: 'BLE', action: 'CLOSE', operatorName: null }, now), '今天 09:05 · 蓝牙落杆 · 成功')
  assert.equal(c.operationText({ ...base, result: 'FAILED', resultCode: 'OBSTRUCTED' }, now), '今天 09:05 · 张先生 远程抬杆 · 闸杆遇阻')
  assert.equal(c.operationText({ ...base, result: 'FAILED', resultCode: null }, now), '今天 09:05 · 张先生 远程抬杆 · 失败')
  assert.equal(c.operationText({ ...base, result: 'TIMEOUT' }, now), '今天 09:05 · 张先生 远程抬杆 · 超时')
  assert.equal(c.operationText({ ...base, result: null }, now), '今天 09:05 · 张先生 远程抬杆')
  assert.equal(
    c.operationText({ ...base, id: 'e-1', approx: true, source: 'REMOTE_CONTROL', operatorName: null, channel: null, result: null }, now),
    '约 今天 09:05 · 遥控器抬杆',
  )
  assert.equal(c.operationText({ ...base, source: 'AUTO_CLOSE', action: 'CLOSE', result: null }, now), '今天 09:05 · 延时自动落杆')
})

// ------------------------------------------------------------ 轮询编排

/** mock 服务端 + 可控时钟 + 手动定时器；wrap 可拦截请求 */
function harness(deviceId, wrap) {
  let now = T0
  const server = createMockServer(() => now)
  const log = []
  const store = new Map()
  let transport = async (req) => server.handle(req)
  const client = new ApiClient({
    transport: async (req) => {
      if (req.path !== '/auth/login') log.push({ at: now - T0, method: req.method, path: req.path, data: req.data })
      return transport(req)
    },
    store: { get: (k) => store.get(k), set: (k, v) => store.set(k, v), remove: (k) => store.delete(k) },
    now: () => now,
    loginCode: async () => 'code',
  })
  const api = wrap ? wrap(client) : client
  const timers = []
  let seq = 0
  let keys = 0
  const states = []
  const ctrl = new DeviceController({
    api,
    deviceId,
    now: () => now,
    timers: {
      set: (fn, ms) => (timers.push({ id: ++seq, fn, at: now + ms }), seq),
      clear: (id) => {
        const i = timers.findIndex((t) => t.id === id)
        if (i >= 0) timers.splice(i, 1)
      },
    },
    newKey: () => `key-${++keys}-0000-0000`,
    onChange: (s) => states.push(s),
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
  return {
    ctrl, client, server, log, timers, states, advance, flush,
    get state() { return ctrl.snapshot },
    setTransport: (fn) => (transport = fn),
    since: (t) => log.filter((r) => r.at >= t),
  }
}

test('进入页面加载详情，之后每 5 秒刷新状态；同一时间只有一条轮询链', async () => {
  const h = harness(1)
  await h.ctrl.start()
  assert.equal(h.state.device.id, 1)
  assert.deepEqual(h.state.button, { kind: 'control', primary: 'OPEN' })
  assert.deepEqual(h.log.map((r) => r.path), ['/devices/1'])
  // 再次 onShow（如从后台回来）立即刷新，不叠加第二条链
  await h.ctrl.start()
  await h.advance(15_000)
  assert.deepEqual(h.log.map((r) => [r.at, r.path]), [
    [0, '/devices/1'],
    [0, '/devices/1/status'],
    [5000, '/devices/1/status'],
    [10000, '/devices/1/status'],
    [15000, '/devices/1/status'],
  ])
  assert.equal(h.timers.length, 1)
  h.ctrl.stop()
  assert.equal(h.timers.length, 0)
  await h.advance(20_000)
  assert.equal(h.log.length, 5, '页面隐藏后不再请求')
})

test('抬杆：执行中 → 抬杆动画 → 到位成功，期间每 1 秒轮询，结束后刷新详情并回到 5 秒', async () => {
  const h = harness(1)
  await h.ctrl.start()
  const p = h.ctrl.control('OPEN')
  assert.equal(h.state.submitting, true)
  assert.equal(h.state.button.kind, 'busy')
  assert.equal(h.state.phase.kind, 'sending')
  await p
  assert.equal(h.state.submitting, false)
  assert.equal(h.state.button.kind, 'busy', '指令未终结，按钮保持执行中')

  await h.advance(1000)
  assert.equal(h.state.phase.kind, 'moving')
  assert.equal(h.state.phase.animation, 'opening')
  const vm = viewModel(h.state, T0 + 1000)
  assert.equal(vm.gatePose, 'opening', '动画以指令为准，不等 5 秒状态刷新')
  assert.equal(vm.gate.label, '抬杆中')

  await h.advance(1000)
  assert.equal(h.state.phase.kind, 'success')
  assert.equal(h.state.phase.text, '抬杆成功，已到位')
  assert.equal(h.state.device.gateStatus, 'OPENED')
  assert.equal(h.state.device.lastOperation.result, 'SUCCESS')
  assert.deepEqual(h.state.button, { kind: 'control', primary: 'CLOSE' })

  await h.advance(10_000)
  assert.deepEqual(h.since(1).map((r) => [r.at, r.path]), [
    [1000, '/devices/1/commands/1001'],
    [2000, '/devices/1/commands/1001'],
    [2000, '/devices/1'],
    [7000, '/devices/1/status'],
    [12000, '/devices/1/status'],
  ])
  assert.equal(viewModel(h.state, T0 + 12_000).result.text, '抬杆成功，已到位', '结果一直显示到下一次点击')
})

test('已在目标状态：直接成功，不轮询', async () => {
  const h = harness(1)
  await h.ctrl.start()
  await h.ctrl.control('CLOSE')
  assert.equal(h.state.phase.text, '已是落杆状态')
  assert.equal(h.state.button.kind, 'control')
  await h.advance(5000)
  assert.deepEqual(h.log.map((r) => r.path), ['/devices/1', '/devices/1/commands', '/devices/1', '/devices/1/status'])
})

test('连点只提交一次；执行中再点无效', async () => {
  const h = harness(1)
  await h.ctrl.start()
  await Promise.all([h.ctrl.control('OPEN'), h.ctrl.control('OPEN'), h.ctrl.control('CLOSE')])
  await h.ctrl.control('CLOSE')
  assert.equal(h.log.filter((r) => r.method === 'POST').length, 1)
})

test('遇阻：显示原因，闸杆停在中途，按钮恢复且两个方向同等', async () => {
  const h = harness(5)
  await h.ctrl.start()
  await h.ctrl.control('OPEN')
  await h.advance(MOCK_DONE_MS)
  assert.deepEqual(
    { kind: h.state.phase.kind, text: h.state.phase.text, tone: h.state.phase.tone },
    { kind: 'failed', text: '闸杆遇阻，请检查现场', tone: 'danger' },
  )
  assert.equal(h.state.device.gateStatus, 'STOPPED')
  assert.deepEqual(h.state.button, { kind: 'control', primary: null })
  const vm = viewModel(h.state, T0 + MOCK_DONE_MS)
  assert.equal(vm.lastOperation, '今天 10:00 · 张先生 远程抬杆 · 闸杆遇阻')
  assert.deepEqual([vm.openPrimary, vm.closePrimary], [false, false])
})

test('超时：15 秒未到位由服务端判超时，期间一直 1 秒轮询', async () => {
  const h = harness(6)
  await h.ctrl.start()
  await h.ctrl.control('OPEN')
  await h.advance(MOCK_TIMEOUT_MS - 1)
  assert.equal(h.state.phase.kind, 'moving')
  await h.advance(1)
  assert.equal(h.state.phase.kind, 'timeout')
  assert.equal(h.state.phase.text, '设备长时间未到位，请查看现场')
  assert.equal(h.log.filter((r) => r.path === '/devices/6/commands/1001').length, MOCK_TIMEOUT_MS / 1000)
})

test('服务端迟迟不给终态：等满上限后停止 1 秒轮询指令，不重新开始跟踪同一条', async () => {
  const h = harness(1, (client) => ({
    get: async (path, q) => {
      const r = await client.get(path, q)
      if (path.endsWith('/status')) return { ...r, pendingCommandId: 1001 }
      return path.includes('/commands/') ? { ...r, status: 'EXECUTING', gateStatus: 'OPENING', finishedAt: null } : r
    },
    post: (path, data) => client.post(path, data),
  }))
  await h.ctrl.start()
  await h.ctrl.control('OPEN')
  await h.advance(c.MAX_WAIT_MS)
  assert.equal(h.state.phase.kind, 'unconfirmed')
  const polls = h.log.filter((r) => r.path.includes('/commands/')).length
  assert.equal(polls, c.MAX_WAIT_MS / 1000)
  await h.advance(10_000)
  assert.equal(h.log.filter((r) => r.path.includes('/commands/')).length, polls, '不再轮询指令')
  assert.equal(h.state.phase.kind, 'unconfirmed')
  // 服务端仍说有未终结指令：按钮保持执行中，按 1 秒刷新状态
  assert.equal(h.state.button.kind, 'busy')
  // 等满上限那次是终态，下一轮按 5 秒；之后发现仍有未终结指令，改为 1 秒：+5、+6 … +10 共 6 次
  assert.equal(h.log.filter((r) => r.path.endsWith('/status')).length, 6)
})

test('休眠、离线、停用、故障：按钮状态与说明，点击不发请求', async () => {
  const cases = [
    [2, { kind: 'unavailable', reason: c.SLEEP_REASON }],
    [3, { kind: 'unavailable', reason: c.OFFLINE_REASON }],
    [4, { kind: 'support', reason: '设备已停用，请联系客服' }],
    [7, { kind: 'support', reason: '设备故障，请联系客服' }],
  ]
  for (const [id, button] of cases) {
    const h = harness(id)
    await h.ctrl.start()
    assert.deepEqual(h.state.button, button, `设备 ${id}`)
    await h.ctrl.control('OPEN')
    assert.equal(h.log.filter((r) => r.method === 'POST').length, 0, `设备 ${id}`)
  }
  const owner = harness(1)
  await owner.ctrl.start()
  assert.equal(viewModel(owner.state, T0).subtitle, '车位号 B2-108', '主人不显示有效期')
  assert.equal(viewModel(owner.state, T0).roleLabel, '我的')
  assert.equal(viewModel(owner.state, T0).isOwner, true, '主人有成员管理入口')
  assert.equal(viewModel(owner.state, T0).id, 1)
  const h = harness(2)
  await h.ctrl.start()
  const vm = viewModel(h.state, T0)
  assert.equal(vm.roleLabel, '家人共享')
  assert.equal(vm.isOwner, false)
  assert.equal(vm.subtitle, '车位号 A1-016 · 有效期至 12-05 10:00')
  assert.equal(vm.online.label, '休眠')
  assert.equal(vm.occupancy, '有车')
  assert.equal(vm.signal, '强')
})

test('点击被拒（设备刚离线）：提示原因并立即刷新状态', async () => {
  const h = harness(1, (client) => ({
    get: (path, q) => client.get(path, q),
    post: async () => {
      throw new ApiError(ErrorCode.DEVICE_OFFLINE)
    },
  }))
  await h.ctrl.start()
  await h.ctrl.control('OPEN')
  assert.equal(h.state.notice, '设备离线，可在现场使用蓝牙')
  assert.equal(h.state.phase, null)
  assert.equal(h.state.submitting, false)
  assert.deepEqual(h.log.map((r) => r.path), ['/devices/1', '/devices/1/status'])
  assert.equal(viewModel(h.state, T0).result.text, '设备离线，可在现场使用蓝牙')
  // 其余错误就地提示，不刷新
  const h2 = harness(1, (client) => ({
    get: (path, q) => client.get(path, q),
    post: async () => {
      throw new ApiError(ErrorCode.TOO_MANY_REQUESTS)
    },
  }))
  await h2.ctrl.start()
  await h2.ctrl.control('OPEN')
  assert.equal(h2.state.notice, '操作过于频繁，请稍后再试')
  assert.deepEqual(h2.log.map((r) => r.path), ['/devices/1'])
  await h2.advance(5000)
  assert.equal(h2.log.length, 2, '提示后照常 5 秒刷新')
  // 授权刚到期：离开
  const h3 = harness(1, (client) => ({
    get: (path, q) => client.get(path, q),
    post: async () => {
      throw new ApiError(ErrorCode.ACCESS_EXPIRED)
    },
  }))
  await h3.ctrl.start()
  await h3.ctrl.control('OPEN')
  assert.equal(h3.state.left, '授权已到期或已被撤销')
  assert.equal(h3.timers.length, 0)
})

test('请求在途时重启或停止：旧链返回后不续约，始终只有一条轮询链', async () => {
  const h = harness(1)
  await h.ctrl.start()
  // 让 5 秒那次刷新挂起
  let release
  h.setTransport((req) => new Promise((resolve) => (release = () => resolve(h.server.handle(req)))))
  await h.advance(5000)
  assert.equal(h.timers.length, 0, '刷新在途')
  h.setTransport(async (req) => h.server.handle(req))
  await h.ctrl.control('OPEN')
  release()
  await h.flush()
  assert.equal(h.timers.length, 1)
  await h.advance(MOCK_DONE_MS)
  assert.equal(h.state.phase.kind, 'success')
  assert.equal(h.timers.length, 1)

  // 停止时有请求在途
  h.setTransport((req) => new Promise((resolve) => (release = () => resolve(h.server.handle(req)))))
  await h.advance(5000)
  h.ctrl.stop()
  release()
  await h.flush()
  assert.equal(h.timers.length, 0, '停止后在途请求返回也不续约')

  // 提交在途时页面隐藏
  const h2 = harness(1)
  await h2.ctrl.start()
  h2.setTransport((req) => new Promise((resolve) => (release = () => resolve(h2.server.handle(req)))))
  const p = h2.ctrl.control('OPEN')
  await h2.flush()
  h2.ctrl.stop()
  release()
  await p
  assert.equal(h2.timers.length, 0)
})

test('提交时网络抖动：用同一幂等键重试一次', async () => {
  let fails = 1
  const h = harness(1, (client) => ({
    get: (path, q) => client.get(path, q),
    post: async (path, data) => {
      h.log.push({ at: -1, method: 'TRY', path, data })
      if (fails-- > 0) throw new ApiError(ErrorCode.NETWORK)
      return client.post(path, data)
    },
  }))
  await h.ctrl.start()
  await h.ctrl.control('OPEN')
  const tries = h.log.filter((r) => r.method === 'TRY')
  assert.equal(tries.length, 2)
  assert.equal(tries[0].data.idempotencyKey, tries[1].data.idempotencyKey)
  assert.equal(h.state.phase.kind, 'sending')
  // 两次都失败：提示网络异常，按钮恢复
  fails = 2
  const h2 = harness(1, (client) => ({
    get: (path, q) => client.get(path, q),
    post: async () => {
      throw new ApiError(ErrorCode.NETWORK)
    },
  }))
  await h2.ctrl.start()
  await h2.ctrl.control('OPEN')
  assert.equal(h2.state.notice, '网络异常，请检查网络后重试')
  assert.equal(h2.state.button.kind, 'control')
})

test('刷新失败：保留旧数据并提示，恢复后提示消失；授权失效则离开且不再请求', async () => {
  const h = harness(1)
  await h.ctrl.start()
  h.setTransport(async () => {
    throw new Error('timeout')
  })
  await h.advance(5000)
  assert.equal(h.state.stale, true)
  assert.equal(h.state.device.id, 1)
  assert.equal(viewModel(h.state, T0).stale, true)
  h.setTransport(async (req) => h.server.handle(req))
  await h.advance(5000)
  assert.equal(h.state.stale, false)

  h.setTransport(async () => ({ status: 403, body: { code: ErrorCode.ACCESS_EXPIRED, message: '授权已到期' } }))
  await h.advance(5000)
  assert.equal(h.state.left, '授权已到期')
  const n = h.log.length
  assert.equal(h.timers.length, 0)
  await h.advance(20_000)
  await h.ctrl.start()
  assert.equal(h.log.length, n, '离开后 onShow 也不再请求')
})

test('首次加载失败可重试；设备不存在直接离开', async () => {
  const h = harness(1)
  h.setTransport(async () => {
    throw new Error('offline')
  })
  await h.ctrl.start()
  assert.equal(h.state.loadError, '网络异常，请检查网络后重试')
  assert.equal(h.state.device, null)
  assert.equal(h.timers.length, 0, '加载失败不轮询')
  h.setTransport(async (req) => h.server.handle(req))
  await h.ctrl.retry()
  assert.equal(h.state.loadError, null)
  assert.equal(h.state.device.id, 1)

  const missing = harness(99)
  await missing.ctrl.start()
  assert.equal(missing.state.left, '设备不存在')

  // 其他业务错误也给出原因与重试，而不是一直“加载中”
  const forbidden = harness(1)
  forbidden.setTransport(async () => ({ status: 403, body: { code: ErrorCode.FORBIDDEN, message: '无权查看' } }))
  await forbidden.ctrl.start()
  assert.equal(forbidden.state.loadError, '无权查看')
  assert.equal(forbidden.state.device, null)
})

test('进入页面时设备上有未终结的指令：接着跟踪；看不到的（家人的）按设备状态等它结束', async () => {
  const h = harness(1)
  await h.ctrl.start()
  await h.ctrl.control('OPEN')
  h.ctrl.stop()
  await h.advance(600)
  // 同一服务端上重新进入页面
  const again = new DeviceController({
    api: h.client, deviceId: 1, now: () => T0 + 600, timers: { set: () => 0, clear: () => undefined }, onChange: () => undefined,
  })
  await again.start()
  assert.equal(again.snapshot.phase.kind, 'moving')
  assert.equal(again.snapshot.button.kind, 'busy')

  // 被授权人看不到别人的指令：10004 不当作设备不可用
  let hiddenTries = 0
  const hidden = harness(1, (client) => ({
    get: async (path, q) => {
      if (path.includes('/commands/')) {
        hiddenTries++
        throw new ApiError(ErrorCode.NOT_FOUND, '指令不存在')
      }
      return client.get(path, q)
    },
    post: (path, data) => client.post(path, data),
  }))
  await hidden.client.ensureLogin()
  await hidden.client.post('/devices/1/commands', { action: 'OPEN', idempotencyKey: 'other-user-key' })
  await hidden.ctrl.start()
  assert.equal(hidden.state.left, null)
  assert.equal(hidden.state.phase, null)
  assert.equal(hidden.state.button.kind, 'busy')
  await hidden.advance(MOCK_DONE_MS)
  assert.equal(hidden.state.button.kind, 'control')
  assert.equal(hidden.state.device.lastOperation.result, 'SUCCESS', '结束后刷新详情')
  const paths = hidden.log.map((r) => [r.at, r.path])
  assert.equal(hiddenTries, 1, '看不到的指令只试一次')
  assert.deepEqual(paths.filter((p) => p[1].endsWith('/status')).map((p) => p[0]), [1000, 2000], '执行中按 1 秒刷新状态')
})
