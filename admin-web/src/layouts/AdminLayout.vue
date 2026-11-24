<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { menu } from '@/router/menu'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

// 角色接入前（BIZ-08）全部菜单可见；接入后按 roles 过滤。
const visibleMenu = computed(() =>
  auth.roles.length === 0
    ? menu
    : menu.filter((m) => !m.roles || m.roles.some((r) => auth.roles.includes(r))),
)

function logout() {
  auth.setToken(null)
  router.push({ name: 'login' })
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="200px" class="aside">
      <div class="brand">智能车位道闸</div>
      <el-menu :default-active="route.path" router>
        <el-menu-item v-for="m in visibleMenu" :key="m.name" :index="m.path">
          {{ m.title }}
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span>{{ route.meta.title }}</span>
        <el-button link @click="logout">退出登录</el-button>
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
</style>
