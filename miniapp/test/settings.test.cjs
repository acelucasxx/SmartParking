// 设备设置（MP-09）：名称与车位号的修改请求、解绑 / 退出、mock 接口
const test = require('node:test')
const assert = require('node:assert/strict')
const st = require('../.test-build/device/settings')
const { DeviceController } = require('../.test-build/device/controller')
const { ApiClient } = require('../.test-build/api/client')
const { ErrorCode } = require('../.test-build/api/errors')
const { createMockServer } = require('../.test-build/mock/server')

const T0 = Date.parse('2026-11-28T10:00:00+08:00')

test('按字符计数，与服务端 codePointCount 一致', () => {
  assert.equal(st.charCount('家里车位'), 4)
  assert.equal(st.charCount('🚗🚗'), 2)
  assert.equal('🚗🚗'.length, 4, 'UTF-16 长度不同')
})

test('修改请求：只发变化的字段；名称不能清空；车位号可清空', () => {
  const o = { name: '家里车位', spaceNo: 'B2-108' }
  assert.deepEqual(st.devicePatch(o, '家里车位', 'B2-108'), { ok: true, patch: null })
  assert.deepEqual(st.devicePatch(o, ' 家里车位 ', ' B2-108 '), { ok: true, patch: null }, '首尾空白不算变化')
  assert.deepEqual(st.devicePatch(o, '新名字', 'B2-108'), { ok: true, patch: { name: '新名字' } })
  assert.deepEqual(st.devicePatch(o, '家里车位', ''), { ok: true, patch: { spaceNo: '' } })
  assert.deepEqual(st.devicePatch(o, '新名字', 'A1'), { ok: true, patch: { name: '新名字', spaceNo: 'A1' } })
  assert.deepEqual(st.devicePatch(o, '  ', 'B2-108'), { ok: false, message: '名称不能为空' })
  // 刚绑定、没有名称：留空表示不设置（绑定结果页只填车位号时不能发空名称）
  assert.deepEqual(st.devicePatch({}, '', 'B2-108'), { ok: true, patch: { spaceNo: 'B2-108' } })
  assert.deepEqual(st.devicePatch({ name: null, spaceNo: null }, '', ''), { ok: true, patch: null })
  assert.deepEqual(st.devicePatch({}, '家', ''), { ok: true, patch: { name: '家' } })
  // 长度按字符
  assert.deepEqual(st.devicePatch(o, '🚗'.repeat(32), ''), { ok: true, patch: { name: '🚗'.repeat(32), spaceNo: '' } })
  assert.deepEqual(st.devicePatch(o, '家'.repeat(33), ''), { ok: false, message: '名称最多 32 个字' })
  assert.deepEqual(st.devicePatch(o, '家', 'B'.repeat(33)), { ok: false, message: '车位号最多 32 个字' })
})

test('解绑与退出：二次确认文案、解绑有倒计时；失败处理', () => {
  assert.equal(st.UNBIND_CONFIRM.countdown, 3)
  for (const k of ['待激活', '家人', '激活码']) assert.ok(st.UNBIND_CONFIRM.content.includes(k), k)
  assert.equal(st.QUIT_CONFIRM.countdown, 0)
  for (const c of [ErrorCode.NOT_FOUND, ErrorCode.DEVICE_NOT_FOUND, ErrorCode.NOT_OWNER, ErrorCode.ACCESS_EXPIRED]) assert.equal(st.leaveFailure(c), 'gone', c)
  for (const c of [ErrorCode.NETWORK, ErrorCode.BAD_REQUEST, ErrorCode.INTERNAL_ERROR]) assert.equal(st.leaveFailure(c), 'show', c)
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
const code = (c) => (e) => e.code === c

test('mock 改名：名称 1–32 字不能清空，车位号可清空，至少一项；被授权人不能改', async () => {
  const { client } = setup()
  await assert.rejects(client.patch('/devices/1', { name: '  ' }), (e) => e.code === ErrorCode.BAD_REQUEST && e.message === '名称长度应为 1–32 个字符')
  await assert.rejects(client.patch('/devices/1', { name: '家'.repeat(33) }), code(ErrorCode.BAD_REQUEST))
  assert.equal((await client.patch('/devices/1', { name: '🚗'.repeat(32) })).name, '🚗'.repeat(32))
  await assert.rejects(client.patch('/devices/1', { spaceNo: 'B'.repeat(33) }), code(ErrorCode.BAD_REQUEST))
  await assert.rejects(client.patch('/devices/1', {}), code(ErrorCode.BAD_REQUEST))
  const c = await client.patch('/devices/1', { spaceNo: '' })
  assert.equal(c.spaceNo, null)
  assert.equal((await client.patch('/devices/3', { spaceNo: 'A1' })).name, null, '没有名称的设备只改车位号')
  await assert.rejects(client.patch('/devices/2', { spaceNo: 'x' }), code(ErrorCode.NOT_OWNER))
})

test('mock 解绑：仅主人；级联作废邀请、转让、撤销成员，设备从列表移除', async () => {
  const { client } = setup()
  const transfer = await client.post('/devices/1/transfer')
  const invite = await client.post('/devices/1/invites', { period: { type: 'PERMANENT' } })
  await assert.rejects(client.post('/devices/2/unbind'), code(ErrorCode.NOT_OWNER))
  assert.equal(await client.post('/devices/1/unbind'), null)
  assert.ok(!(await client.get('/devices')).some((d) => d.id === 1))
  await assert.rejects(client.get('/devices/1'), code(ErrorCode.NOT_FOUND))
  assert.equal((await client.get(`/transfers/${transfer.token}`)).state, 'CANCELLED')
  // 自己发出的邀请：预览仍识别为自己发出的（服务端按发出人先判定）；撤回接口已拿不到设备
  const token = invite.path.split('token=')[1]
  assert.equal((await client.get(`/invites/${token}`)).state, 'OWN_INVITE')
  assert.equal((await client.get(`/invites/${token}`)).deviceName, '家里车位')
  await assert.rejects(client.delete(`/devices/1/invites/${invite.id}`), code(ErrorCode.NOT_FOUND))
  await assert.rejects(client.post('/devices/1/unbind'), code(ErrorCode.NOT_FOUND))
})

test('mock 退出授权：被授权人退出后从列表移除；主人不能退出', async () => {
  const { client } = setup()
  await assert.rejects(client.delete('/devices/1/membership'), code(ErrorCode.BAD_REQUEST))
  assert.equal(await client.delete('/devices/2/membership'), null)
  assert.ok(!(await client.get('/devices')).some((d) => d.id === 2))
  await assert.rejects(client.delete('/devices/2/membership'), code(ErrorCode.NOT_FOUND))
})

test('控制页从设置页返回时刷新详情（名称）', async () => {
  const { client } = setup()
  const ctrl = new DeviceController({ api: client, deviceId: 1, now: () => T0, timers: { set: () => 0, clear: () => undefined }, onChange: () => undefined })
  await ctrl.start()
  assert.equal(ctrl.snapshot.device.name, '家里车位')
  await client.patch('/devices/1', { name: '新名字' })
  await ctrl.refreshDetail()
  assert.equal(ctrl.snapshot.device.name, '新名字')
  // 设备没了：离开
  await client.post('/devices/1/unbind')
  await ctrl.refreshDetail()
  assert.equal(ctrl.snapshot.left, '设备不存在')
  // 离开后不再请求
  await ctrl.refreshDetail()
  // 未加载时调用无效
  const fresh = new DeviceController({ api: client, deviceId: 3, now: () => T0, timers: { set: () => 0, clear: () => undefined }, onChange: () => undefined })
  await fresh.refreshDetail()
  assert.equal(fresh.snapshot.device, null)
})
