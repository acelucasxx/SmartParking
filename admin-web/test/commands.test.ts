// ADM-04：指令审计的筛选条件 ↔ 地址栏、列表参数、导出检查
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  EMPTY,
  EXPORT_MAX_DAYS,
  exportParams,
  filterProblem,
  fromQuery,
  listParams,
  toQuery,
} from '../src/commands/filters.ts'
import type { CommandFilters } from '../src/commands/filters.ts'

const f = (patch: Partial<CommandFilters> = {}): CommandFilters => ({ ...EMPTY, ...patch })

test('地址栏 ↔ 筛选条件：往返一致，不认识的值丢掉，日期须成对', () => {
  const full = f({
    deviceId: '12',
    sn: 'sp1',
    phone: '13800138000',
    channel: 'BLE',
    status: 'FAILED',
    from: '2026-11-01',
    to: '2026-11-28',
    page: 2,
  })
  const q = toQuery(full)
  assert.deepEqual(q, {
    deviceId: '12',
    sn: 'sp1',
    phone: '13800138000',
    channel: 'BLE',
    status: 'FAILED',
    from: '2026-11-01',
    to: '2026-11-28',
    page: '2',
  })
  assert.deepEqual(fromQuery(q), full)
  assert.deepEqual(toQuery(f()), {})
  assert.deepEqual(fromQuery({ channel: 'NFC', status: 'done', deviceId: '0', page: 'x' }), f())
  assert.deepEqual(fromQuery({ deviceId: '-3' }), f())
  assert.deepEqual(fromQuery({ deviceId: '1.5' }), f())
  assert.deepEqual(fromQuery({ from: '2026-11-01' }), f(), '只有起没有止')
  assert.deepEqual(fromQuery({ to: '2026-11-01' }), f(), '只有止没有起')
  assert.equal(fromQuery({ page: '2.5' }).page, 1)
  assert.deepEqual(fromQuery({ from: '2026-11-01', to: '2026/11/02' }), f())
  assert.deepEqual(fromQuery({ sn: [' A1 ', 'B2'] }).sn, 'A1')
})

test('列表参数：SN 转大写，手机号去空白，日期换算为 from（含）/ to（不含）', () => {
  assert.deepEqual(listParams(f(), 20), { page: 1, size: 20 })
  assert.deepEqual(
    listParams(f({ from: '2026-11-01' }), 20),
    { page: 1, size: 20 },
    '起止缺一不传时间',
  )
  assert.deepEqual(
    listParams(
      f({
        deviceId: '12',
        sn: ' sp 1 ',
        phone: '138 0013 8000',
        channel: 'REMOTE',
        status: 'SUCCESS',
        from: '2026-11-01',
        to: '2026-11-28',
        page: 3,
      }),
      50,
    ),
    {
      deviceId: 12,
      sn: 'SP1',
      phone: '13800138000',
      channel: 'REMOTE',
      status: 'SUCCESS',
      from: '2026-11-01T00:00:00+08:00',
      to: '2026-11-29T00:00:00+08:00',
      page: 3,
      size: 50,
    },
  )
})

test('筛选检查', () => {
  assert.equal(filterProblem(f()), null)
  assert.equal(filterProblem(f({ sn: 'sp 1' })), null)
  assert.equal(filterProblem(f({ sn: 'SP-1' })), 'SN 只能包含字母和数字')
  assert.equal(filterProblem(f({ phone: '1380013800' })), '操作人手机号需输入完整 11 位')
  assert.equal(filterProblem(f({ phone: '138 0013 8000' })), null)
})

test('导出：须选时间范围、不超过 31 天（起止都含）、不支持按手机号', () => {
  assert.equal(EXPORT_MAX_DAYS, 31)
  assert.equal(exportParams(f({ from: '2026-11-01' })).ok, false, '只有起')
  assert.deepEqual(exportParams(f({ from: '2026-11-01' })), {
    ok: false,
    message: '导出须先选择时间范围（不超过 31 天）',
  })
  assert.deepEqual(exportParams(f()), {
    ok: false,
    message: '导出须先选择时间范围（不超过 31 天）',
  })
  assert.deepEqual(
    exportParams(f({ phone: '13800138000', from: '2026-11-01', to: '2026-11-02' })),
    {
      ok: false,
      message: '导出不支持按操作人手机号筛选，请清空手机号后导出',
    },
  )
  // 11-01 至 12-01 共 31 天
  assert.deepEqual(
    exportParams(f({ sn: 'sp1', channel: 'BLE', from: '2026-11-01', to: '2026-12-01' })),
    {
      ok: true,
      params: {
        sn: 'SP1',
        channel: 'BLE',
        from: '2026-11-01T00:00:00+08:00',
        to: '2026-12-02T00:00:00+08:00',
      },
    },
  )
  assert.deepEqual(exportParams(f({ from: '2026-11-01', to: '2026-12-02' })), {
    ok: false,
    message: '导出的时间范围不能超过 31 天',
  })
  assert.equal(exportParams(f({ from: '2026-11-28', to: '2026-11-28' })).ok, true, '单日')
  // 反着选也按跨度算
  assert.equal(exportParams(f({ from: '2026-12-01', to: '2026-11-01' })).ok, true)
  assert.equal(exportParams(f({ from: '2026-12-02', to: '2026-11-01' })).ok, false)
  // 导出参数不带分页
  const ok = exportParams(f({ from: '2026-11-01', to: '2026-11-02', page: 5, deviceId: '7' }))
  assert.deepEqual(ok.ok && Object.keys(ok.params).sort(), ['deviceId', 'from', 'to'])
})
