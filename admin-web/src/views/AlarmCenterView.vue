<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { get, post } from '@/api/http'
import { AdminApiError } from '@/auth/errors'
import LabelTag from '@/components/LabelTag.vue'
import { usePaged } from '@/composables/usePaged'
import {
  type AlarmFilters,
  DEFAULT_FILTERS,
  NOTE_MAX,
  fromQuery,
  handleFailure,
  noteProblem,
  toParams,
  toQuery,
} from '@/alarms/filters'
import { ALARM_LEVEL, ALARM_STATUS, ALARM_TYPE, label, text } from '@/devices/labels'
import type { Alarm, Paged } from '@/devices/types'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/format'

/**
 * 告警中心（ADM-05，PRD 6.2）：电机故障、遇阻、红外防砸、连续 3 次指令超时、离线超 24 小时；
 * 同设备同类 30 分钟内合并（显示合并次数）；标记处理并填写说明。默认只看未处理的。
 */
const SIZE = 20
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const form = reactive<AlarmFilters>({ ...DEFAULT_FILTERS })
const canHandle = computed(() => auth.can('alarm:handle'))

const list = usePaged((page) =>
  get<Paged<Alarm>>('/alarms', toParams({ ...fromQuery(route.query), page }, SIZE)),
)

watch(
  () => route.query,
  (q) => {
    Object.assign(form, fromQuery(q))
    void list.load(form.page)
  },
  { immediate: true },
)

function apply(patch: Partial<AlarmFilters> = {}) {
  if (form.sn && !/^[A-Za-z0-9]+$/.test(form.sn.replace(/\s+/g, ''))) {
    ElMessage.warning('SN 只能包含字母和数字')
    return
  }
  router.push({ query: toQuery({ ...form, ...patch, page: 1 }) })
}

function reset() {
  router.push({ query: {} })
}

function changePage(page: number) {
  router.push({ query: toQuery({ ...fromQuery(route.query), page }) })
}

// ------------------------------------------------------------ 标记处理

const dialog = reactive({
  show: false,
  alarm: null as Alarm | null,
  note: '',
  error: '',
  submitting: false,
})

function openHandle(alarm: Alarm) {
  Object.assign(dialog, { show: true, alarm, note: '', error: '', submitting: false })
}

async function submitHandle() {
  if (!dialog.alarm || dialog.submitting) return
  const problem = noteProblem(dialog.note)
  if (problem) {
    dialog.error = problem
    return
  }
  dialog.submitting = true
  try {
    const updated = await post<Alarm>(
      `/alarms/${dialog.alarm.id}/handle`,
      { note: dialog.note.trim() },
      { silent: true },
    )
    ElMessage.success('已标记处理')
    dialog.show = false
    // 只看未处理时，处理完的从列表里移走；其余就地更新
    if (form.status === 'OPEN') void list.load(list.page.value)
    else {
      const i = list.items.value.findIndex((a) => a.id === updated.id)
      if (i >= 0) list.items.value[i] = updated
    }
  } catch (e) {
    if (e instanceof AdminApiError && handleFailure(e.code) === 'refresh') {
      ElMessage.warning(e.code === 10009 ? '该告警已被其他人处理' : e.message)
      dialog.show = false
      void list.load(list.page.value)
    } else {
      dialog.error = e instanceof AdminApiError ? e.message : '处理失败，请重试'
    }
  } finally {
    dialog.submitting = false
  }
}
</script>

<template>
  <div>
    <el-card shadow="never">
      <el-tabs
        :model-value="form.status"
        class="tabs"
        @tab-change="(s: string | number) => apply({ status: s as AlarmFilters['status'] })"
      >
        <el-tab-pane label="未处理" name="OPEN" />
        <el-tab-pane label="已处理" name="HANDLED" />
        <el-tab-pane label="全部" name="ALL" />
      </el-tabs>
      <el-form :model="form" inline class="filters" @submit.prevent="apply()">
        <el-form-item v-if="form.deviceId" label="设备 ID">
          <el-tag closable @close="apply({ deviceId: '' })">{{ form.deviceId }}</el-tag>
        </el-form-item>
        <el-form-item label="SN">
          <el-input v-model="form.sn" placeholder="完整 SN" clearable />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type" placeholder="全部" clearable class="select">
            <el-option v-for="(v, k) in ALARM_TYPE" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" native-type="submit">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>
      <div class="hint">
        同设备同类告警 30
        分钟内合并为一条，显示合并次数；处理后再发生会产生新告警。设备恢复上线不会自动关闭离线告警。
      </div>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-table
        v-loading="list.loading.value"
        :data="list.items.value"
        :empty-text="
          list.loaded.value
            ? form.status === 'OPEN'
              ? '没有未处理的告警'
              : '没有符合条件的告警'
            : ' '
        "
      >
        <el-table-column label="类型" width="130">
          <template #default="{ row }">{{ text(ALARM_TYPE, row.type) }}</template>
        </el-table-column>
        <el-table-column label="级别" width="80">
          <template #default="{ row }"
            ><LabelTag :value="label(ALARM_LEVEL, row.level)"
          /></template>
        </el-table-column>
        <el-table-column label="SN" min-width="140">
          <template #default="{ row }">
            <router-link
              :to="{ name: 'device-detail', params: { id: row.deviceId } }"
              class="link"
              >{{ row.sn }}</router-link
            >
          </template>
        </el-table-column>
        <el-table-column label="最近发生" width="150">
          <template #default="{ row }">{{ formatDateTime(row.lastAt) }}</template>
        </el-table-column>
        <el-table-column label="首次发生" width="150">
          <template #default="{ row }">{{ formatDateTime(row.firstAt) }}</template>
        </el-table-column>
        <el-table-column label="次数" width="70">
          <template #default="{ row }">{{ row.count }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }"
            ><LabelTag :value="label(ALARM_STATUS, row.status)"
          /></template>
        </el-table-column>
        <el-table-column label="处理" min-width="200">
          <template #default="{ row }">
            <template v-if="row.status === 'HANDLED'">
              <div>{{ row.handlerName }} · {{ formatDateTime(row.handledAt) }}</div>
              <div class="muted note">{{ row.note }}</div>
            </template>
            <el-button
              v-else-if="canHandle"
              size="small"
              type="primary"
              plain
              @click="openHandle(row)"
              >标记处理</el-button
            >
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        class="pager"
        layout="total, prev, pager, next"
        :current-page="list.page.value"
        :page-size="SIZE"
        :total="list.total.value"
        hide-on-single-page
        @current-change="changePage"
      />
    </el-card>

    <el-dialog v-model="dialog.show" title="标记处理" width="480px" :close-on-click-modal="false">
      <template v-if="dialog.alarm">
        <div class="summary">
          {{ text(ALARM_TYPE, dialog.alarm.type) }} · {{ dialog.alarm.sn }} · 共
          {{ dialog.alarm.count }} 次，最近
          {{ formatDateTime(dialog.alarm.lastAt) }}
        </div>
        <el-input
          v-model="dialog.note"
          type="textarea"
          :rows="4"
          :maxlength="NOTE_MAX"
          show-word-limit
          placeholder="处理说明，如：已电话联系业主，现场有杂物，已清理"
          @input="dialog.error = ''"
        />
        <div v-if="dialog.error" class="error">{{ dialog.error }}</div>
      </template>
      <template #footer>
        <el-button @click="dialog.show = false">取消</el-button>
        <el-button type="primary" :loading="dialog.submitting" @click="submitHandle"
          >确认处理</el-button
        >
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tabs {
  margin-top: -8px;
}
.filters :deep(.el-form-item) {
  margin-bottom: 8px;
}
.select {
  width: 140px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.table-card {
  margin-top: 16px;
}
.link {
  color: var(--el-color-primary);
  text-decoration: none;
}
.note {
  white-space: pre-wrap;
}
.summary {
  margin-bottom: 12px;
  color: var(--el-text-color-secondary);
}
.error {
  margin-top: 8px;
  color: var(--el-color-danger);
  font-size: 13px;
}
.pager {
  justify-content: flex-end;
  margin-top: 12px;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
