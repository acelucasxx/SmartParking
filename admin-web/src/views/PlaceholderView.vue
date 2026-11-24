<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()
</script>

<template>
  <el-empty :description="`${route.meta.title}：待实现（${route.meta.task}）`" />
</template>
