// ADM-03：设备列表筛选 ↔ 地址栏、枚举文案覆盖契约、显示格式、日期范围
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  EMPTY_FILTERS,
  filterProblem,
  fromQuery,
  toParams,
  toQuery,
} from '../src/devices/filters.ts'
import * as L from '../src/devices/labels.ts'
import { dayRange } from '../src/utils/format.ts'

/** 契约 YAML 里某个 schema 的文本块：从 `    Name:` 到下一个同级 schema（只处理本项目契约的缩进写法） */
function schemaBlock(file: string, schema: string): string {
  const yaml = readFileSync(new URL(`../../docs/api/${file}`, import.meta.url), 'utf8')
  const start = yaml.indexOf(`\n    ${schema}:\n`)
  assert.ok(start >= 0, `契约里找不到 ${schema}`)
  const rest = yaml.slice(start + 1)
  const next = rest.slice(1).search(/\n {4}[A-Za-z]+:\n/)
  return next >= 0 ? rest.slice(0, next + 1) : rest
}

function enumAfter(block: string, name: string): string[] {
  const m = /enum: \[([^\]]+)\]/.exec(block)
  assert.ok(m, `${name} 没有 enum`)
  return m[1].split(',').map((s) => s.trim())
}

/** schema 本身的 enum */
function contractEnum(file: string, schema: string): string[] {
  return enumAfter(schemaBlock(file, schema), schema)
}

/** schema 里某个属性的 enum（属性可在 allOf 里，缩进不限） */
function propertyEnum(file: string, schema: string, prop: string): string[] {
  const block = schemaBlock(file, schema)
  const at = block.search(new RegExp(`\\n +${prop}:\\n`))
  assert.ok(at >= 0, `${schema}.${prop} 不存在`)
  return enumAfter(block.slice(at), `${schema}.${prop}`)
}

test('枚举文案覆盖契约里的每个取值', () => {
  const cases: Array<[string[], Record<string, unknown>, string]> = [
    [contractEnum('common.yaml', 'OnlineState'), L.ONLINE_STATE, 'OnlineState'],
    [contractEnum('common.yaml', 'GateStatus'), L.GATE_STATUS, 'GateStatus'],
    [contractEnum('common.yaml', 'BindStatus'), L.BIND_STATUS, 'BindStatus'],
    [contractEnum('common.yaml', 'MemberStatus'), L.MEMBER_STATUS, 'MemberStatus'],
    [contractEnum('common.yaml', 'CommandStatus'), L.COMMAND_STATUS, 'CommandStatus'],
    [contractEnum('common.yaml', 'CommandResultCode'), L.RESULT_CODE, 'CommandResultCode'],
    [contractEnum('common.yaml', 'TriggerSource'), L.TRIGGER_SOURCE, 'TriggerSource'],
    [contractEnum('common.yaml', 'AlarmType'), L.ALARM_TYPE, 'AlarmType'],
    [contractEnum('common.yaml', 'Channel'), L.CHANNEL, 'Channel'],
    [
      propertyEnum('openapi-admin.yaml', 'CommandAudit', 'action'),
      L.COMMAND_ACTION,
      'CommandAudit.action',
    ],
    [
      propertyEnum('openapi-admin.yaml', 'CommandAudit', 'role'),
      L.OPERATOR_ROLE,
      'CommandAudit.role',
    ],
    [propertyEnum('openapi-admin.yaml', 'Alarm', 'level'), L.ALARM_LEVEL, 'Alarm.level'],
    [propertyEnum('openapi-admin.yaml', 'Alarm', 'status'), L.ALARM_STATUS, 'Alarm.status'],
    [propertyEnum('openapi-admin.yaml', 'AdminDeviceDetail', 'workMode'), L.WORK_MODE, 'workMode'],
  ]
  for (const [values, map, name] of cases) {
    assert.deepEqual(Object.keys(map).sort(), [...values].sort(), name)
  }
})

test('未知或空的枚举值不报错', () => {
  assert.deepEqual(L.label(L.GATE_STATUS, 'NEW_STATE'), { text: 'NEW_STATE', type: 'info' })
  assert.deepEqual(L.label(L.GATE_STATUS, null), { text: '—', type: 'info' })
  assert.deepEqual(L.label(L.GATE_STATUS, ''), { text: '—', type: 'info' })
  assert.deepEqual(L.label(L.GATE_STATUS, 'FAULT'), { text: '电机故障', type: 'danger' })
  assert.equal(L.text(L.CHANNEL, undefined), '—')
  assert.equal(L.text(L.CHANNEL, 'NFC'), 'NFC')
  assert.equal(L.text(L.CHANNEL, 'BLE'), '蓝牙')
})

test('显示格式：电量、信号、车位、耗时', () => {
  assert.equal(L.batteryText(86), '86%')
  assert.equal(L.batteryText(0), '0%')
  assert.equal(L.batteryText(null), '—')
  assert.equal(L.signalText(31), '31（强）')
  assert.equal(L.signalText(20), '20（强）')
  assert.equal(L.signalText(19), '19（中）')
  assert.equal(L.signalText(10), '10（中）')
  assert.equal(L.signalText(9), '9（弱）')
  assert.equal(L.signalText(1), '1（弱）')
  assert.equal(L.signalText(0), '0（无）')
  assert.equal(L.signalText(99), '—')
  assert.equal(L.signalText(-1), '—')
  assert.equal(L.signalText(null), '—')
  assert.equal(L.occupiedText(true), '有车')
  assert.equal(L.occupiedText(false), '无车')
  assert.equal(L.occupiedText(null), '—')
  assert.equal(L.durationText(1240), '1.24 秒')
  assert.equal(L.durationText(1000), '1.00 秒')
  assert.equal(L.durationText(999), '999 毫秒')
  assert.equal(L.durationText(0), '0 毫秒')
  assert.equal(L.durationText(null), '—')
})

test('筛选条件 ↔ 地址栏：往返一致，空值与第 1 页不写，不认识的值丢掉', () => {
  const f = {
    sn: 'SP26',
    imei: '8612',
    phone: '13800138000',
    bindStatus: 'BOUND',
    onlineState: 'SLEEP',
    enabled: 'false',
    page: 3,
  } as const
  const q = toQuery({ ...f })
  assert.deepEqual(q, {
    sn: 'SP26',
    imei: '8612',
    phone: '13800138000',
    bindStatus: 'BOUND',
    onlineState: 'SLEEP',
    enabled: 'false',
    page: '3',
  })
  assert.deepEqual(fromQuery(q), f)
  assert.deepEqual(toQuery({ ...EMPTY_FILTERS }), {})
  assert.deepEqual(toQuery({ ...EMPTY_FILTERS, sn: '  ', page: 1 }), {})
  assert.deepEqual(fromQuery({}), EMPTY_FILTERS)
  assert.deepEqual(
    fromQuery({
      bindStatus: 'X',
      onlineState: 'online',
      enabled: 'yes',
      page: '0',
      sn: ['A1', 'B2'],
      imei: 5,
    }),
    { ...EMPTY_FILTERS, sn: 'A1' },
  )
  assert.equal(fromQuery({ page: '2.5' }).page, 1)
  assert.equal(fromQuery({ page: '-1' }).page, 1)
  assert.equal(fromQuery({ sn: '  sp1 ' }).sn, 'sp1')
})

test('筛选条件 → 接口参数：SN 转大写、去空白，启用状态为布尔，空值不传', () => {
  assert.deepEqual(toParams({ ...EMPTY_FILTERS }, 20), { page: 1, size: 20 })
  assert.deepEqual(
    toParams(
      {
        sn: ' sp 26 ',
        imei: '86 12',
        phone: '138 0013 8000',
        bindStatus: 'PENDING',
        onlineState: 'OFFLINE',
        enabled: 'true',
        page: 2,
      },
      50,
    ),
    {
      page: 2,
      size: 50,
      sn: 'SP26',
      imei: '8612',
      phone: '13800138000',
      bindStatus: 'PENDING',
      onlineState: 'OFFLINE',
      enabled: true,
    },
  )
  assert.equal(toParams({ ...EMPTY_FILTERS, enabled: 'false' }, 20).enabled, false)
})

test('筛选条件检查：SN / IMEI 前缀只收字母数字，手机号须完整 11 位', () => {
  assert.equal(filterProblem({ ...EMPTY_FILTERS }), null)
  assert.equal(filterProblem({ ...EMPTY_FILTERS, sn: 'sp 26' }), null)
  assert.equal(filterProblem({ ...EMPTY_FILTERS, sn: 'SP-26' }), 'SN 只能包含字母和数字')
  assert.equal(filterProblem({ ...EMPTY_FILTERS, imei: '86a' }), 'IMEI 只能包含数字')
  assert.equal(filterProblem({ ...EMPTY_FILTERS, phone: '1380013800' }), '手机号需输入完整 11 位')
  assert.equal(filterProblem({ ...EMPTY_FILTERS, phone: '23800138000' }), '手机号需输入完整 11 位')
  assert.equal(filterProblem({ ...EMPTY_FILTERS, phone: '138 0013 8000' }), null)
})

test('日期范围 → from（含）/ to（不含，结束日次日 0 点，东八区）', () => {
  assert.deepEqual(dayRange(['2026-11-01', '2026-11-28']), {
    from: '2026-11-01T00:00:00+08:00',
    to: '2026-11-29T00:00:00+08:00',
  })
  assert.deepEqual(dayRange(['2026-12-31', '2026-12-31']), {
    from: '2026-12-31T00:00:00+08:00',
    to: '2027-01-01T00:00:00+08:00',
  })
  assert.deepEqual(dayRange(['2028-02-28', '2028-02-28'])?.to, '2028-02-29T00:00:00+08:00', '闰年')
  assert.deepEqual(
    dayRange(['2026-11-28', '2026-11-01']),
    { from: '2026-11-01T00:00:00+08:00', to: '2026-11-29T00:00:00+08:00' },
    '反着选也行',
  )
  assert.equal(dayRange(null), null)
  assert.equal(dayRange(['', '']), null)
  assert.equal(dayRange(['2026/11/01', '2026-11-02']), null)
  // 结束日格式也要检查（扩展年份 Date.parse 能解析，但不是选择器给的格式）
  assert.equal(dayRange(['2026-11-01', '+02026-11-01']), null)
})
