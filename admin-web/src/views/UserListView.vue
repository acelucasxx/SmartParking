<script setup lang="ts">
import { reactive, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { get } from '@/api/http'
import LabelTag from '@/components/LabelTag.vue'
import { usePaged } from '@/composables/usePaged'
import { USER_STATUS, label } from '@/devices/labels'
import type { AppUser, Paged } from '@/devices/types'
import {
  EMPTY_USER_FILTERS,
  type UserFilters,
  filterProblem,
  fromQuery,
  toParams,
  toQuery,
} from '@/users/filters'
import { formatDateTime } from '@/utils/format'

/**
 * 用户管理 · 列表（ADM-07，PRD 6.2）：小程序用户，手机号脱敏显示；按完整手机号精确查询、按状态筛选，按注册时间倒序。
 */
const SIZE = 20
const route = useRoute()
const router = useRouter()
const form = reactive<UserFilters>({ ...EMPTY_USER_FILTERS })

const list = usePaged((page) =>
  get<Paged<AppUser>>('/users', toParams({ ...fromQuery(route.query), page }, SIZE)),
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

function open(row: AppUser) {
  router.push({ name: 'user-detail', params: { id: row.id } })
}
</script>

<template>
  <div>
    <el-card shadow="never">
      <el-form :model="form" inline class="filters" @submit.prevent="search">
        <el-form-item label="手机号">
          <el-input v-model="form.phone" placeholder="完整 11 位" clearable maxlength="13" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" placeholder="全部" clearable class="select">
            <el-option v-for="(v, k) in USER_STATUS" :key="k" :label="v.text" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" native-type="submit">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </el-form-item>
      </el-form>
      <div class="hint">手机号加密存储，只能按完整号码精确查询；列表中脱敏显示。</div>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-table
        v-loading="list.loading.value"
        :data="list.items.value"
        :empty-text="list.loaded.value ? '没有符合条件的用户' : ' '"
        row-class-name="clickable"
        @row-click="open"
      >
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="nickname" label="昵称" min-width="120" />
        <el-table-column label="手机号" width="130">
          <template #default="{ row }">{{ row.phoneMasked || '未授权' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }"
            ><LabelTag :value="label(USER_STATUS, row.status)"
          /></template>
        </el-table-column>
        <el-table-column label="名下设备" width="90">
          <template #default="{ row }">{{ row.ownedCount }}</template>
        </el-table-column>
        <el-table-column label="被授权" width="80">
          <template #default="{ row }">{{ row.memberCount }}</template>
        </el-table-column>
        <el-table-column label="注册时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="最近登录" width="150">
          <template #default="{ row }">{{ formatDateTime(row.lastLoginAt) }}</template>
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
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
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
</style>
