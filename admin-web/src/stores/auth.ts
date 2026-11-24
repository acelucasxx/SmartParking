import { defineStore } from 'pinia'
import { ref } from 'vue'

export type AdminRole = 'SUPER_ADMIN' | 'OPS' | 'SUPPORT'

const TOKEN_KEY = 'sp_admin_token'

/** 登录态。真实登录接口在 BIZ-08 / ADM-01 中接入。 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(sessionStorage.getItem(TOKEN_KEY))
  const roles = ref<AdminRole[]>([])

  function setToken(value: string | null) {
    token.value = value
    if (value) sessionStorage.setItem(TOKEN_KEY, value)
    else sessionStorage.removeItem(TOKEN_KEY)
  }

  return { token, roles, setToken }
})
