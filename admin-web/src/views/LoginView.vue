<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { homePath, safeRedirect } from '@/auth/access'
import { AdminApiError, loginFailure } from '@/auth/errors'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const form = reactive({ username: '', password: '' })
const submitting = ref(false)
/** 锁定、停用：醒目的提示框；密码错误（含剩余次数）、其他错误：表单下的提示 */
const failure = ref<ReturnType<typeof loginFailure> | null>(null)
const blocked = computed(
  () => failure.value?.kind === 'locked' || failure.value?.kind === 'disabled',
)

async function submit() {
  if (submitting.value) return
  if (!form.username.trim() || !form.password) {
    failure.value = { kind: 'other', message: '请输入账号和密码' }
    return
  }
  submitting.value = true
  failure.value = null
  try {
    const admin = await auth.login(form.username, form.password)
    form.password = ''
    const next = admin.mustChangePassword
      ? { name: 'password' }
      : safeRedirect(route.query.redirect, homePath(admin))
    await router.replace(next)
  } catch (e) {
    form.password = ''
    failure.value =
      e instanceof AdminApiError
        ? loginFailure(e)
        : { kind: 'other', message: '登录失败，请稍后重试' }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login">
    <el-card class="card" header="智能车位道闸 · 管理后台">
      <el-alert
        v-if="auth.notice && !failure"
        :title="auth.notice"
        type="warning"
        :closable="false"
        show-icon
        class="alert"
      />
      <el-alert
        v-if="blocked"
        :title="failure?.kind === 'locked' ? '账号已锁定' : '账号已停用'"
        :description="
          failure?.kind === 'locked'
            ? `${failure.message}。连续输错 5 次会锁定 15 分钟，如忘记密码请联系超级管理员重置。`
            : '请联系超级管理员启用账号。'
        "
        type="error"
        :closable="false"
        show-icon
        class="alert"
      />
      <el-form :model="form" label-position="top" @submit.prevent="submit">
        <el-form-item label="账号">
          <el-input v-model="form.username" autocomplete="username" :disabled="submitting" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            autocomplete="current-password"
            :disabled="submitting"
          />
        </el-form-item>
        <div v-if="failure && !blocked" class="error">{{ failure.message }}</div>
        <el-button type="primary" native-type="submit" class="submit" :loading="submitting">
          登录
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  background: var(--el-bg-color-page);
}
.card {
  width: 380px;
}
.alert {
  margin-bottom: 16px;
}
.error {
  margin: -8px 0 12px;
  color: var(--el-color-danger);
  font-size: 13px;
}
.submit {
  width: 100%;
}
</style>
