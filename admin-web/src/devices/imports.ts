/**
 * 批量导入（docs/biz/device-import.md“批量导入”“印刷文件”）的前端规则：上传前检查、任务状态、可导出什么。
 * 不依赖 Vue，可在 Node 里直接单测。
 */

export type ImportStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED'

/** 契约 ImportTask */
export interface ImportTask {
  id: number
  fileName: string
  status: ImportStatus
  totalRows: number
  successRows: number
  failedRows: number
  errorMessage?: string | null
  /** 非空表示印刷文件已导出，不可再导出 */
  printExportedAt?: string | null
  operatorName: string
  createdAt: string
  finishedAt?: string | null
}

export interface FailedRow {
  /** 文件中的行号（含表头） */
  row: number
  sn?: string | null
  imei?: string | null
  bleMac?: string | null
  reason: string
}

export interface ImportTaskDetail extends ImportTask {
  failedRowsDetail: FailedRow[]
}

export const MAX_FILE_BYTES = 5 * 1024 * 1024
export const ACCEPT = '.xlsx,.csv'

/** 上传前检查：只收 .xlsx / .csv，≤ 5 MB，不能为空。行数与表头由服务端校验（40014） */
export function fileProblem(file: { name: string; size: number }): string | null {
  if (!/\.(xlsx|csv)$/i.test(file.name)) return '只支持 .xlsx 或 .csv 文件，请用模板填写'
  if (file.size <= 0) return '文件是空的'
  if (file.size > MAX_FILE_BYTES) return '文件不能超过 5 MB（最多 5,000 行），请拆分后分批导入'
  return null
}

export const STATUS_LABEL: Record<
  ImportStatus,
  { text: string; type: 'info' | 'warning' | 'success' | 'danger' }
> = {
  PENDING: { text: '排队中', type: 'info' },
  RUNNING: { text: '处理中', type: 'warning' },
  DONE: { text: '已完成', type: 'success' },
  FAILED: { text: '失败', type: 'danger' },
}

export function finished(task: Pick<ImportTask, 'status'>): boolean {
  return task.status === 'DONE' || task.status === 'FAILED'
}

/** 处理中每 2 秒查一次任务详情 */
export const POLL_MS = 2000

/**
 * 印刷文件能否导出：任务完成、有成功入库的设备、还没导出过（每个任务只能导出一次）。
 * 返回不能导出的原因，可以导出为 null。
 */
export function printBlocker(task: ImportTask): string | null {
  if (task.printExportedAt) return '已导出过，激活码不可再次导出'
  if (task.status !== 'DONE') return '任务完成后才能导出'
  if (task.successRows <= 0) return '没有成功入库的设备'
  return null
}

/** 有失败行才能导出失败行文件 */
export function canExportFailed(task: ImportTask): boolean {
  return finished(task) && task.failedRows > 0
}

/** 结果摘要：共 500 行，成功 497，失败 3 */
export function summary(task: ImportTask): string {
  if (task.status === 'FAILED')
    return task.errorMessage?.trim() || '处理失败，整批未入库，请修正后重新导入'
  if (!finished(task)) return task.totalRows > 0 ? `共 ${task.totalRows} 行，处理中…` : '处理中…'
  return `共 ${task.totalRows} 行，成功 ${task.successRows}，失败 ${task.failedRows}`
}
