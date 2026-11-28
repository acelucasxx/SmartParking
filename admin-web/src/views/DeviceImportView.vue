<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type UploadRawFile } from 'element-plus'
import { download, get, post, upload } from '@/api/http'
import { AdminApiError } from '@/auth/errors'
import {
  ACCEPT,
  POLL_MS,
  STATUS_LABEL,
  type ImportTask,
  type ImportTaskDetail,
  canExportFailed,
  fileProblem,
  finished,
  printBlocker,
  summary,
} from '@/devices/imports'
import { type DeviceInputForm, type Field, fieldForCode, validateDevice } from '@/devices/rules'
import { formatDateTime } from '@/utils/format'

/**
 * 设备入库（ADM-02，PRD 5.1、7）：单台录入、模板下载、批量导入与进度、导入记录、失败行导出、印刷文件导出。
 * 规则在 devices/rules.ts、devices/imports.ts。
 */

// ------------------------------------------------------------ 导入记录

const records = ref<ImportTask[]>([])
const total = ref(0)
const page = ref(1)
const size = 10
const loadingRecords = ref(false)

async function loadRecords() {
  loadingRecords.value = true
  try {
    const data = await get<{ items: ImportTask[]; total: number }>('/device-imports', {
      page: page.value,
      size,
    })
    records.value = data.items
    total.value = data.total
  } catch {
    // 错误已由请求层提示
  } finally {
    loadingRecords.value = false
  }
}

// ------------------------------------------------------------ 批量导入与任务详情

const uploading = ref(false)
/** 正在查看的任务（刚上传的，或从记录里点开的） */
const task = ref<ImportTaskDetail | null>(null)
const taskVisible = ref(false)
let pollTimer: number | undefined

const FAILED_PREVIEW = 100
const failedPreview = computed(() => task.value?.failedRowsDetail.slice(0, FAILED_PREVIEW) ?? [])

async function beforeUpload(file: UploadRawFile) {
  const problem = fileProblem(file)
  if (problem) {
    ElMessage.warning(problem)
    return false
  }
  uploading.value = true
  try {
    const created = await upload<ImportTask>('/device-imports', file)
    ElMessage.success('已上传，正在处理')
    await openTask(created.id)
    page.value = 1
    void loadRecords()
  } catch {
    // 40014 等格式问题由请求层提示，不建任务
  } finally {
    uploading.value = false
  }
  // 已自行上传，阻止 el-upload 再发请求
  return false
}

async function openTask(id: number) {
  stopPolling()
  taskVisible.value = true
  await refreshTask(id)
}

async function refreshTask(id: number) {
  try {
    const detail = await get<ImportTaskDetail>(`/device-imports/${id}`)
    task.value = detail
    if (!finished(detail)) {
      pollTimer = window.setTimeout(() => void refreshTask(id), POLL_MS)
    } else {
      // 处理完成：记录列表里的状态与计数随之更新
      const i = records.value.findIndex((r) => r.id === id)
      if (i >= 0) records.value[i] = detail
    }
  } catch {
    // 网络抖动：稍后再试；任务不存在等错误已提示
    if (task.value && !finished(task.value) && taskVisible.value) {
      pollTimer = window.setTimeout(() => void refreshTask(id), POLL_MS * 2)
    }
  }
}

function stopPolling() {
  window.clearTimeout(pollTimer)
  pollTimer = undefined
}

function closeTask() {
  stopPolling()
  taskVisible.value = false
}

async function exportFailed(t: ImportTask) {
  try {
    await download(`/device-imports/${t.id}/failed-rows`, `失败行-${t.id}.xlsx`)
  } catch {
    // 已提示
  }
}

const exporting = ref<number | null>(null)

async function exportPrint(t: ImportTask) {
  const blocker = printBlocker(t)
  if (blocker) {
    ElMessage.warning(blocker)
    return
  }
  try {
    await ElMessageBox.confirm(
      `将导出 ${t.successRows} 台设备的 SN、二维码与激活码明文。每个任务只能导出一次，导出后系统不再保存激活码；文件遗失只能逐台重置激活码。请确认网络稳定并妥善保管文件。`,
      '导出印刷文件',
      { confirmButtonText: '确认导出', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  exporting.value = t.id
  try {
    await download(`/device-imports/${t.id}/print-file`, `印刷文件-${t.id}.zip`)
    ElMessage.success('印刷文件已导出')
  } catch (e) {
    // 40015 已导出过：刷新状态
    if (!(e instanceof AdminApiError)) ElMessage.error('导出失败')
  } finally {
    exporting.value = null
    void loadRecords()
    if (task.value?.id === t.id) void refreshTask(t.id)
  }
}

async function downloadTemplate() {
  try {
    await download('/device-imports/template', '设备导入模板.xlsx')
  } catch {
    // 已提示
  }
}

// ------------------------------------------------------------ 单台录入

const emptyForm = (): DeviceInputForm => ({
  sn: '',
  imei: '',
  bleMac: '',
  iccid: '',
  model: '',
  batchNo: '',
})
const createVisible = ref(false)
const form = reactive<DeviceInputForm>(emptyForm())
const errors = reactive<Partial<Record<Field, string>>>({})
const creating = ref(false)
/** 录入成功：激活码只在这里出现一次 */
const created = ref<{ sn: string; activateCode: string } | null>(null)

function openCreate() {
  Object.assign(form, emptyForm())
  clearErrors()
  createVisible.value = true
}

function clearErrors() {
  for (const k of Object.keys(errors) as Field[]) delete errors[k]
}

async function submitCreate() {
  if (creating.value) return
  clearErrors()
  const result = validateDevice(form)
  if (!result.ok) {
    Object.assign(errors, result.errors)
    return
  }
  creating.value = true
  try {
    const data = await post<{ device: { sn: string }; activateCode: string }>(
      '/devices',
      result.input,
      {
        silent: true,
      },
    )
    createVisible.value = false
    created.value = { sn: data.device.sn, activateCode: data.activateCode }
  } catch (e) {
    if (e instanceof AdminApiError) {
      const field = fieldForCode(e.code)
      if (field) errors[field] = e.message
      else ElMessage.error(e.message)
    }
  } finally {
    creating.value = false
  }
}

async function copyCode() {
  if (!created.value) return
  try {
    await navigator.clipboard.writeText(created.value.activateCode)
    ElMessage.success('已复制激活码')
  } catch {
    ElMessage.warning('复制失败，请手动抄写')
  }
}

onMounted(loadRecords)
onBeforeUnmount(stopPolling)
</script>

<template>
  <div class="import">
    <div class="toolbar">
      <el-button @click="downloadTemplate">下载导入模板</el-button>
      <el-button type="primary" @click="openCreate">单台录入</el-button>
    </div>

    <el-card shadow="never" header="批量导入">
      <el-upload
        drag
        :accept="ACCEPT"
        :show-file-list="false"
        :before-upload="beforeUpload"
        :disabled="uploading"
      >
        <div v-loading="uploading" class="drop">
          <div class="drop__title">将 .xlsx / .csv 文件拖到这里，或点击选择</div>
          <div class="drop__hint">
            列为 SN、IMEI、蓝牙 MAC、型号、批次（其后可以有其他列）；不超过 5 MB、5,000
            行。整批校验通过才入库，有问题的行会单独列出。
          </div>
        </div>
      </el-upload>
    </el-card>

    <el-card shadow="never" header="导入记录" class="records">
      <el-table v-loading="loadingRecords" :data="records" empty-text="还没有导入记录">
        <el-table-column prop="fileName" label="文件" min-width="200" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="STATUS_LABEL[(row as ImportTask).status].type" size="small">
              {{ STATUS_LABEL[(row as ImportTask).status].text }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="总数 / 成功 / 失败" width="150">
          <template #default="{ row }">
            {{ row.totalRows }} / {{ row.successRows }} /
            <span :class="{ danger: row.failedRows > 0 }">{{ row.failedRows }}</span>
          </template>
        </el-table-column>
        <el-table-column label="印刷文件" width="110">
          <template #default="{ row }">
            <span v-if="row.printExportedAt" class="muted">已导出</span>
            <span v-else-if="!printBlocker(row)" class="warning">待导出</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="operatorName" label="操作人" width="110" />
        <el-table-column label="导入时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openTask(row.id)">详情</el-button>
            <el-button link :disabled="!canExportFailed(row)" @click="exportFailed(row)"
              >失败行</el-button
            >
            <el-button
              link
              type="warning"
              :disabled="!!printBlocker(row)"
              :loading="exporting === row.id"
              @click="exportPrint(row)"
            >
              印刷文件
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-if="total > size"
        v-model:current-page="page"
        class="pager"
        layout="total, prev, pager, next"
        :page-size="size"
        :total="total"
        @current-change="loadRecords"
      />
    </el-card>

    <!-- 任务详情与进度 -->
    <el-drawer v-model="taskVisible" title="导入详情" size="640px" @close="closeTask">
      <template v-if="task">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="文件" :span="2">{{ task.fileName }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="STATUS_LABEL[task.status].type" size="small">{{
              STATUS_LABEL[task.status].text
            }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="操作人">{{ task.operatorName }}</el-descriptions-item>
          <el-descriptions-item label="导入时间">{{
            formatDateTime(task.createdAt)
          }}</el-descriptions-item>
          <el-descriptions-item label="完成时间">{{
            formatDateTime(task.finishedAt)
          }}</el-descriptions-item>
          <el-descriptions-item label="印刷文件" :span="2">
            {{
              task.printExportedAt ? `已于 ${formatDateTime(task.printExportedAt)} 导出` : '未导出'
            }}
          </el-descriptions-item>
        </el-descriptions>

        <el-alert
          :title="summary(task)"
          :type="
            task.status === 'FAILED'
              ? 'error'
              : task.status === 'DONE'
                ? task.failedRows
                  ? 'warning'
                  : 'success'
                : 'info'
          "
          :closable="false"
          show-icon
          class="summary"
        />
        <el-progress
          v-if="!finished(task)"
          :percentage="100"
          :indeterminate="true"
          :show-text="false"
        />

        <div class="actions">
          <el-button :disabled="!canExportFailed(task)" @click="exportFailed(task)"
            >导出失败行</el-button
          >
          <el-button
            type="warning"
            :disabled="!!printBlocker(task)"
            :loading="exporting === task.id"
            @click="exportPrint(task)"
          >
            导出印刷文件
          </el-button>
          <span v-if="printBlocker(task) && finished(task)" class="muted">{{
            printBlocker(task)
          }}</span>
        </div>

        <template v-if="task.failedRowsDetail.length">
          <div class="failed-title">
            失败行（{{ task.failedRows }}）<span
              v-if="task.failedRows > FAILED_PREVIEW"
              class="muted"
            >
              ，这里只显示前 {{ FAILED_PREVIEW }} 行，完整列表请导出失败行</span
            >
          </div>
          <el-table :data="failedPreview" size="small" max-height="360">
            <el-table-column prop="row" label="行号" width="70" />
            <el-table-column prop="sn" label="SN" width="150" show-overflow-tooltip />
            <el-table-column prop="imei" label="IMEI" width="150" />
            <el-table-column prop="reason" label="失败原因" min-width="160" show-overflow-tooltip />
          </el-table>
        </template>
      </template>
    </el-drawer>

    <!-- 单台录入 -->
    <el-dialog v-model="createVisible" title="单台录入" width="480px" :close-on-click-modal="false">
      <el-form :model="form" label-width="90px" @submit.prevent="submitCreate">
        <el-form-item label="SN" required :error="errors.sn">
          <el-input v-model="form.sn" placeholder="8–32 位字母或数字" />
        </el-form-item>
        <el-form-item label="IMEI" required :error="errors.imei">
          <el-input v-model="form.imei" placeholder="15 位数字" />
        </el-form-item>
        <el-form-item label="蓝牙 MAC" :error="errors.bleMac">
          <el-input v-model="form.bleMac" placeholder="可空，如 AA:BB:CC:DD:EE:FF" />
        </el-form-item>
        <el-form-item label="ICCID" :error="errors.iccid">
          <el-input v-model="form.iccid" placeholder="可空，至多 20 位" />
        </el-form-item>
        <el-form-item label="型号" required :error="errors.model">
          <el-input v-model="form.model" placeholder="如 SPG-100" />
        </el-form-item>
        <el-form-item label="批次" required :error="errors.batchNo">
          <el-input v-model="form.batchNo" placeholder="如 B20261201" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">录入</el-button>
      </template>
    </el-dialog>

    <!-- 录入成功：激活码只显示一次 -->
    <el-dialog
      :model-value="!!created"
      title="录入成功"
      width="440px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      @close="created = null"
    >
      <template v-if="created">
        <el-alert
          title="激活码只显示这一次，关闭后无法再查看，请立即抄写或复制。"
          type="warning"
          :closable="false"
          show-icon
        />
        <div class="code">
          <div class="muted">SN：{{ created.sn }}</div>
          <div class="code__value">{{ created.activateCode }}</div>
        </div>
      </template>
      <template #footer>
        <el-button @click="copyCode">复制激活码</el-button>
        <el-button type="primary" @click="created = null">我已记录</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-bottom: 16px;
}
.drop {
  padding: 8px 0;
}
.drop__title {
  font-size: 15px;
}
.drop__hint {
  margin-top: 8px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}
.records {
  margin-top: 16px;
}
.pager {
  justify-content: flex-end;
  margin-top: 12px;
}
.summary {
  margin: 16px 0 8px;
}
.actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 16px 0;
}
.failed-title {
  margin: 8px 0;
  font-weight: 500;
}
.code {
  margin-top: 16px;
  text-align: center;
}
.code__value {
  margin-top: 8px;
  font-family: Menlo, Consolas, monospace;
  font-size: 32px;
  font-weight: 600;
  letter-spacing: 6px;
}
.muted {
  color: var(--el-text-color-secondary);
}
.warning {
  color: var(--el-color-warning);
}
.danger {
  color: var(--el-color-danger);
}
</style>
