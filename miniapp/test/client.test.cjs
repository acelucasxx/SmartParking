// 请求核心：会话、单飞登录、登录失效重登、信封解析、PATCH 改写
const test = require('node:test')
const assert = require('node:assert/strict')
const { ApiClient, SESSION_KEY, EXPIRY_MARGIN_MS, METHOD_OVERRIDE_HEADER, buildQuery } = require('../.test-build/api/client')
const { ApiError, ErrorCode } = require('../.test-build/api/errors')

const T0 = Date.parse('2026-11-28T10:00:00+08:00')

function memoryStore() {
  const map = new Map()
  return { map, get: (k) => map.get(k), set: (k, v) => map.set(k, v), remove: (k) => map.delete(k) }
}

const ok = (data) => ({ status: 200, body: { code: 0, message: '成功', data } })
const err = (code, status = 400, message) => ({ status, body: { code, message } })

/** 可编程的传输层：按顺序记录请求，handler 决定响应 */
function setup(handler, { now = () => T0, loginCodes } = {}) {
  const calls = []
  let logins = 0
  const store = memoryStore()
  let banned = 0
  const transport = async (req) => {
    calls.push(req)
    if (req.path === '/auth/login') {
      logins++
      return ok({ token: `t${logins}`, expiresAt: new Date(now() + 3_600_000).toISOString(), user: { id: 1, nickname: 'n', phoneBound: true } })
    }
    return handler(req, calls)
  }
  const client = new ApiClient({
    transport,
    store,
    now,
    loginCode: loginCodes ?? (async () => 'code'),
    onBanned: () => banned++,
  })
  return { client, calls, store, logins: () => logins, banned: () => banned }
}

test('并发请求只登录一次，都带上同一个 token', async () => {
  const { client, calls, logins } = setup(async (req) => ok(req.path))
  const results = await Promise.all([client.get('/a'), client.get('/b'), client.post('/c', { x: 1 })])
  assert.deepEqual(results, ['/a', '/b', '/c'])
  assert.equal(logins(), 1)
  const business = calls.filter((c) => c.path !== '/auth/login')
  assert.ok(business.every((c) => c.headers.Authorization === 'Bearer t1'))
  assert.deepEqual(calls.find((c) => c.path === '/auth/login').data, { code: 'code' })
  assert.equal(calls.find((c) => c.path === '/auth/login').headers.Authorization, undefined)
})

test('会话快到期（不足 60 秒）时提前重新登录', async () => {
  let now = T0
  const { client, store, logins } = setup(async () => ok(1), { now: () => now })
  await client.get('/a')
  const expiresAt = store.get(SESSION_KEY).expiresAt
  now = expiresAt - EXPIRY_MARGIN_MS - 1
  await client.get('/a')
  assert.equal(logins(), 1, '到期前 60 秒以外仍沿用')
  now = expiresAt - EXPIRY_MARGIN_MS
  await client.get('/a')
  assert.equal(logins(), 2, '进入 60 秒余量即重新登录')
  assert.equal(client.session().token, 't2')
})

test('登录失效（10002）时重新登录并重试一次，再失败就抛出', async () => {
  const seen = []
  const { client, logins } = setup(async (req) => {
    seen.push(req.headers.Authorization)
    return req.headers.Authorization === 'Bearer t1' ? err(ErrorCode.UNAUTHORIZED, 401) : ok('fresh')
  })
  assert.equal(await client.get('/x'), 'fresh')
  assert.deepEqual(seen, ['Bearer t1', 'Bearer t2'])
  assert.equal(logins(), 2)

  const always = setup(async () => err(ErrorCode.UNAUTHORIZED, 401))
  await assert.rejects(always.client.get('/x'), (e) => e instanceof ApiError && e.code === ErrorCode.UNAUTHORIZED)
  assert.equal(always.logins(), 2, '只重登一次，不死循环')
})

test('登录失效后重登也失败：旧会话已清掉，不再拿旧 token 重试', async () => {
  let loginOk = true
  const calls = []
  const store = memoryStore()
  const client = new ApiClient({
    transport: async (req) => {
      calls.push(req)
      if (req.path === '/auth/login') {
        if (!loginOk) throw new Error('offline')
        return ok({ token: 'old', expiresAt: new Date(T0 + 3_600_000).toISOString(), user: { id: 1, nickname: 'n', phoneBound: true } })
      }
      return err(ErrorCode.UNAUTHORIZED, 401)
    },
    store,
    now: () => T0,
    loginCode: async () => 'c',
  })
  await client.ensureLogin()
  loginOk = false
  await assert.rejects(client.get('/x'), (e) => e.code === ErrorCode.NETWORK)
  assert.equal(store.get(SESSION_KEY), undefined)
  assert.equal(client.session(), null)
})

test('非信封响应：401 视为登录失效，其余为服务异常；网络失败为网络异常', async () => {
  const gateway = setup(async (req) =>
    req.headers.Authorization === 'Bearer t1' ? { status: 401, body: '<html>' } : ok('after'),
  )
  assert.equal(await gateway.client.get('/x'), 'after')

  const broken = setup(async () => ({ status: 502, body: 'Bad Gateway' }))
  await assert.rejects(broken.client.get('/x'), (e) => e.code === ErrorCode.BAD_RESPONSE && e.status === 502 && e.action === 'retry')

  const offline = setup(async () => {
    throw new Error('request:fail timeout')
  })
  await assert.rejects(offline.client.get('/x'), (e) => e.code === ErrorCode.NETWORK && e.message === '网络异常，请检查网络后重试')
})

test('业务错误带服务端提示与处理方式；没有提示时用默认文案', async () => {
  const { client } = setup(async (req) =>
    req.path === '/a' ? err(ErrorCode.ACTIVATE_CODE_WRONG, 400, '激活码错误，还可尝试 3 次') : err(ErrorCode.DEVICE_OFFLINE, 409),
  )
  await assert.rejects(client.post('/a'), (e) => e.code === 30001 && e.message === '激活码错误，还可尝试 3 次' && e.action === 'show')
  await assert.rejects(client.post('/b'), (e) => e.message === '设备离线，可在现场使用蓝牙' && e.status === 409)
})

test('账号停用（10010）回调 onBanned', async () => {
  const { client, banned } = setup(async () => err(ErrorCode.USER_BANNED, 403))
  await assert.rejects(client.get('/devices'), (e) => e.action === 'banned')
  assert.equal(banned(), 1)
})

test('登录本身被拒（停用、微信失败）也会抛出；wx.login 失败为微信登录失败', async () => {
  let banned = 0
  const store = memoryStore()
  const client = new ApiClient({
    transport: async () => err(ErrorCode.USER_BANNED, 403),
    store,
    now: () => T0,
    loginCode: async () => 'c',
    onBanned: () => banned++,
  })
  await assert.rejects(client.get('/x'), (e) => e.code === ErrorCode.USER_BANNED)
  assert.equal(banned, 1)
  assert.equal(store.get(SESSION_KEY), undefined)

  const noCode = setup(async () => ok(1), { loginCodes: async () => { throw new Error('login:fail') } })
  await assert.rejects(noCode.client.get('/x'), (e) => e.code === ErrorCode.WX_LOGIN_FAILED && e.action === 'retry')
  assert.equal(noCode.calls.length, 0)
})

test('PATCH 改写成 POST + X-HTTP-Method-Override；其他方法原样', async () => {
  const { client, calls } = setup(async () => ok(null))
  await client.patch('/devices/1', { name: 'a' })
  await client.delete('/devices/1/membership')
  await client.request('PUT', '/x', { data: { a: 1 }, query: { page: 2 } })
  const [patch, del, put] = calls.filter((c) => c.path !== '/auth/login')
  assert.equal(patch.method, 'POST')
  assert.equal(patch.headers[METHOD_OVERRIDE_HEADER], 'PATCH')
  assert.deepEqual(patch.data, { name: 'a' })
  assert.equal(del.method, 'DELETE')
  assert.equal(del.headers[METHOD_OVERRIDE_HEADER], undefined)
  assert.equal(put.method, 'PUT')
  assert.deepEqual(put.query, { page: 2 })
})

test('auth: false 不登录、不带 token；data 缺省返回 null', async () => {
  const { client, calls, logins } = setup(async () => ({ status: 200, body: { code: 0, message: '成功' } }))
  assert.equal(await client.request('GET', '/bind/SP1', { auth: false }), null)
  assert.equal(logins(), 0)
  assert.equal(calls[0].headers.Authorization, undefined)
})

test('退出：通知服务端后清掉会话；网络不通也照样本地退出', async () => {
  const { client, calls, store } = setup(async () => ok(null))
  await client.ensureLogin()
  await client.logout()
  assert.equal(store.get(SESSION_KEY), undefined)
  const logout = calls.find((c) => c.path === '/auth/logout')
  assert.equal(logout.method, 'POST')
  assert.equal(logout.headers.Authorization, 'Bearer t1')

  const offline = setup(async (req) => {
    if (req.path === '/auth/logout') throw new Error('fail')
    return ok(null)
  })
  await offline.client.ensureLogin()
  await offline.client.logout()
  assert.equal(offline.store.get(SESSION_KEY), undefined)

  const never = setup(async () => ok(null))
  await never.client.logout()
  assert.equal(never.calls.length, 0, '没登录过就不请求')
})

test('updateUser 只更新资料，保留 token', async () => {
  const { client } = setup(async () => ok(null))
  await client.ensureLogin()
  client.updateUser({ id: 1, nickname: 'n', phoneBound: true, phoneMasked: '138****0000' })
  assert.equal(client.session().token, 't1')
  assert.equal(client.session().user.phoneMasked, '138****0000')
})

test('损坏的本地会话视为未登录', async () => {
  const { client, store } = setup(async () => ok(null))
  for (const bad of [null, 'x', { token: '' , expiresAt: T0 + 1e9 }, { token: 't', expiresAt: 'soon' }]) {
    store.set(SESSION_KEY, bad)
    assert.equal(client.session(), null)
  }
})

test('buildQuery 跳过空值并编码', () => {
  assert.equal(buildQuery(), '')
  assert.equal(buildQuery({ a: null, b: undefined }), '')
  assert.equal(buildQuery({ date: '2026-11-28', page: 1, all: true, q: '车 位&' }), '?date=2026-11-28&page=1&all=true&q=%E8%BD%A6%20%E4%BD%8D%26')
})
