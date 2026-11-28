<script setup lang="ts">
import { computed } from 'vue'
import { homePath, FORBIDDEN_PATH } from '@/auth/access'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const home = computed(() => homePath(auth.profile))
</script>

<template>
  <el-result
    icon="warning"
    title="无权访问"
    sub-title="你的角色没有该页面的权限，如需开通请联系超级管理员。"
  >
    <template #extra>
      <el-button v-if="home !== FORBIDDEN_PATH" type="primary" @click="$router.replace(home)">
        返回首页
      </el-button>
    </template>
  </el-result>
</template>
