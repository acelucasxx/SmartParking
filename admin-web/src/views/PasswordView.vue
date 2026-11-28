<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { homePath } from '@/auth/access'
import { AdminApiError, AdminCode } from '@/auth/errors'
import { PASSWORD_MAX, PASSWORD_MIN, passwordProblem } from '@/auth/password'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const form = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const submitting = ref(false)
const error = ref('')
/** 用临时密码登录：必须先修改，不能离开本页 */
const forced = computed(() => !!auth.profile?.mustChangePassword)

async function submit() {
  if (submitting.value) return
  if (!form.oldPassword) {
    error.value = forced.value ? '请输入临时密码' : '请输入原密码'
    return
  }
  const problem = passwordProblem(form.newPassword, form.oldPassword, form.confirm)
  if (problem) {
    error.value = problem
    return
  }
  submitting.value = true
  error.value = ''
  try {
    await auth.changePassword(form.oldPassword, form.newPassword)
    ElMessage.success('密码已修改，其他设备上的登录已退出')
    Object.assign(form, { oldPassword: '', newPassword: '', confirm: '' })
    await router.replace(homePath(auth.profile))
  } catch (e) {
    if (e instanceof AdminApiError) {
      error.value = e.code === AdminCode.LOGIN_FAILED ? '原密码错误' : e.message
    } else error.value = '修改失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}

async function logout() {
  await auth.logout()
  router.replace({ name: 'login' })
}
</script>

<template>
  <el-card class="card" :header="forced ? '首次登录，请修改密码' : '修改密码'">
    <el-alert
      v-if="forced"
      title="你正在使用临时密码，修改后才能使用其他功能。"
      type="warning"
      :closable="false"
      show-icon
      class="alert"
    />
    <el-form :model="form" label-position="top" @submit.prevent="submit">
      <el-form-item :label="forced ? '临时密码' : '原密码'">
        <el-input
          v-model="form.oldPassword"
          type="password"
          show-password
          autocomplete="current-password"
        />
      </el-form-item>
      <el-form-item label="新密码">
        <el-input
          v-model="form.newPassword"
          type="password"
          show-password
          autocomplete="new-password"
        />
        <div class="hint">
          {{ PASSWORD_MIN }}–{{ PASSWORD_MAX }} 位，同时包含字母和数字，不能有空格
        </div>
      </el-form-item>
      <el-form-item label="确认新密码">
        <el-input
          v-model="form.confirm"
          type="password"
          show-password
          autocomplete="new-password"
        />
      </el-form-item>
      <div v-if="error" class="error">{{ error }}</div>
      <el-button type="primary" native-type="submit" :loading="submitting">确认修改</el-button>
      <el-button v-if="forced" link @click="logout">退出登录</el-button>
    </el-form>
  </el-card>
</template>

<style scoped>
.card {
  max-width: 440px;
}
.alert {
  margin-bottom: 16px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.error {
  margin: -8px 0 12px;
  color: var(--el-color-danger);
  font-size: 13px;
}
</style>
