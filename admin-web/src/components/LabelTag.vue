<script setup lang="ts">
import type { Label } from '@/devices/labels'

defineProps<{ value: Label }>()
</script>

<template>
  <el-tag :type="value.type" size="small" disable-transitions>{{ value.text }}</el-tag>
</template>
