<script setup lang="ts">
import { watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { guard } from '@/auth/access'
import { target } from '@/router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

// 会话失效（10002 或前端判定超时）、被要求先修改密码时，按路由守卫的规则就地跳转
watch(
  () => [auth.token, auth.profile?.mustChangePassword] as const,
  () => {
    if (auth.token && !auth.profile) return
    const result = guard(target(route), auth.profile)
    if (result.kind === 'redirect') router.replace({ path: result.path, query: result.query })
  },
)
</script>

<template>
  <router-view />
</template>
