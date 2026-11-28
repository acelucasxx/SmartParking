// ADM-06：运维操作的可用性、原因、换件新值、错误对应、自检轮询
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  OPS,
  REASON_MAX,
  REASON_MIN,
  SELF_CHECK_MAX_MS,
  availableOps,
  commandFinished,
  errorField,
  op,
  partValue,
  reasonProblem,
  selfCheckError,
} from '../src/devices/ops.ts'

// 与 V10 迁移写入的角色权限点一致
const OPS_PERMS = [
  'device:disable',
  'device:replace-part',
  'device:self-check',
  'device:force-unbind',
  'device:reset-code',
]
const SUPPORT_PERMS = ['device:force-unbind', 'device:reset-code']
const can = (perms: string[]) => (p: string) => perms.includes('*') || perms.includes(p)
const keys = (list: ReturnType<typeof availableOps>) => list.map((x) => x.op.key)

test('按权限点显示：运维全部，客服只有强制解绑与重置激活码', () => {
  const bound = { bindStatus: 'BOUND' as const, enabled: true }
  assert.deepEqual(keys(availableOps(bound, can(['*']))), [
    'forceUnbind',
    'resetCode',
    'disable',
    'replacePart',
    'selfCheck',
  ])
  assert.deepEqual(keys(availableOps(bound, can(OPS_PERMS))), [
    'forceUnbind',
    'resetCode',
    'disable',
    'replacePart',
    'selfCheck',
  ])
  assert.deepEqual(keys(availableOps(bound, can(SUPPORT_PERMS))), ['forceUnbind', 'resetCode'])
  assert.deepEqual(keys(availableOps(bound, can([]))), [])
})

test('禁用 / 启用按当前状态只显示一个；未绑定的设备不能强制解绑', () => {
  assert.deepEqual(keys(availableOps({ bindStatus: 'BOUND', enabled: false }, can(['*']))), [
    'forceUnbind',
    'resetCode',
    'enable',
    'replacePart',
    'selfCheck',
  ])
  const pending = availableOps({ bindStatus: 'PENDING', enabled: true }, can(['*']))
  assert.equal(pending.find((x) => x.op.key === 'forceUnbind')?.disabledReason, '设备未绑定')
  assert.ok(
    pending.filter((x) => x.op.key !== 'forceUnbind').every((x) => x.disabledReason === null),
  )
  assert.equal(
    availableOps({ bindStatus: 'BOUND', enabled: true }, can(['*']))[0].disabledReason,
    null,
  )
})

test('除自检外都要原因；启用与自检不是危险样式', () => {
  assert.deepEqual(
    OPS.filter((o) => !o.needsReason).map((o) => o.key),
    ['selfCheck'],
  )
  assert.deepEqual(
    OPS.filter((o) => !o.danger).map((o) => o.key),
    ['enable', 'selfCheck'],
  )
  assert.equal(op('enable').permission, op('disable').permission, '启用与禁用同一权限点')
  assert.ok(op('forceUnbind').warning.includes('待激活'))
})

test('原因：去首尾空白后 2–255 个字符，按字符计', () => {
  assert.equal(REASON_MIN, 2)
  assert.equal(REASON_MAX, 255)
  assert.equal(reasonProblem(''), '请填写原因（至少 2 个字）')
  assert.equal(reasonProblem(' 申 '), '请填写原因（至少 2 个字）')
  assert.equal(reasonProblem('申诉'), null)
  assert.equal(reasonProblem('字'.repeat(255)), null)
  assert.equal(reasonProblem('字'.repeat(256)), '原因最多 255 个字')
  assert.equal(reasonProblem('🚗'.repeat(255)), null, 'emoji 算 1 个字')
})

test('换件新值：IMEI 15 位 + Luhn，MAC 规整；不能与当前相同', () => {
  const current = { imei: '861234567890127', bleMac: 'AA:BB:CC:DD:EE:FF' }
  assert.deepEqual(partValue('DTU', ' 8612 3456 0000 013 ', current), {
    ok: true,
    value: '861234560000013',
  })
  assert.deepEqual(partValue('DTU', '86123456000001', current), {
    ok: false,
    message: '新 IMEI 应为 15 位数字',
  })
  assert.deepEqual(partValue('DTU', '861234560000012', current), {
    ok: false,
    message: '新 IMEI 校验位不正确，请核对',
  })
  assert.deepEqual(partValue('DTU', '861234567890127', current), {
    ok: false,
    message: '新 IMEI 与当前相同',
  })
  assert.deepEqual(partValue('BOARD', 'aa-bb-cc-dd-ee-01', current), {
    ok: true,
    value: 'AA:BB:CC:DD:EE:01',
  })
  assert.deepEqual(partValue('BOARD', 'aabbccddeeff', current), {
    ok: false,
    message: '新 MAC 与当前相同',
  })
  assert.deepEqual(partValue('BOARD', '', current), {
    ok: false,
    message: '请填写正确的蓝牙 MAC，如 AA:BB:CC:DD:EE:FF',
  })
  assert.deepEqual(partValue('BOARD', 'xyz', current), {
    ok: false,
    message: '请填写正确的蓝牙 MAC，如 AA:BB:CC:DD:EE:FF',
  })
  assert.deepEqual(partValue('BOARD', 'AA:BB:CC:DD:EE:01', { imei: current.imei, bleMac: null }), {
    ok: true,
    value: 'AA:BB:CC:DD:EE:01',
  })
})

test('服务端错误对应输入框', () => {
  assert.equal(errorField(40020), 'reason')
  for (const c of [40011, 40012, 40013]) assert.equal(errorField(c), 'value', String(c))
  for (const c of [10001, 40021, 20003]) assert.equal(errorField(c), null, String(c))
})

test('自检：终态判定与等待上限', () => {
  for (const s of ['SUCCESS', 'FAILED', 'TIMEOUT']) assert.equal(commandFinished(s), true, s)
  for (const s of ['PENDING', 'SENT', 'EXECUTING']) assert.equal(commandFinished(s), false, s)
  assert.ok(SELF_CHECK_MAX_MS > 20_000, '要等得到服务端的超时判定（超时 + 5 秒对账）')
})

test('自检失败的提示换成运维视角', () => {
  assert.equal(
    selfCheckError(20003, '设备离线，可在现场使用蓝牙'),
    '设备离线，无法自检（最近 24 小时没有上行），请检查电池与安装位置',
  )
  assert.equal(selfCheckError(20004, 'x'), '设备休眠中，暂时无法自检，可稍后再试')
  assert.equal(selfCheckError(20005, 'x'), '设备正在执行其他指令，请稍后再试')
  assert.equal(selfCheckError(10004, '设备不存在'), '设备不存在')
})
