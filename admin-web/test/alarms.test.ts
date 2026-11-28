// ADM-05：告警中心的筛选条件 ↔ 地址栏、接口参数、处理备注、处理失败
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  DEFAULT_FILTERS,
  NOTE_MAX,
  fromQuery,
  handleFailure,
  noteProblem,
  toParams,
  toQuery,
} from '../src/alarms/filters.ts'
import type { AlarmFilters } from '../src/alarms/filters.ts'

const f = (patch: Partial<AlarmFilters> = {}): AlarmFilters => ({ ...DEFAULT_FILTERS, ...patch })

test('默认只看未处理的；默认值不写进地址栏', () => {
  assert.deepEqual(fromQuery({}), f())
  assert.equal(fromQuery({}).status, 'OPEN')
  assert.deepEqual(toQuery(f()), {})
  assert.deepEqual(toQuery(f({ status: 'ALL' })), { status: 'ALL' })
  assert.deepEqual(
    toQuery(f({ status: 'HANDLED', type: 'OBSTRUCTED', sn: ' sp1 ', deviceId: '9', page: 3 })),
    {
      status: 'HANDLED',
      type: 'OBSTRUCTED',
      sn: 'sp1',
      deviceId: '9',
      page: '3',
    },
  )
})

test('地址栏往返一致；不认识的值回到默认', () => {
  const full = f({ status: 'HANDLED', type: 'CMD_TIMEOUT', sn: 'SP1', deviceId: '12', page: 2 })
  assert.deepEqual(fromQuery(toQuery(full)), full)
  assert.deepEqual(fromQuery({ status: 'open', type: 'FIRE', deviceId: '0', page: '1.5' }), f())
  assert.deepEqual(fromQuery({ deviceId: 'abc' }).deviceId, '')
  assert.equal(fromQuery({ status: ['ALL', 'OPEN'] }).status, 'ALL')
})

test('接口参数：“全部”不传 status；SN 转大写', () => {
  assert.deepEqual(toParams(f(), 20), { page: 1, size: 20, status: 'OPEN' })
  assert.deepEqual(toParams(f({ status: 'ALL' }), 20), { page: 1, size: 20 })
  assert.deepEqual(
    toParams(f({ status: 'HANDLED', type: 'INFRARED', sn: ' sp 1 ', deviceId: '7', page: 2 }), 50),
    {
      page: 2,
      size: 50,
      status: 'HANDLED',
      type: 'INFRARED',
      sn: 'SP1',
      deviceId: 7,
    },
  )
})

test('处理备注：去首尾空白后 1–512 个字符，按字符计', () => {
  assert.equal(NOTE_MAX, 512)
  assert.equal(noteProblem(''), '请填写处理说明')
  assert.equal(noteProblem('   '), '请填写处理说明')
  assert.equal(noteProblem('已清理'), null)
  assert.equal(noteProblem(` ${'字'.repeat(512)} `), null)
  assert.equal(noteProblem('字'.repeat(513)), '处理说明最多 512 个字')
  assert.equal(noteProblem('🚗'.repeat(512)), null, 'emoji 算 1 个字')
})

test('处理失败：已被处理或不存在时刷新，其余就地提示', () => {
  assert.equal(handleFailure(10009), 'refresh')
  assert.equal(handleFailure(10004), 'refresh')
  assert.equal(handleFailure(10001), 'show')
  assert.equal(handleFailure(-1), 'show')
})
