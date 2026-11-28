// ADM-01：登录失败与锁定提示、会话失效判定、会话超时、新密码规则
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  AdminApiError,
  AdminCode,
  MUST_CHANGE_PASSWORD_MESSAGE,
  errorEffect,
  loginFailure,
  unwrap,
} from '../src/auth/errors.ts'
import { IDLE_MS, MAX_MS, WARN_BEFORE_MS, expiresAt, sessionPhase } from '../src/auth/idle.ts'
import { PASSWORD_MAX, PASSWORD_MIN, passwordProblem } from '../src/auth/password.ts'

function failed(status: number, body: unknown): AdminApiError {
  const r = unwrap(status, body)
  assert.equal(r.ok, false)
  return (r as { error: AdminApiError }).error
}

test('解信封：code 0 即成功，data 缺省为 null；其余统一成错误并保留服务端提示', () => {
  assert.deepEqual(unwrap(200, { code: 0, message: 'ok', data: { a: 1 } }), {
    ok: true,
    data: { a: 1 },
  })
  assert.deepEqual(unwrap(200, { code: 0, message: 'ok' }), { ok: true, data: null })

  const wrong = failed(401, { code: 40001, message: '用户名或密码错误，还可尝试 3 次' })
  assert.deepEqual(
    [wrong.code, wrong.message, wrong.status],
    [AdminCode.LOGIN_FAILED, '用户名或密码错误，还可尝试 3 次', 401],
  )
  assert.equal(failed(403, { code: 40002, message: '  ' }).message, '操作失败，请稍后重试')
  // 网络失败、非信封
  assert.deepEqual(
    [failed(0, undefined).code, failed(0, undefined).message],
    [AdminCode.NETWORK, '网络异常，请稍后重试'],
  )
  assert.equal(failed(0, { code: 0 }).code, AdminCode.NETWORK, '没有响应就不算成功')
  assert.equal(failed(502, '<html>').code, AdminCode.BAD_RESPONSE)
  assert.equal(failed(200, { code: '0' }).code, AdminCode.BAD_RESPONSE)
  assert.equal(failed(200, null).code, AdminCode.BAD_RESPONSE)
  // 网关的非信封 401 视为登录失效
  const gateway = failed(401, '<html>401</html>')
  assert.deepEqual(
    [gateway.code, gateway.message],
    [AdminCode.UNAUTHORIZED, '登录已过期，请重新登录'],
  )
})

test('全局处理：只有 10002 是会话失效；40001 虽然也是 HTTP 401 但只是密码错误', () => {
  assert.equal(errorEffect(failed(401, { code: 10002, message: '未登录或登录已过期' })), 'expired')
  assert.equal(errorEffect(failed(401, { code: 40001, message: '用户名或密码错误' })), 'show')
  assert.equal(
    errorEffect(failed(403, { code: 10003, message: MUST_CHANGE_PASSWORD_MESSAGE })),
    'change-password',
  )
  assert.equal(errorEffect(failed(403, { code: 10003, message: '无权执行该操作' })), 'show')
  assert.equal(
    errorEffect(failed(403, { code: 40002, message: MUST_CHANGE_PASSWORD_MESSAGE })),
    'show',
  )
  assert.equal(errorEffect(failed(0, undefined)), 'show')
})

test('登录失败的展示：锁定、停用、密码错误（带剩余次数）、其他', () => {
  assert.deepEqual(
    loginFailure(failed(403, { code: 40002, message: '账号已锁定，请 15 分钟后再试' })),
    {
      kind: 'locked',
      message: '账号已锁定，请 15 分钟后再试',
    },
  )
  assert.equal(loginFailure(failed(403, { code: 40003, message: '账号已停用' })).kind, 'disabled')
  assert.deepEqual(
    loginFailure(failed(401, { code: 40001, message: '用户名或密码错误，还可尝试 4 次' })),
    {
      kind: 'wrong',
      message: '用户名或密码错误，还可尝试 4 次',
    },
  )
  assert.equal(loginFailure(failed(400, { code: 10001, message: '请求参数不合法' })).kind, 'other')
  assert.equal(loginFailure(failed(0, undefined)).kind, 'other')
})

test('会话超时：空闲 30 分钟、自登录起 12 小时，取早者；到期前 2 分钟提醒', () => {
  const login = 1_000_000
  assert.equal(IDLE_MS, 30 * 60_000)
  assert.equal(MAX_MS, 12 * 3_600_000)
  assert.equal(expiresAt({ loginAt: login, lastActivityAt: login }), login + IDLE_MS)
  // 一直在操作：顺延也不超过 12 小时
  assert.equal(
    expiresAt({ loginAt: login, lastActivityAt: login + MAX_MS - 60_000 }),
    login + MAX_MS,
  )

  const t = { loginAt: login, lastActivityAt: login + 60_000 }
  const end = login + 60_000 + IDLE_MS
  assert.equal(sessionPhase(t, end - WARN_BEFORE_MS - 1), 'active')
  assert.equal(sessionPhase(t, end - WARN_BEFORE_MS), 'expiring')
  assert.equal(sessionPhase(t, end - 1), 'expiring')
  assert.equal(sessionPhase(t, end), 'expired')
  assert.equal(
    sessionPhase({ loginAt: login, lastActivityAt: login + MAX_MS }, login + MAX_MS),
    'expired',
  )
})

test('新密码规则与服务端一致', () => {
  const ok = 'abcdef1234'
  assert.equal(passwordProblem(ok, 'old', ok), null)
  assert.equal(passwordProblem('', 'old', ''), '请输入新密码')
  assert.equal(
    passwordProblem('abcdef123', 'old', 'abcdef123'),
    `新密码长度需为 ${PASSWORD_MIN}–${PASSWORD_MAX} 位`,
  )
  const longest = 'a1'.repeat(32)
  assert.equal(passwordProblem(longest, 'old', longest), null)
  assert.equal(
    passwordProblem(longest + 'b', 'old', longest + 'b'),
    `新密码长度需为 ${PASSWORD_MIN}–${PASSWORD_MAX} 位`,
  )
  for (const bad of ['abcde 12345', 'abcdef1234中', 'abcdef1234\t']) {
    assert.equal(
      passwordProblem(bad, 'old', bad),
      '新密码只能包含字母、数字和英文符号，不能有空格',
      JSON.stringify(bad),
    )
  }
  assert.equal(passwordProblem('a!b@c#d$e%1', 'old', 'a!b@c#d$e%1'), null, '英文符号可以')
  assert.equal(passwordProblem('abcdefghij', 'old', 'abcdefghij'), '新密码需同时包含字母和数字')
  assert.equal(passwordProblem('1234567890', 'old', '1234567890'), '新密码需同时包含字母和数字')
  assert.equal(passwordProblem(ok, ok, ok), '新密码不能与原密码相同')
  assert.equal(passwordProblem(ok, 'old', 'abcdef12345'), '两次输入的新密码不一致')
})
