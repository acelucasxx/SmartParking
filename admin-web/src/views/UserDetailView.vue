<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { get, post } from '@/api/http'
import { AdminApiError, AdminCode } from '@/auth/errors'
import LabelTag from '@/components/LabelTag.vue'
import { MEMBER_STATUS, USER_STATUS, label } from '@/devices/labels'
import { REASON_MAX, reasonProblem } from '@/devices/ops'
import type { AppUserDetail } from '@/devices/types'
import { useAuthStore } from '@/stores/auth'
import { BAN_EFFECT, UNBAN_EFFECT } from '@/users/filters'
import { formatDateTime } from '@/utils/format'

/**
 * 用户管理 · 详情（ADM-07）：基本信息、名下设备、授权记录（含历史，最近 100 条）；封禁 / 解封（须填原因）。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const id = computed(() => Number(route.params.id))
const user = ref<AppUserDetail | null>(null)
const loading = ref(false)
const notFound = ref(false)
const canBan = computed(() => auth.can('user:ban'))

async function load() {
  loading.value = true
  try {
    user.value = await get<AppUserDetail>(`/users/${id.value}`, undefined, { silent: true })
    notFound.value = false
  } catch (e) {
    if (e instanceof AdminApiError && e.code === AdminCode.NOT_FOUND) notFound.value = true
    else if (e instanceof AdminApiError) ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

watch(id, () => void load(), { immediate: true })

const dialog = reactive({ show: false, reason: '', error: '', submitting: false })
const banning = computed(() => user.value?.status !== 'BANNED')

function openDialog() {
  Object.assign(dialog, { show: true, reason: '', error: '', submitting: false })
}

async function submit() {
  if (!user.value || dialog.submitting) return
  const problem = reasonProblem(dialog.reason)
  if (problem) {
    dialog.error = problem
    return
  }
  dialog.submitting = true
  try {
    await post(
      `/users/${user.value.id}/${banning.value ? 'ban' : 'unban'}`,
      { reason: dialog.reason.trim() },
      { silent: true },
    )
    ElMessage.success(banning.value ? '已封禁' : '已解封')
    dialog.show = false
    await load()
  } catch (e) {
    dialog.error = e instanceof AdminApiError ? e.message : '操作失败，请重试'
  } finally {
    dialog.submitting = false
  }
}
</script>

<template>
  <div v-loading="loading && !user">
    <el-page-header class="head" @back="router.back()">
      <template #content>
        <span class="title">{{ user ? user.nickname : '用户详情' }}</span>
        <LabelTag v-if="user" :value="label(USER_STATUS, user.status)" />
      </template>
    </el-page-header>

    <el-result v-if="notFound" icon="warning" title="用户不存在">
      <template #extra>
        <el-button type="primary" @click="router.push({ name: 'users' })">返回用户列表</el-button>
      </template>
    </el-result>

    <template v-else-if="user">
      <div v-if="canBan" class="ops">
        <el-button size="small" :type="banning ? 'danger' : 'primary'" plain @click="openDialog">
          {{ banning ? '封禁用户' : '解除封禁' }}
        </el-button>
      </div>

      <el-descriptions title="基本信息" :column="3" border>
        <el-descriptions-item label="ID">{{ user.id }}</el-descriptions-item>
        <el-descriptions-item label="昵称">{{ user.nickname }}</el-descriptions-item>
        <el-descriptions-item label="手机号">{{
          user.phoneMasked || '未授权'
        }}</el-descriptions-item>
        <el-descriptions-item label="状态"
          ><LabelTag :value="label(USER_STATUS, user.status)"
        /></el-descriptions-item>
        <el-descriptions-item label="注册时间">{{
          formatDateTime(user.createdAt)
        }}</el-descriptions-item>
        <el-descriptions-item label="最近登录">{{
          formatDateTime(user.lastLoginAt)
        }}</el-descriptions-item>
      </el-descriptions>

      <div class="section-title">名下设备（{{ user.ownedDevices.length }}）</div>
      <el-table :data="user.ownedDevices" empty-text="没有名下设备">
        <el-table-column label="SN" min-width="140">
          <template #default="{ row }">
            <router-link
              :to="{ name: 'device-detail', params: { id: row.deviceId } }"
              class="link"
              >{{ row.sn }}</router-link
            >
          </template>
        </el-table-column>
        <el-table-column label="名称" min-width="120">
          <template #default="{ row }">{{ row.name || '—' }}</template>
        </el-table-column>
        <el-table-column label="成为主人" width="160">
          <template #default="{ row }">{{ formatDateTime(row.since) }}</template>
        </el-table-column>
      </el-table>

      <div class="section-title">授权记录（含已结束，最近 100 条）</div>
      <el-table :data="user.memberDevices" empty-text="没有授权记录">
        <el-table-column label="SN" min-width="140">
          <template #default="{ row }">
            <router-link
              :to="{ name: 'device-detail', params: { id: row.deviceId } }"
              class="link"
              >{{ row.sn }}</router-link
            >
          </template>
        </el-table-column>
        <el-table-column label="名称" min-width="120">
          <template #default="{ row }">{{ row.name || '—' }}</template>
        </el-table-column>
        <el-table-column label="有效期" min-width="260">
          <template #default="{ row }">
            {{ formatDateTime(row.validFrom) }} 至
            {{ row.validTo ? formatDateTime(row.validTo) : '永久' }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }"
            ><LabelTag :value="label(MEMBER_STATUS, row.status)"
          /></template>
        </el-table-column>
      </el-table>
    </template>

    <el-dialog
      v-model="dialog.show"
      :title="banning ? '封禁用户' : '解除封禁'"
      width="500px"
      :close-on-click-modal="false"
    >
      <el-alert
        :title="banning ? BAN_EFFECT : UNBAN_EFFECT"
        :type="banning ? 'warning' : 'info'"
        :closable="false"
        show-icon
      />
      <div class="target">用户：{{ user?.nickname }}（ID {{ user?.id }}）</div>
      <el-input
        v-model="dialog.reason"
        type="textarea"
        :rows="3"
        :maxlength="REASON_MAX"
        show-word-limit
        placeholder="原因（写入操作日志），如：多次恶意抢绑他人设备"
        @input="dialog.error = ''"
      />
      <div v-if="dialog.error" class="error">{{ dialog.error }}</div>
      <template #footer>
        <el-button @click="dialog.show = false">取消</el-button>
        <el-button
          :type="banning ? 'danger' : 'primary'"
          :loading="dialog.submitting"
          @click="submit"
        >
          确认{{ banning ? '封禁' : '解封' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.head {
  margin-bottom: 12px;
}
.title {
  margin-right: 8px;
  font-weight: 600;
}
.ops {
  margin-bottom: 12px;
}
.section-title {
  margin: 20px 0 8px;
  font-weight: 600;
}
.link {
  color: var(--el-color-primary);
  text-decoration: none;
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
</style>
