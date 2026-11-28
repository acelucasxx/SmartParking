<script setup lang="ts">
import { reactive, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { get } from '@/api/http'
import LabelTag from '@/components/LabelTag.vue'
import { usePaged } from '@/composables/usePaged'
import {
  type DeviceFilters,
  EMPTY_FILTERS,
  filterProblem,
  fromQuery,
  toParams,
  toQuery,
} from '@/devices/filters'
import { BIND_STATUS, GATE_STATUS, ONLINE_STATE, batteryText, label } from '@/devices/labels'
import type { AdminDevice, Paged } from '@/devices/types'
import { formatDateTime } from '@/utils/format'

/**
 * 设备管理 · 列表（ADM-03）：按 SN / IMEI 前缀、主人手机号、绑定状态、在线状态、启用状态筛选。
 * 筛选条件写在地址栏，刷新、从详情返回、分享链接都保留。
 */
const SIZE = 20
const route = useRoute()
const router = useRouter()
const form = reactive<DeviceFilters>({ ...EMPTY_FILTERS })

const list = usePaged((page) =>
  get<Paged<AdminDevice>>('/devices', toParams({ ...fromQuery(route.query), page }, SIZE)),
)

// 地址栏变化（含首次进入、浏览器前进后退）时同步表单并查询
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

function open(row: AdminDevice) {
  router.push({ name: 'device-detail', params: { id: row.id } })
}
</script>

<template>
  <div>
    <el-card shadow="never">
      <el-form :model="form" inline class="filters" @submit.prevent="search">
        <el-form-item label="SN">
          <el-input v-model="form.sn" placeholder="前缀匹配" clearable />
        </el-form-item>
        <el-form-item label="IMEI">
          <el-input v-model="form.imei" placeholder="前缀匹配" clearable />
        </el-form-item>
        <el-form-item label="主人手机号">
          <el-input v-model="form.phone" placeholder="完整 11 位" clearable maxlength="11" />
        </el-form-item>
        <el-form-item label="绑定">
          <el-select v-model="form.bindStatus" placeholder="全部" clearable class="select">
            <el-option v-for="(v, k) in BIND_STATUS" :key="k" :label="v.text" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="在线">
          <el-select v-model="form.onlineState" placeholder="全部" clearable class="select">
            <el-option v-for="(v, k) in ONLINE_STATE" :key="k" :label="v.text" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-select v-model="form.enabled" placeholder="全部" clearable class="select">
            <el-option label="启用" value="true" />
            <el-option label="已禁用" value="false" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" native-type="submit">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-table
        v-loading="list.loading.value"
        :data="list.items.value"
        :empty-text="list.loaded.value ? '没有符合条件的设备' : ' '"
        row-class-name="clickable"
        @row-click="open"
      >
        <el-table-column prop="sn" label="SN" min-width="150" />
        <el-table-column prop="imei" label="IMEI" min-width="150" />
        <el-table-column label="型号 / 批次" min-width="150">
          <template #default="{ row }">{{ row.model }} / {{ row.batchNo }}</template>
        </el-table-column>
        <el-table-column label="绑定" width="90">
          <template #default="{ row }"
            ><LabelTag :value="label(BIND_STATUS, row.bindStatus)"
          /></template>
        </el-table-column>
        <el-table-column label="在线" width="80">
          <template #default="{ row }"
            ><LabelTag :value="label(ONLINE_STATE, row.onlineState)"
          /></template>
        </el-table-column>
        <el-table-column label="闸杆" width="100">
          <template #default="{ row }"
            ><LabelTag :value="label(GATE_STATUS, row.gateStatus)"
          /></template>
        </el-table-column>
        <el-table-column label="电量" width="70">
          <template #default="{ row }">{{ batteryText(row.batteryPct) }}</template>
        </el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-tag v-if="!row.enabled" type="danger" size="small">已禁用</el-tag>
            <span v-else class="muted">启用</span>
          </template>
        </el-table-column>
        <el-table-column label="最近上行" width="150">
          <template #default="{ row }">{{ formatDateTime(row.lastSeenAt) }}</template>
        </el-table-column>
        <el-table-column label="入库时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
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
.table-card {
  margin-top: 16px;
}
.table-card :deep(.clickable) {
  cursor: pointer;
}
.pager {
  justify-content: flex-end;
  margin-top: 12px;
}
.muted {
  color: var(--el-text-color-secondary);
}
</style>
