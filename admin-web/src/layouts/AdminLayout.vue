<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElNotification } from 'element-plus'
import { visibleMenu } from '@/auth/access'
import { sessionPhase } from '@/auth/idle'
import { ROLE_LABEL } from '@/auth/types'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const menu = computed(() => (auth.profile?.mustChangePassword ? [] : visibleMenu(auth.profile)))
const roleLabel = computed(() => (auth.profile ? ROLE_LABEL[auth.profile.role] : ''))

// 会话超时：空闲 30 分钟、自登录起 12 小时（auth/idle.ts）。到期前 2 分钟提醒一次，到期回登录页
let timer: number | undefined
let warned = false
function checkSession() {
  if (!auth.token) return
  const phase = sessionPhase(
    { loginAt: auth.loginAt, lastActivityAt: auth.lastActivityAt },
    Date.now(),
  )
  if (phase === 'expired') {
    auth.expire('长时间未操作，登录已过期，请重新登录')
  } else if (phase === 'expiring' && !warned) {
    warned = true
    ElNotification.warning({
      title: '会话即将超时',
      message: '2 分钟内没有操作将自动退出，进行任意操作可继续使用。',
    })
  } else if (phase === 'active') {
    warned = false
  }
}
onMounted(() => {
  timer = window.setInterval(checkSession, 15_000)
})
onBeforeUnmount(() => window.clearInterval(timer))

async function onCommand(command: string) {
  if (command === 'password') router.push({ name: 'password' })
  else if (command === 'logout') {
    await auth.logout()
    router.replace({ name: 'login' })
  }
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="brand">智能车位道闸</div>
      <el-menu :default-active="route.path" router>
        <el-menu-item v-for="m in menu" :key="m.name" :index="m.path">
          {{ m.title }}
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="title">{{ route.meta.title }}</span>
        <el-dropdown v-if="auth.profile" trigger="click" @command="onCommand">
          <span class="account">
            {{ auth.profile.displayName }}
            <el-tag size="small" type="info" class="role">{{ roleLabel }}</el-tag>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="password">修改密码</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
}
.aside {
  border-right: 1px solid var(--el-border-color-light);
}
.brand {
  height: 56px;
  line-height: 56px;
  padding: 0 20px;
  font-weight: 600;
}
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--el-border-color-light);
}
.title {
  font-weight: 500;
}
.account {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}
</style>
