// ADM-07：用户管理的筛选条件、状态文案覆盖契约
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  EMPTY_USER_FILTERS,
  filterProblem,
  fromQuery,
  toParams,
  toQuery,
} from '../src/users/filters.ts'
import { USER_STATUS } from '../src/devices/labels.ts'

const f = (patch: Partial<typeof EMPTY_USER_FILTERS> = {}) => ({ ...EMPTY_USER_FILTERS, ...patch })

test('地址栏 ↔ 筛选条件：往返一致，手机号去空白，不认识的状态丢掉', () => {
  assert.deepEqual(toQuery(f()), {})
  assert.deepEqual(toQuery(f({ phone: '138 0013 8000', status: 'BANNED', page: 2 })), {
    phone: '13800138000',
    status: 'BANNED',
    page: '2',
  })
  assert.deepEqual(
    fromQuery({ phone: '13800138000', status: 'BANNED', page: '2' }),
    f({ phone: '13800138000', status: 'BANNED', page: 2 }),
  )
  assert.deepEqual(fromQuery({ status: 'banned', page: '0' }), f())
  assert.deepEqual(fromQuery({ page: '1.5' }), f())
  assert.equal(fromQuery({ status: 'ACTIVE' }).status, 'ACTIVE')
})

test('接口参数与检查：手机号须完整 11 位', () => {
  assert.deepEqual(toParams(f(), 20), { page: 1, size: 20 })
  assert.deepEqual(toParams(f({ phone: ' 138 0013 8000 ', status: 'ACTIVE', page: 3 }), 50), {
    page: 3,
    size: 50,
    phone: '13800138000',
    status: 'ACTIVE',
  })
  assert.equal(filterProblem(f()), null)
  assert.equal(filterProblem(f({ phone: '138 0013 8000' })), null)
  assert.equal(filterProblem(f({ phone: '1380013800' })), '手机号需输入完整 11 位（只能精确查询）')
  assert.equal(filterProblem(f({ phone: '23800138000' })), '手机号需输入完整 11 位（只能精确查询）')
})

test('用户状态文案覆盖契约 AppUser.status', () => {
  const yaml = readFileSync(new URL('../../docs/api/openapi-admin.yaml', import.meta.url), 'utf8')
  const block = yaml.slice(yaml.indexOf('\n    AppUser:\n'), yaml.indexOf('\n    AppUserDetail:\n'))
  const m = /status:\n\s+type: string\n\s+enum: \[([^\]]+)\]/.exec(block)
  assert.ok(m, 'AppUser.status 的 enum')
  assert.deepEqual(
    Object.keys(USER_STATUS).sort(),
    m[1]
      .split(',')
      .map((s) => s.trim())
      .sort(),
  )
})
