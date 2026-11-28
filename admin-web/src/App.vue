<script setup lang="ts">
import { watch } from 'vue'
import { useRouter } from 'vue-router'
import { guard } from '@/auth/access'
import { target } from '@/router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

// 会话失效（10002 或前端判定超时）、被要求先修改密码时，按路由守卫的规则就地跳转。
// 刷新页面时 /auth/me 返回也会触发这里，那时首次导航还没完成、当前路由仍是起始的 /：
// 必须等路由就绪后按真实的当前路由判断，否则会把直接打开的页面错跳到首页。
watch(
  () => [auth.token, auth.profile?.mustChangePassword] as const,
  async () => {
    if (auth.token && !auth.profile) return
    await router.isReady()
    const current = router.currentRoute.value
    const result = guard(target(current), auth.profile)
    if (result.kind === 'redirect') router.replace({ path: result.path, query: result.query })
  },
)
</script>

<template>
  <router-view />
</template>
