import type { Directive } from 'vue'
import { useAuthStore } from '@/stores/auth'

/**
 * 按钮级权限：`<el-button v-can="'device:import'">导入</el-button>`，没有该权限点时不渲染。
 * 权限点在登录时确定，改角色需重新登录（服务端下一次请求即按新角色拦截），所以只在挂载时判断一次。
 * 需要根据权限切换内容时用 `auth.can()` 配合 v-if。
 */
export const vCan: Directive<HTMLElement, string> = {
  mounted(el, binding) {
    if (!useAuthStore().can(binding.value)) el.remove()
  },
}
