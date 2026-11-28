import type { AdminProfile } from './types.ts'

/**
 * 菜单与按钮权限（ADM-01）。前端只按登录结果里的权限点（`role.permissions`，docs/biz/admin-auth.md）隐藏菜单和按钮，
 * 真正的拦截在服务端（接口的 `@AdminRoles`）。
 *
 * 本文件不依赖 Vue，可在 Node 里直接单测（test/access.test.ts）。
 */

/** 超级管理员的权限点 */
export const ALL = '*'

/** 系统设置只有超级管理员能进：这个权限点不发给其他角色 */
export const SYSTEM_PERMISSION = 'system:manage'

export function can(
  profile: Pick<AdminProfile, 'permissions'> | null | undefined,
  permission: string,
): boolean {
  if (!profile) return false
  return profile.permissions.includes(ALL) || profile.permissions.includes(permission)
}

export interface MenuItem {
  name: string
  path: string
  title: string
  /** 进入该页需要的权限点 */
  permission: string
  /** 对应开发任务号，页面实现后可删除 */
  task: string
}

/** 侧边菜单，顺序即显示顺序。权限点与 V10 迁移写入的角色权限一致（PRD 第 7 章“使用角色”） */
export const MENU: MenuItem[] = [
  {
    name: 'dashboard',
    path: '/dashboard',
    title: '仪表盘',
    permission: 'dashboard:view',
    task: 'ADM-09',
  },
  {
    name: 'device-import',
    path: '/devices/import',
    title: '设备入库',
    permission: 'device:import',
    task: 'ADM-02',
  },
  {
    name: 'devices',
    path: '/devices',
    title: '设备管理',
    permission: 'device:view',
    task: 'ADM-03',
  },
  { name: 'users', path: '/users', title: '用户管理', permission: 'user:view', task: 'ADM-07' },
  {
    name: 'commands',
    path: '/commands',
    title: '指令审计',
    permission: 'command:view',
    task: 'ADM-04',
  },
  { name: 'alarms', path: '/alarms', title: '告警中心', permission: 'alarm:view', task: 'ADM-05' },
  {
    name: 'settings',
    path: '/settings',
    title: '系统设置',
    permission: SYSTEM_PERMISSION,
    task: 'ADM-08',
  },
]

export function visibleMenu(
  profile: Pick<AdminProfile, 'permissions'> | null | undefined,
  menu = MENU,
): MenuItem[] {
  return menu.filter((m) => can(profile, m.permission))
}

/** 登录后的默认页：第一个有权限的菜单；一个都没有时进“无权限”页 */
export function homePath(
  profile: Pick<AdminProfile, 'permissions'> | null | undefined,
  menu = MENU,
): string {
  return visibleMenu(profile, menu)[0]?.path ?? FORBIDDEN_PATH
}

export const LOGIN_PATH = '/login'
export const PASSWORD_PATH = '/password'
export const FORBIDDEN_PATH = '/403'

/**
 * 登录后回跳的地址：只接受站内路径（/ 开头、不是 //、不含反斜杠），不回到登录页，其余回默认页。
 * 防止 ?redirect= 被用来跳到外站。
 */
export function safeRedirect(raw: unknown, fallback: string): string {
  if (typeof raw !== 'string' || !raw.startsWith('/') || raw.startsWith('//') || raw.includes('\\'))
    return fallback
  const path = raw.split(/[?#]/)[0]
  if (path === LOGIN_PATH || path === PASSWORD_PATH) return fallback
  return raw
}

export interface GuardTarget {
  path: string
  fullPath: string
  /** 公开页（登录页）不需要登录 */
  public?: boolean
  /** 进入需要的权限点；没有则登录即可进 */
  permission?: string
}

export type GuardResult =
  { kind: 'allow' } | { kind: 'redirect'; path: string; query?: Record<string, string> }

/**
 * 路由守卫的判定：
 * - 未登录访问非公开页 → 登录页，带上 redirect
 * - 已登录访问登录页 → 默认页
 * - 仍在用临时密码 → 只能进修改密码页
 * - 没有权限点 → 无权限页
 */
export function guard(to: GuardTarget, profile: AdminProfile | null): GuardResult {
  if (!profile) {
    if (to.public) return { kind: 'allow' }
    const root = to.fullPath === '/' || to.fullPath === ''
    return root
      ? { kind: 'redirect', path: LOGIN_PATH }
      : { kind: 'redirect', path: LOGIN_PATH, query: { redirect: to.fullPath } }
  }
  if (profile.mustChangePassword) {
    return to.path === PASSWORD_PATH ? { kind: 'allow' } : { kind: 'redirect', path: PASSWORD_PATH }
  }
  if (to.path === LOGIN_PATH || to.path === '/')
    return { kind: 'redirect', path: homePath(profile) }
  if (to.permission && !can(profile, to.permission))
    return { kind: 'redirect', path: FORBIDDEN_PATH }
  return { kind: 'allow' }
}
