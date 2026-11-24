<script setup lang="ts">
import { reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const form = reactive({ username: '', password: '' })

// 骨架阶段：不调用接口，直接写入占位 token。真实登录、失败锁定在 ADM-01 / BIZ-08 实现。
function submit() {
  auth.setToken('dev-placeholder')
  router.replace((route.query.redirect as string) || '/')
}
</script>

<template>
  <div class="login">
    <el-card class="card" header="管理后台登录">
      <el-form :model="form" label-position="top" @submit.prevent="submit">
        <el-form-item label="账号">
          <el-input v-model="form.username" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            autocomplete="current-password"
          />
        </el-form-item>
        <el-button type="primary" native-type="submit" class="submit">登录</el-button>
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
  width: 360px;
}
.submit {
  width: 100%;
}
</style>
