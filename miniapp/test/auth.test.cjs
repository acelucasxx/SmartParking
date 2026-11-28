// 登录授权（MP-02）：手机号授权结果、回跳地址、协议版本、授权后更新本地资料
const test = require('node:test')
const assert = require('node:assert/strict')
const flow = require('../.test-build/auth/flow')
const { AGREEMENT_VERSION, AGREEMENTS, agreement, AGREEMENT_IS_PLACEHOLDER } = require('../.test-build/content/agreements')
const { ApiClient } = require('../.test-build/api/client')
const { ErrorCode } = require('../.test-build/api/errors')
const { createMockServer } = require('../.test-build/mock/server')

test('手机号授权回调：拿到 code / 用户拒绝 / 其他失败', () => {
  assert.deepEqual(flow.phoneResult({ code: ' abc ', errMsg: 'getPhoneNumber:ok' }), { kind: 'code', code: 'abc' })
  assert.deepEqual(flow.phoneResult({ errMsg: 'getPhoneNumber:fail user deny' }), { kind: 'denied' })
  assert.deepEqual(flow.phoneResult({ errMsg: 'getPhoneNumber:fail:user cancel' }), { kind: 'denied' })
  assert.deepEqual(flow.phoneResult({ code: '', errMsg: 'getPhoneNumber:fail no permission' }), { kind: 'failed', message: '获取手机号失败，请重试' })
  assert.equal(flow.phoneResult(undefined).kind, 'failed')
  assert.equal(flow.phoneResult(null).kind, 'failed')
})

test('回跳地址只接受本小程序页面，其余回首页', () => {
  const enc = encodeURIComponent
  assert.equal(flow.redirectTarget(undefined), '/pages/index/index')
  assert.equal(flow.redirectTarget(''), '/pages/index/index')
  assert.equal(flow.redirectTarget(enc('/pages/bind/bind?sn=SP2612000123')), '/pages/bind/bind?sn=SP2612000123')
  assert.equal(flow.redirectTarget(enc('/pages/invite/accept?token=a_b-C')), '/pages/invite/accept?token=a_b-C')
  for (const bad of ['https://evil.example/x', '/pages/../x', 'pages/index/index', '/other/x', '/pages/a b', '%E0%A4%A']) {
    assert.equal(flow.redirectTarget(bad.startsWith('%') ? bad : enc(bad)), '/pages/index/index', bad)
  }
  // 不回到登录页、停用页本身，避免循环
  assert.equal(flow.redirectTarget(enc('/pages/login/login?redirect=x')), '/pages/index/index')
  assert.equal(flow.redirectTarget(enc('/pages/banned/banned')), '/pages/index/index')
})

test('回跳方式：tabBar 页面 switchTab 且去掉参数，其余 redirectTo', () => {
  assert.deepEqual(flow.navigation('/pages/mine/mine'), { method: 'switchTab', url: '/pages/mine/mine' })
  assert.deepEqual(flow.navigation('/pages/index/index?x=1'), { method: 'switchTab', url: '/pages/index/index' })
  assert.deepEqual(flow.navigation('/pages/bind/bind?sn=1'), { method: 'redirectTo', url: '/pages/bind/bind?sn=1' })
})

test('登录页地址带回跳参数，往返一致', () => {
  assert.equal(flow.loginUrl(), '/pages/login/login')
  const url = flow.loginUrl('/pages/bind/bind?sn=SP1&x=a b')
  assert.equal(url, '/pages/login/login?redirect=%2Fpages%2Fbind%2Fbind%3Fsn%3DSP1%26x%3Da%20b')
  // navigateTo 的参数原样交给 onLoad（不自动解码），redirectTarget 解码一次，与 loginUrl 编码一次对应
  assert.equal(flow.redirectTarget(flow.loginUrl('/pages/bind/bind?sn=SP1').split('redirect=')[1]), '/pages/bind/bind?sn=SP1')
  // 来源地址里的参数值应已编码；未编码的空格不放行
  assert.equal(flow.redirectTarget(url.split('redirect=')[1]), flow.HOME_PAGE)
  assert.equal(flow.redirectTarget(flow.loginUrl('/pages/bind/bind?x=a%20b').split('redirect=')[1]), '/pages/bind/bind?x=a%20b')
})

test('协议：只认当前版本；占位文本必须明示', () => {
  assert.equal(flow.agreedCurrent(AGREEMENT_VERSION), true)
  assert.equal(flow.agreedCurrent('old'), false)
  assert.equal(flow.agreedCurrent(undefined), false)
  assert.equal(agreement('privacy').title, '隐私政策')
  assert.equal(agreement('user').title, '用户协议')
  assert.equal(agreement('whatever').title, '用户协议')
  // 正式文本到位前：版本号为草稿、占位标记打开，隐私政策需列出手机号、蓝牙、位置（PRD 9）
  assert.equal(AGREEMENT_IS_PLACEHOLDER, AGREEMENT_VERSION.startsWith('draft'))
  const privacy = AGREEMENTS.privacy.sections.map((s) => s.body).join('')
  for (const item of ['手机号', '蓝牙', '位置']) assert.ok(privacy.includes(item), item)
})

test('needPhone：未授权手机号需要先授权', () => {
  assert.equal(flow.needPhone({ phoneBound: true }), 'ok')
  assert.equal(flow.needPhone({ phoneBound: false }), 'need-phone')
  assert.equal(flow.needPhone(null), 'need-phone')
})

function mockClient() {
  const server = createMockServer(() => Date.parse('2026-11-28T10:00:00+08:00'))
  const store = new Map()
  return new ApiClient({
    transport: async (req) => server.handle(req),
    store: { get: (k) => store.get(k), set: (k, v) => store.set(k, v), remove: (k) => store.delete(k) },
    now: () => Date.parse('2026-11-28T10:00:00+08:00'),
    loginCode: async () => 'code',
  })
}

test('授权手机号后本地资料随之更新；失败时资料不变', async () => {
  const client = mockClient()
  const before = await client.ensureLogin()
  assert.equal(before.user.phoneBound, false)

  await assert.rejects(flow.bindPhone(client, 'fail'), (e) => e.code === ErrorCode.WX_PHONE_FAILED && e.action === 'retry')
  assert.equal(client.session().user.phoneBound, false)

  const user = await flow.bindPhone(client, '13912341234')
  assert.equal(user.phoneMasked, '139****1234')
  assert.equal(client.session().user.phoneBound, true)
  assert.equal(client.session().user.phoneMasked, '139****1234')
  assert.equal(client.session().token, before.token, '不重新登录')
  assert.equal((await client.get('/me')).phoneBound, true)
})
