// ADM-02：设备入库的字段规则、批量导入的前端规则、下载文件名、时间显示
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  fieldForCode,
  luhn,
  normalizeMac,
  normalizeSn,
  validateDevice,
} from '../src/devices/rules.ts'
import type { DeviceInputForm } from '../src/devices/rules.ts'
import {
  MAX_FILE_BYTES,
  canExportFailed,
  fileProblem,
  finished,
  printBlocker,
  summary,
} from '../src/devices/imports.ts'
import type { ImportTask } from '../src/devices/imports.ts'
import { filenameFromDisposition, isJsonResponse } from '../src/api/files.ts'
import { formatDateTime } from '../src/utils/format.ts'

// 合法 IMEI（Luhn 通过），与契约示例一致
const IMEI = '861234567890127'

const form = (patch: Partial<DeviceInputForm> = {}): DeviceInputForm => ({
  sn: 'SP2612000123',
  imei: IMEI,
  bleMac: '',
  iccid: '',
  model: 'SPG-100',
  batchNo: 'B20261201',
  ...patch,
})

test('Luhn：IMEI 校验位', () => {
  assert.equal(luhn(IMEI), true)
  assert.equal(luhn('861234567890128'), false)
  assert.equal(luhn('490154203237518'), true)
  assert.equal(luhn('49015420323751x'), false)
  assert.equal(luhn(''), false)
  // 空格会被当作 0：必须先确认全是数字
  assert.equal(luhn('0 059'), false)
  // 个位翻倍大于 9 时减 9：0059 → 0+1(5*2-9)+0+9 = 10
  assert.equal(luhn('0059'), true)
})

test('SN 与 MAC 的规整与服务端一致', () => {
  assert.equal(normalizeSn(' sp26 1200 0123 '), 'SP2612000123')
  assert.equal(normalizeMac('aa:bb:cc:dd:ee:ff'), 'AA:BB:CC:DD:EE:FF')
  assert.equal(normalizeMac('AA-BB-CC-DD-EE-FF'), 'AA:BB:CC:DD:EE:FF')
  assert.equal(normalizeMac('aabbccddeeff'), 'AA:BB:CC:DD:EE:FF')
  assert.equal(normalizeMac(' AAB:BCC-DDE EFF '), 'AA:BB:CC:DD:EE:FF', '不限制分隔符位置')
  assert.equal(normalizeMac(''), null)
  assert.equal(normalizeMac('  :- '), null)
  assert.equal(normalizeMac('AA:BB:CC:DD:EE'), undefined)
  assert.equal(normalizeMac('AA:BB:CC:DD:EE:FG'), undefined)
  assert.equal(normalizeMac('AA:BB:CC:DD:EE:FF:00'), undefined)
})

test('单台录入：合法时返回规整后的请求体', () => {
  const r = validateDevice(
    form({
      sn: ' sp2612000123 ',
      imei: '8612 3456 7890 127',
      bleMac: 'aa-bb-cc-dd-ee-ff',
      iccid: ' 8986 01ab ',
      model: ' SPG-100 ',
      batchNo: ' B1 ',
    }),
  )
  assert.deepEqual(r, {
    ok: true,
    input: {
      sn: 'SP2612000123',
      imei: IMEI,
      bleMac: 'AA:BB:CC:DD:EE:FF',
      iccid: '898601AB',
      model: 'SPG-100',
      batchNo: 'B1',
    },
  })
  const minimal = validateDevice(form())
  assert.equal(minimal.ok && minimal.input.bleMac, null)
  assert.equal(minimal.ok && minimal.input.iccid, null)
})

test('单台录入：逐字段提示', () => {
  const errors = (patch: Partial<DeviceInputForm>) => {
    const r = validateDevice(form(patch))
    return r.ok ? {} : r.errors
  }
  assert.deepEqual(errors({ sn: '' }), { sn: '请输入 SN' })
  assert.deepEqual(errors({ sn: 'SP-1234567' }), { sn: 'SN 为 8–32 位字母或数字' })
  assert.deepEqual(errors({ sn: 'A'.repeat(7) }), { sn: 'SN 为 8–32 位字母或数字' })
  assert.deepEqual(errors({ sn: 'A'.repeat(32) }), {})
  assert.deepEqual(errors({ sn: 'A'.repeat(33) }), { sn: 'SN 为 8–32 位字母或数字' })
  assert.deepEqual(errors({ imei: '' }), { imei: '请输入 IMEI' })
  assert.deepEqual(errors({ imei: '86123456789012' }), { imei: 'IMEI 为 15 位数字' })
  assert.deepEqual(errors({ imei: '8612345678901270' }), { imei: 'IMEI 为 15 位数字' })
  assert.deepEqual(errors({ imei: '861234567890128' }), { imei: 'IMEI 校验位不正确，请核对' })
  assert.deepEqual(errors({ bleMac: 'xyz' }), {
    bleMac: '蓝牙 MAC 格式不正确，如 AA:BB:CC:DD:EE:FF',
  })
  assert.deepEqual(errors({ iccid: '1'.repeat(20) }), {})
  assert.deepEqual(errors({ iccid: '1'.repeat(21) }), { iccid: 'ICCID 至多 20 位字母或数字' })
  assert.deepEqual(errors({ iccid: '8986-01' }), { iccid: 'ICCID 至多 20 位字母或数字' })
  assert.deepEqual(errors({ model: '  ' }), { model: '请输入型号' })
  assert.deepEqual(errors({ model: 'M'.repeat(32) }), {})
  assert.deepEqual(errors({ model: 'M'.repeat(33) }), { model: '型号至多 32 个字符' })
  assert.deepEqual(errors({ batchNo: '' }), { batchNo: '请输入批次' })
  assert.deepEqual(errors({ batchNo: 'B'.repeat(33) }), { batchNo: '批次至多 32 个字符' })
  // 多个字段同时出错都列出
  assert.deepEqual(Object.keys(errors({ sn: '', imei: '', model: '' })).sort(), [
    'imei',
    'model',
    'sn',
  ])
})

test('服务端错误码对应字段', () => {
  assert.equal(fieldForCode(40010), 'sn')
  assert.equal(fieldForCode(40011), 'imei')
  assert.equal(fieldForCode(40012), 'imei')
  assert.equal(fieldForCode(40013), 'bleMac')
  assert.equal(fieldForCode(10001), null)
})

const task = (patch: Partial<ImportTask> = {}): ImportTask => ({
  id: 7,
  fileName: '第一批.xlsx',
  status: 'DONE',
  totalRows: 500,
  successRows: 497,
  failedRows: 3,
  errorMessage: null,
  printExportedAt: null,
  operatorName: '运维小王',
  createdAt: '2026-11-28T10:00:00+08:00',
  finishedAt: '2026-11-28T10:00:05+08:00',
  ...patch,
})

test('上传前检查：类型、大小、空文件', () => {
  assert.equal(fileProblem({ name: '第一批.xlsx', size: 1024 }), null)
  assert.equal(fileProblem({ name: 'a.CSV', size: MAX_FILE_BYTES }), null)
  assert.equal(fileProblem({ name: 'a.xls', size: 10 }), '只支持 .xlsx 或 .csv 文件，请用模板填写')
  assert.equal(
    fileProblem({ name: 'a.xlsx.exe', size: 10 }),
    '只支持 .xlsx 或 .csv 文件，请用模板填写',
  )
  assert.equal(fileProblem({ name: 'a.csv', size: 0 }), '文件是空的')
  assert.equal(
    fileProblem({ name: 'a.csv', size: MAX_FILE_BYTES + 1 }),
    '文件不能超过 5 MB（最多 5,000 行），请拆分后分批导入',
  )
})

test('任务状态：完成与失败为终态', () => {
  assert.equal(finished(task({ status: 'PENDING' })), false)
  assert.equal(finished(task({ status: 'RUNNING' })), false)
  assert.equal(finished(task({ status: 'DONE' })), true)
  assert.equal(finished(task({ status: 'FAILED' })), true)
})

test('印刷文件：完成、有成功设备、未导出过才能导出（每个任务只能一次）', () => {
  assert.equal(printBlocker(task()), null)
  assert.equal(
    printBlocker(task({ printExportedAt: '2026-11-28T11:00:00+08:00' })),
    '已导出过，激活码不可再次导出',
  )
  assert.equal(printBlocker(task({ status: 'RUNNING' })), '任务完成后才能导出')
  assert.equal(printBlocker(task({ status: 'FAILED', successRows: 0 })), '任务完成后才能导出')
  assert.equal(printBlocker(task({ successRows: 0, failedRows: 500 })), '没有成功入库的设备')
})

test('失败行：任务结束且有失败行才能导出', () => {
  assert.equal(canExportFailed(task()), true)
  assert.equal(canExportFailed(task({ failedRows: 0 })), false)
  assert.equal(canExportFailed(task({ status: 'RUNNING' })), false)
  assert.equal(canExportFailed(task({ status: 'FAILED', failedRows: 2 })), true)
})

test('结果摘要', () => {
  assert.equal(summary(task()), '共 500 行，成功 497，失败 3')
  assert.equal(summary(task({ status: 'RUNNING' })), '共 500 行，处理中…')
  assert.equal(summary(task({ status: 'PENDING', totalRows: 0 })), '处理中…')
  assert.equal(summary(task({ status: 'FAILED', errorMessage: ' 处理超时 ' })), '处理超时')
  assert.equal(
    summary(task({ status: 'FAILED', errorMessage: null })),
    '处理失败，整批未入库，请修正后重新导入',
  )
})

test('下载文件名：优先 filename*（中文），其次 filename，再次默认', () => {
  // Spring ContentDisposition.filename(name, UTF_8) 的输出
  const spring = `attachment; filename="=?UTF-8?Q?=E5=8D=B0=E5=88=B7=E6=96=87=E4=BB=B6-7.zip?="; filename*=UTF-8''%E5%8D%B0%E5%88%B7%E6%96%87%E4%BB%B6-7.zip`
  assert.equal(filenameFromDisposition(spring, 'x.zip'), '印刷文件-7.zip')
  assert.equal(
    filenameFromDisposition(`attachment; filename="template.xlsx"`, 'x'),
    'template.xlsx',
  )
  assert.equal(filenameFromDisposition(`attachment; filename=plain.csv`, 'x'), 'plain.csv')
  assert.equal(
    filenameFromDisposition(`attachment; filename="=?UTF-8?Q?=E5=8D=B0?="`, '默认.zip'),
    '默认.zip',
  )
  assert.equal(
    filenameFromDisposition(`attachment; filename*=UTF-8''%E0%A4%A; filename="ok.zip"`, 'x'),
    'ok.zip',
    '编码坏了退回 filename',
  )
  assert.equal(
    filenameFromDisposition(`attachment; filename="../../etc/passwd"`, 'x'),
    'passwd',
    '去掉路径',
  )
  assert.equal(
    filenameFromDisposition(`attachment; filename*=UTF-8''..%2F..%2Fa.zip`, 'x'),
    'a.zip',
  )
  assert.equal(filenameFromDisposition(`attachment`, '默认.xlsx'), '默认.xlsx')
  assert.equal(filenameFromDisposition(null, '默认.xlsx'), '默认.xlsx')
  assert.equal(filenameFromDisposition(`attachment; filename=""`, '默认.xlsx'), '默认.xlsx')
})

test('下载出错时响应是 JSON 信封', () => {
  assert.equal(isJsonResponse('application/json'), true)
  assert.equal(isJsonResponse('application/json;charset=UTF-8'), true)
  assert.equal(isJsonResponse('application/problem+json'), true)
  assert.equal(isJsonResponse('application/zip'), false)
  assert.equal(
    isJsonResponse('application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'),
    false,
  )
  assert.equal(isJsonResponse(undefined), false)
})

test('时间按东八区显示', () => {
  assert.equal(formatDateTime('2026-11-28T10:05:09+08:00'), '2026-11-28 10:05')
  assert.equal(formatDateTime('2026-11-28T16:30:05Z', true), '2026-11-29 00:30:05')
  assert.equal(formatDateTime(null), '—')
  assert.equal(formatDateTime(''), '—')
  assert.equal(formatDateTime('bad'), 'bad')
})
