<script setup lang="ts">
import { computed, onBeforeUnmount, reactive } from 'vue'
import { ElMessage } from 'element-plus'
import { get, post } from '@/api/http'
import { AdminApiError } from '@/auth/errors'
import {
  type OpDef,
  type OpKey,
  type Part,
  REASON_MAX,
  SELF_CHECK_MAX_MS,
  SELF_CHECK_POLL_MS,
  availableOps,
  commandFinished,
  errorField,
  op,
  partValue,
  reasonProblem,
  selfCheckError,
} from '@/devices/ops'
import { COMMAND_STATUS, RESULT_CODE, durationText, label, text } from '@/devices/labels'
import type { AdminDeviceDetail, CommandAudit } from '@/devices/types'
import LabelTag from '@/components/LabelTag.vue'
import { useAuthStore } from '@/stores/auth'

/**
 * 设备详情页的运维操作（ADM-06）：强制解绑、重置激活码、禁用 / 启用、换件、设备自检。
 * 按权限点显示；高危操作须填原因（写入操作日志）。操作成功后通知父页面刷新。
 */
const props = defineProps<{ device: AdminDeviceDetail }>()
const emit = defineEmits<{ changed: [] }>()
const auth = useAuthStore()

const ops = computed(() => availableOps(props.device, (p) => auth.can(p)))

const PATHS: Record<Exclude<OpKey, 'selfCheck'>, string> = {
  forceUnbind: 'force-unbind',
  resetCode: 'reset-activate-code',
  disable: 'disable',
  enable: 'enable',
  replacePart: 'replace-part',
}

// ------------------------------------------------------------ 需要原因的操作

const form = reactive({
  show: false,
  op: null as OpDef | null,
  reason: '',
  resetCode: false,
  part: 'DTU' as Part,
  value: '',
  reasonError: '',
  valueError: '',
  error: '',
  submitting: false,
})

/** 新激活码：只显示这一次 */
const code = reactive({ show: false, sn: '', value: '' })

function open(o: OpDef) {
  if (o.key === 'selfCheck') {
    openSelfCheck()
    return
  }
  Object.assign(form, {
    show: true,
    op: o,
    reason: '',
    resetCode: false,
    part: 'DTU',
    value: '',
    reasonError: '',
    valueError: '',
    error: '',
    submitting: false,
  })
}

async function submit() {
  const o = form.op
  if (!o || o.key === 'selfCheck' || form.submitting) return
  form.reasonError = reasonProblem(form.reason) ?? ''
  form.valueError = ''
  form.error = ''
  const body: Record<string, unknown> = { reason: form.reason.trim() }
  if (o.key === 'forceUnbind') body.resetActivateCode = form.resetCode
  if (o.key === 'replacePart') {
    const v = partValue(form.part, form.value, props.device)
    if (!v.ok) form.valueError = v.message
    else Object.assign(body, { part: form.part, newValue: v.value })
  }
  if (form.reasonError || form.valueError) return
  form.submitting = true
  try {
    const data = await post<{ activateCode?: string | null } | null>(
      `/devices/${props.device.id}/${PATHS[o.key]}`,
      body,
      {
        silent: true,
      },
    )
    form.show = false
    ElMessage.success(`${o.label}成功`)
    if (data && typeof data === 'object' && 'activateCode' in data && data.activateCode) {
      Object.assign(code, { show: true, sn: props.device.sn, value: data.activateCode })
    }
    emit('changed')
  } catch (e) {
    if (!(e instanceof AdminApiError)) {
      form.error = `${o.label}失败，请重试`
      return
    }
    const field = errorField(e.code)
    if (field === 'reason') form.reasonError = e.message
    else if (field === 'value') form.valueError = e.message
    else form.error = e.message
    // 设备状态已变（如已被别人解绑）：刷新详情
    if (e.code === 40021 || e.code === 10004) emit('changed')
  } finally {
    form.submitting = false
  }
}

async function copyCode() {
  try {
    await navigator.clipboard.writeText(code.value)
    ElMessage.success('已复制激活码')
  } catch {
    ElMessage.warning('复制失败，请手动抄写')
  }
}

// ------------------------------------------------------------ 设备自检

const check = reactive({
  show: false,
  action: 'QUERY_STATUS' as 'QUERY_STATUS' | 'QUERY_VERSION',
  running: false,
  command: null as CommandAudit | null,
  error: '',
  timedOut: false,
})
let timer: number | undefined
let startedAt = 0

function openSelfCheck() {
  stopPolling()
  Object.assign(check, {
    show: true,
    action: 'QUERY_STATUS',
    running: false,
    command: null,
    error: '',
    timedOut: false,
  })
}

async function startSelfCheck() {
  if (check.running) return
  Object.assign(check, { running: true, command: null, error: '', timedOut: false })
  try {
    check.command = await post<CommandAudit>(
      `/devices/${props.device.id}/self-check`,
      { action: check.action },
      { silent: true },
    )
    startedAt = Date.now()
    poll()
  } catch (e) {
    // 离线 20003、休眠 20004、有未完成的指令 20005
    check.error = e instanceof AdminApiError ? selfCheckError(e.code, e.message) : '自检下发失败'
    check.running = false
  }
}

function poll() {
  if (!check.command) return
  if (commandFinished(check.command.status)) {
    check.running = false
    if (check.action === 'QUERY_VERSION' && check.command.status === 'SUCCESS') emit('changed')
    return
  }
  if (Date.now() - startedAt >= SELF_CHECK_MAX_MS) {
    check.running = false
    check.timedOut = true
    return
  }
  timer = window.setTimeout(async () => {
    try {
      check.command = await get<CommandAudit>(`/commands/${check.command!.id}`, undefined, {
        silent: true,
      })
    } catch {
      // 网络抖动：下一轮再查
    }
    if (check.show) poll()
  }, SELF_CHECK_POLL_MS)
}

function stopPolling() {
  window.clearTimeout(timer)
  timer = undefined
}

function closeSelfCheck() {
  stopPolling()
  check.show = false
  check.running = false
}

onBeforeUnmount(stopPolling)
</script>

<template>
  <div v-if="ops.length" class="ops">
    <template v-for="item in ops" :key="item.op.key">
      <el-tooltip
        :disabled="!item.disabledReason"
        :content="item.disabledReason ?? ''"
        placement="bottom"
      >
        <span>
          <el-button
            size="small"
            :type="item.op.danger ? 'danger' : 'default'"
            plain
            :disabled="!!item.disabledReason"
            @click="open(item.op)"
          >
            {{ item.op.label }}
          </el-button>
        </span>
      </el-tooltip>
    </template>

    <!-- 高危操作：填原因 -->
    <el-dialog
      v-model="form.show"
      :title="form.op?.label"
      width="520px"
      :close-on-click-modal="false"
      append-to-body
    >
      <template v-if="form.op">
        <el-alert
          :title="form.op.warning"
          :type="form.op.danger ? 'warning' : 'info'"
          :closable="false"
          show-icon
        />
        <div class="target">设备：{{ device.sn }}{{ device.name ? `（${device.name}）` : '' }}</div>
        <el-form label-position="top" @submit.prevent="submit">
          <template v-if="form.op.key === 'replacePart'">
            <el-form-item label="更换部件">
              <el-radio-group v-model="form.part" @change="form.valueError = ''">
                <el-radio value="DTU">4G 模块（新 IMEI）</el-radio>
                <el-radio value="BOARD">主板（新蓝牙 MAC）</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item
              :label="
                form.part === 'DTU'
                  ? `新 IMEI（当前 ${device.imei}）`
                  : `新蓝牙 MAC（当前 ${device.bleMac || '无'}）`
              "
              :error="form.valueError"
            >
              <el-input
                v-model="form.value"
                :placeholder="form.part === 'DTU' ? '15 位数字' : 'AA:BB:CC:DD:EE:FF'"
              />
            </el-form-item>
          </template>
          <el-form-item v-if="form.op.key === 'forceUnbind'">
            <el-checkbox v-model="form.resetCode"
              >同时重置激活码（新码只显示一次，可交给申诉人立即绑定）</el-checkbox
            >
          </el-form-item>
          <el-form-item label="原因（写入操作日志）" :error="form.reasonError" required>
            <el-input
              v-model="form.reason"
              type="textarea"
              :rows="3"
              :maxlength="REASON_MAX"
              show-word-limit
              placeholder="如：业主持购买凭证申诉抢绑"
            />
          </el-form-item>
        </el-form>
        <div v-if="form.error" class="error">{{ form.error }}</div>
      </template>
      <template #footer>
        <el-button @click="form.show = false">取消</el-button>
        <el-button
          :type="form.op?.danger ? 'danger' : 'primary'"
          :loading="form.submitting"
          @click="submit"
        >
          确认{{ form.op?.label }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 新激活码只显示一次 -->
    <el-dialog
      v-model="code.show"
      title="新激活码"
      width="440px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      append-to-body
    >
      <el-alert
        title="激活码只显示这一次，关闭后无法再查看，请立即抄写或复制。"
        type="warning"
        :closable="false"
        show-icon
      />
      <div class="code">
        <div class="muted">SN：{{ code.sn }}</div>
        <div class="code__value">{{ code.value }}</div>
      </div>
      <template #footer>
        <el-button @click="copyCode">复制激活码</el-button>
        <el-button type="primary" @click="code.show = false">我已记录</el-button>
      </template>
    </el-dialog>

    <!-- 设备自检 -->
    <el-dialog
      v-model="check.show"
      title="设备自检"
      width="520px"
      append-to-body
      @close="closeSelfCheck"
    >
      <el-alert :title="op('selfCheck').warning" type="info" :closable="false" show-icon />
      <el-radio-group v-model="check.action" class="check-action" :disabled="check.running">
        <el-radio value="QUERY_STATUS">查询状态</el-radio>
        <el-radio value="QUERY_VERSION">查询固件版本</el-radio>
      </el-radio-group>
      <div v-if="check.error" class="error">{{ check.error }}</div>
      <el-descriptions v-if="check.command" :column="1" border class="check-result">
        <el-descriptions-item label="结果">
          <LabelTag :value="label(COMMAND_STATUS, check.command.status)" />
          <span v-if="check.command.resultCode" class="muted">
            {{ text(RESULT_CODE, check.command.resultCode) }}</span
          >
          <span v-if="check.running" class="muted"> 等待设备响应…</span>
        </el-descriptions-item>
        <el-descriptions-item label="下行帧"
          ><code>{{ check.command.downFrame || '—' }}</code></el-descriptions-item
        >
        <el-descriptions-item label="设备回帧"
          ><code>{{ check.command.rawFrame || '—' }}</code></el-descriptions-item
        >
        <el-descriptions-item v-if="check.command.fwVersion" label="固件版本">{{
          check.command.fwVersion
        }}</el-descriptions-item>
        <el-descriptions-item label="耗时">{{
          durationText(check.command.durationMs)
        }}</el-descriptions-item>
      </el-descriptions>
      <div v-if="check.timedOut" class="error">
        暂未收到结果，可稍后在指令审计中查看（指令 ID {{ check.command?.id }}）。
      </div>
      <template #footer>
        <el-button @click="closeSelfCheck">关闭</el-button>
        <el-button type="primary" :loading="check.running" @click="startSelfCheck">
          {{ check.command && !check.running ? '再次自检' : '开始自检' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ops {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.target {
  margin: 12px 0;
  color: var(--el-text-color-secondary);
}
.error {
  margin-top: 8px;
  color: var(--el-color-danger);
  font-size: 13px;
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
.check-action {
  margin: 16px 0 8px;
}
.check-result {
  margin-top: 12px;
}
code {
  font-family: Menlo, Consolas, monospace;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
