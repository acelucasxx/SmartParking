import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { get, post, put, setAuthHooks } from '@/api/http'
import { can as canDo } from '@/auth/access'
import type { AdminLoginResult, AdminProfile } from '@/auth/types'

export type { AdminRole } from '@/auth/types'

const STORAGE_KEY = 'sp_admin_session'

interface Stored {
  token: string
  loginAt: number
  lastActivityAt: number
}

/** 会话放 sessionStorage：关掉标签页即退出，不跨标签共享 */
function load(): Stored | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY)
    const s = raw ? (JSON.parse(raw) as Partial<Stored>) : null
    if (
      s &&
      typeof s.token === 'string' &&
      typeof s.loginAt === 'number' &&
      typeof s.lastActivityAt === 'number'
    ) {
      return s as Stored
    }
  } catch {
    // 损坏的数据按未登录处理
  }
  return null
}

/**
 * 登录态：token、当前账号（含角色与权限点）、会话计时。
 * 刷新页面后只有 token，路由守卫会先调 /auth/me 取回账号。
 */
export const useAuthStore = defineStore('auth', () => {
  const stored = load()
  const token = ref<string | null>(stored?.token ?? null)
  const loginAt = ref(stored?.loginAt ?? 0)
  const lastActivityAt = ref(stored?.lastActivityAt ?? 0)
  const profile = ref<AdminProfile | null>(null)
  /** 回到登录页时显示的原因，如“登录已过期，请重新登录” */
  const notice = ref<string | null>(null)
  let loadingProfile: Promise<AdminProfile | null> | null = null

  const loggedIn = computed(() => !!token.value && !!profile.value)

  function persist() {
    if (token.value) {
      const s: Stored = {
        token: token.value,
        loginAt: loginAt.value,
        lastActivityAt: lastActivityAt.value,
      }
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(s))
    } else sessionStorage.removeItem(STORAGE_KEY)
  }

  function clear(reason: string | null) {
    token.value = null
    profile.value = null
    loginAt.value = 0
    lastActivityAt.value = 0
    notice.value = reason
    persist()
  }

  async function login(username: string, password: string): Promise<AdminProfile> {
    const result = await post<AdminLoginResult>(
      '/auth/login',
      { username: username.trim(), password },
      { silent: true },
    )
    const now = Date.now()
    token.value = result.token
    loginAt.value = now
    lastActivityAt.value = now
    profile.value = result.admin
    notice.value = null
    persist()
    return result.admin
  }

  /** 刷新页面后取回当前账号；会话已失效则清掉 */
  function ensureProfile(): Promise<AdminProfile | null> {
    if (profile.value) return Promise.resolve(profile.value)
    if (!token.value) return Promise.resolve(null)
    loadingProfile ??= get<AdminProfile>('/auth/me', undefined, { silent: true })
      .then((p) => (profile.value = p))
      .catch(() => {
        // 失效时 onExpired 已清掉；网络异常也回登录页，避免卡在空白页
        if (token.value) clear('无法获取账号信息，请重新登录')
        return null
      })
      .finally(() => (loadingProfile = null))
    return loadingProfile
  }

  async function logout() {
    try {
      if (token.value) await post('/auth/logout', undefined, { silent: true })
    } catch {
      // 网络不通也照样本地退出
    }
    clear(null)
  }

  async function changePassword(oldPassword: string, newPassword: string) {
    await put('/auth/password', { oldPassword, newPassword }, { silent: true })
    // 修改密码保留当前会话、作废其他会话
    if (profile.value) profile.value = { ...profile.value, mustChangePassword: false }
  }

  function can(permission: string) {
    return canDo(profile.value, permission)
  }

  /** 会话失效（服务端 10002 或前端判定超时） */
  function expire(message: string) {
    if (token.value) clear(message)
  }

  setAuthHooks({
    token: () => token.value,
    onActivity: () => {
      lastActivityAt.value = Date.now()
      persist()
    },
    onExpired: (message) => expire(message || '登录已过期，请重新登录'),
    onMustChangePassword: () => {
      if (profile.value) profile.value = { ...profile.value, mustChangePassword: true }
    },
  })

  return {
    token,
    profile,
    loginAt,
    lastActivityAt,
    notice,
    loggedIn,
    login,
    ensureProfile,
    logout,
    changePassword,
    can,
    expire,
  }
})
