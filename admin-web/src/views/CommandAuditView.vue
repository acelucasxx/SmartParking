<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { download, get } from '@/api/http'
import LabelTag from '@/components/LabelTag.vue'
import { usePaged } from '@/composables/usePaged'
import {
  type CommandFilters,
  EMPTY,
  exportParams,
  filterProblem,
  fromQuery,
  listParams,
  toQuery,
} from '@/commands/filters'
import {
  CHANNEL,
  COMMAND_ACTION,
  COMMAND_STATUS,
  OPERATOR_ROLE,
  RESULT_CODE,
  durationText,
  label,
  text,
} from '@/devices/labels'
import type { CommandAudit, Paged } from '@/devices/types'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime } from '@/utils/format'

/**
 * 指令审计（ADM-04，PRD 6.2）：按设备、SN、操作人手机号、通道、状态、时间筛选，看原始帧，导出 xlsx。
 * 筛选条件写在地址栏；导出须选时间范围（≤ 31 天），不支持按手机号。
 */
const SIZE = 20
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const form = reactive<CommandFilters>({ ...EMPTY })
const range = computed<[string, string] | null>({
  get: () => (form.from && form.to ? ([form.from, form.to] as [string, string]) : null),
  set: (v: [string, string] | null) => {
    form.from = v?.[0] ?? ''
    form.to = v?.[1] ?? ''
  },
})

const list = usePaged((page) =>
  get<Paged<CommandAudit>>('/commands', listParams({ ...fromQuery(route.query), page }, SIZE)),
)

watch(
  () => route.query,
  (q) => {
    Object.assign(form, fromQuery(q))
    void list.load(form.page)
  },
  { immediate: true },
)

function search() {
  const problem = filterProblem(form)
  if (problem) {
    ElMessage.warning(problem)
    return
  }
  router.push({ query: toQuery({ ...form, page: 1 }) })
}

function reset() {
  router.push({ query: {} })
}

function changePage(page: number) {
  router.push({ query: toQuery({ ...fromQuery(route.query), page }) })
}

const canExport = computed(() => auth.can('command:export'))
const exporting = ref(false)

async function exportXlsx() {
  // 导出按地址栏里已生效的条件，避免表单改了没点查询时导出的与看到的不一致
  const built = exportParams(fromQuery(route.query))
  if (!built.ok) {
    ElMessage.warning(built.message)
    return
  }
  exporting.value = true
  try {
    await download('/commands/export', '指令流水.xlsx', built.params)
  } catch {
    // 已提示（如超过 10 万行）
  } finally {
    exporting.value = false
  }
}
</script>

<template>
  <div>
    <el-card shadow="never">
      <el-form :model="form" inline class="filters" @submit.prevent="search">
        <el-form-item v-if="form.deviceId" label="设备 ID">
          <el-tag closable @close="form.deviceId = ''">{{ form.deviceId }}</el-tag>
        </el-form-item>
        <el-form-item label="SN">
          <el-input v-model="form.sn" placeholder="完整 SN" clearable />
        </el-form-item>
        <el-form-item label="操作人手机号">
          <el-input v-model="form.phone" placeholder="完整 11 位" clearable maxlength="11" />
        </el-form-item>
        <el-form-item label="通道">
          <el-select v-model="form.channel" placeholder="全部" clearable class="select">
            <el-option v-for="(v, k) in CHANNEL" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" placeholder="全部" clearable class="select">
            <el-option v-for="(v, k) in COMMAND_STATUS" :key="k" :label="v.text" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="下发时间">
          <el-date-picker
            v-model="range"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            class="range"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" native-type="submit">查询</el-button>
          <el-button @click="reset">重置</el-button>
          <el-button v-if="canExport" :loading="exporting" @click="exportXlsx">导出</el-button>
        </el-form-item>
      </el-form>
      <div class="hint">
        导出按当前查询条件，须选择不超过 31 天的时间范围；单次最多 10 万行，手机号已脱敏。
      </div>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-table
        v-loading="list.loading.value"
        :data="list.items.value"
        :empty-text="list.loaded.value ? '没有符合条件的指令' : ' '"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="frames">
              <div>指令 ID：{{ row.id }}</div>
              <div>
                下行帧：<code>{{ row.downFrame || '—' }}</code>
              </div>
              <div>
                判定帧：<code>{{ row.rawFrame || '—' }}</code>
              </div>
              <div v-if="row.fwVersion">固件版本：{{ row.fwVersion }}</div>
              <div>完成时间：{{ formatDateTime(row.finishedAt, true) }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="下发时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.issuedAt, true) }}</template>
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
        <el-table-column label="操作人" min-width="180">
          <template #default="{ row }">
            {{ row.operator.name }}
            <span class="muted"
              >{{ text(OPERATOR_ROLE, row.role)
              }}{{ row.operator.phoneMasked ? ` · ${row.operator.phoneMasked}` : '' }}</span
            >
          </template>
        </el-table-column>
        <el-table-column label="动作" width="90">
          <template #default="{ row }">{{ text(COMMAND_ACTION, row.action) }}</template>
        </el-table-column>
        <el-table-column label="通道" width="70">
          <template #default="{ row }">{{ text(CHANNEL, row.channel) }}</template>
        </el-table-column>
        <el-table-column label="结果" width="170">
          <template #default="{ row }">
            <LabelTag :value="label(COMMAND_STATUS, row.status)" />
            <span v-if="row.resultCode" class="muted">
              {{ text(RESULT_CODE, row.resultCode) }}</span
            >
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="100">
          <template #default="{ row }">{{ durationText(row.durationMs) }}</template>
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
  </div>
</template>

<style scoped>
.filters :deep(.el-form-item) {
  margin-bottom: 8px;
}
.select {
  width: 110px;
}
.range {
  width: 260px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.table-card {
  margin-top: 16px;
}
.frames {
  padding: 4px 48px;
  line-height: 1.9;
}
code {
  font-family: Menlo, Consolas, monospace;
}
.link {
  color: var(--el-color-primary);
  text-decoration: none;
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
