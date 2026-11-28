/**
 * 设备入库的字段规则，与服务端一致（docs/biz/device-import.md“字段规则”）。前端先校验，提示更及时；服务端仍会校验。
 * 不依赖 Vue，可在 Node 里直接单测（test/devices.test.ts）。
 */

export interface DeviceInputForm {
  sn: string
  imei: string
  bleMac: string
  iccid: string
  model: string
  batchNo: string
}

/** 提交给 POST /devices 的请求体（契约 DeviceInput） */
export interface DeviceInput {
  sn: string
  imei: string
  bleMac: string | null
  iccid: string | null
  model: string
  batchNo: string
}

export type Field = keyof DeviceInputForm

/** SN：去空白、转大写后为 8–32 位大写字母或数字 */
export function normalizeSn(raw: string): string {
  return raw.replace(/\s+/g, '').toUpperCase()
}

/** Luhn 校验（IMEI 第 15 位为校验位） */
export function luhn(digits: string): boolean {
  if (!/^\d+$/.test(digits)) return false
  let sum = 0
  for (let i = 0; i < digits.length; i++) {
    let d = Number(digits[digits.length - 1 - i])
    if (i % 2 === 1) {
      d *= 2
      if (d > 9) d -= 9
    }
    sum += d
  }
  return sum % 10 === 0
}

/**
 * 蓝牙 MAC：接受 `AA:BB:…`、`aa-bb-…` 或不分隔，统一为 `AA:BB:CC:DD:EE:FF`；空为 null；不合法返回 undefined。
 */
export function normalizeMac(raw: string): string | null | undefined {
  // 与服务端一致：去空白与 : -，剩下 12 位十六进制即可（不限制分隔符位置）
  const hex = raw.replace(/[\s:-]/g, '').toUpperCase()
  if (!hex) return null
  if (!/^[0-9A-F]{12}$/.test(hex)) return undefined
  return hex.match(/.{2}/g)!.join(':')
}

const TEXT_MAX = 32

/**
 * 校验单台录入表单。
 * @returns 合法时 `{ ok: true, input }`（已规范化）；否则每个字段的错误提示
 */
export function validateDevice(
  form: DeviceInputForm,
): { ok: true; input: DeviceInput } | { ok: false; errors: Partial<Record<Field, string>> } {
  const errors: Partial<Record<Field, string>> = {}
  const sn = normalizeSn(form.sn)
  if (!sn) errors.sn = '请输入 SN'
  else if (!/^[A-Z0-9]{8,32}$/.test(sn)) errors.sn = 'SN 为 8–32 位字母或数字'

  const imei = form.imei.replace(/\s+/g, '')
  if (!imei) errors.imei = '请输入 IMEI'
  else if (!/^\d{15}$/.test(imei)) errors.imei = 'IMEI 为 15 位数字'
  else if (!luhn(imei)) errors.imei = 'IMEI 校验位不正确，请核对'

  const bleMac = normalizeMac(form.bleMac)
  if (bleMac === undefined) errors.bleMac = '蓝牙 MAC 格式不正确，如 AA:BB:CC:DD:EE:FF'

  const iccid = form.iccid.replace(/\s+/g, '').toUpperCase()
  if (iccid && !/^[0-9A-Z]{1,20}$/.test(iccid)) errors.iccid = 'ICCID 至多 20 位字母或数字'

  const model = form.model.trim()
  if (!model) errors.model = '请输入型号'
  else if (model.length > TEXT_MAX) errors.model = `型号至多 ${TEXT_MAX} 个字符`

  const batchNo = form.batchNo.trim()
  if (!batchNo) errors.batchNo = '请输入批次'
  else if (batchNo.length > TEXT_MAX) errors.batchNo = `批次至多 ${TEXT_MAX} 个字符`

  if (Object.keys(errors).length > 0) return { ok: false, errors }
  return {
    ok: true,
    input: { sn, imei, bleMac: bleMac ?? null, iccid: iccid || null, model, batchNo },
  }
}

/** 服务端返回的错误码对应哪个字段（40010 SN 重复、40011 IMEI 不合法、40012 IMEI 重复、40013 MAC 重复） */
export function fieldForCode(code: number): Field | null {
  switch (code) {
    case 40010:
      return 'sn'
    case 40011:
    case 40012:
      return 'imei'
    case 40013:
      return 'bleMac'
    default:
      return null
  }
}
