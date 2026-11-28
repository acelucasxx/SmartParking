// ADM-01：菜单与按钮权限、路由守卫、登录回跳
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  FORBIDDEN_PATH,
  LOGIN_PATH,
  MENU,
  PASSWORD_PATH,
  SYSTEM_PERMISSION,
  can,
  guard,
  homePath,
  safeRedirect,
  visibleMenu,
} from '../src/auth/access.ts'
import type { AdminProfile } from '../src/auth/types.ts'

// 与 V10 迁移写入的角色权限点一致
const OPS = [
  'dashboard:view',
  'device:view',
  'device:import',
  'device:disable',
  'device:replace-part',
  'device:self-check',
  'device:force-unbind',
  'device:reset-code',
  'command:view',
  'command:export',
  'alarm:view',
  'alarm:handle',
]
const SUPPORT = [
  'dashboard:view',
  'device:view',
  'device:force-unbind',
  'device:reset-code',
  'user:view',
  'user:ban',
  'command:view',
  'command:export',
]

function admin(
  role: AdminProfile['role'],
  permissions: string[],
  extra: Partial<AdminProfile> = {},
): AdminProfile {
  return {
    id: 1,
    username: 'u',
    displayName: 'U',
    role,
    permissions,
    mustChangePassword: false,
    ...extra,
  }
}
const superAdmin = admin('SUPER_ADMIN', ['*'])
const ops = admin('OPS', OPS)
const support = admin('SUPPORT', SUPPORT)

test('权限点：超级管理员 * 拥有全部；其余按列表；未登录没有任何权限', () => {
  assert.equal(can(superAdmin, 'anything'), true)
  assert.equal(can(ops, 'device:import'), true)
  assert.equal(can(ops, 'user:ban'), false)
  assert.equal(can(support, 'user:ban'), true)
  assert.equal(can(null, 'dashboard:view'), false)
  assert.equal(can(undefined, '*'), false)
  assert.equal(can(admin('OPS', []), '*'), false, '没有 * 时不能靠查询 * 获得全部权限')
})

test('菜单按角色：与 PRD 第 7 章“使用角色”一致', () => {
  const names = (p: AdminProfile | null) => visibleMenu(p).map((m) => m.name)
  assert.deepEqual(
    names(superAdmin),
    MENU.map((m) => m.name),
  )
  assert.deepEqual(names(ops), ['dashboard', 'device-import', 'devices', 'commands', 'alarms'])
  assert.deepEqual(names(support), ['dashboard', 'devices', 'users', 'commands'])
  assert.deepEqual(names(null), [])
  // 系统设置只有超级管理员：这个权限点不在任何其他角色里
  assert.ok(!OPS.includes(SYSTEM_PERMISSION) && !SUPPORT.includes(SYSTEM_PERMISSION))
  assert.equal(MENU.find((m) => m.name === 'settings')?.permission, SYSTEM_PERMISSION)
})

test('默认页：第一个有权限的菜单；一个都没有进无权限页', () => {
  assert.equal(homePath(ops), '/dashboard')
  assert.equal(homePath(admin('SUPPORT', ['user:view'])), '/users')
  assert.equal(homePath(admin('OPS', [])), FORBIDDEN_PATH)
  assert.equal(homePath(null), FORBIDDEN_PATH)
})

test('登录回跳只接受站内路径，不回登录页、修改密码页', () => {
  const home = '/dashboard'
  assert.equal(safeRedirect('/devices?sn=SP1#x', home), '/devices?sn=SP1#x')
  for (const bad of [
    'https://evil.example',
    '//evil.example/x',
    '/\\evil.example',
    'devices',
    '',
    undefined,
    null,
    1,
    ['/devices'],
  ]) {
    assert.equal(safeRedirect(bad, home), home, String(bad))
  }
  assert.equal(safeRedirect('/login?redirect=/x', home), home)
  assert.equal(safeRedirect('/password', home), home)
  assert.equal(safeRedirect('/password#a', home), home)
  assert.equal(safeRedirect('/passwords', home), '/passwords')
})

test('路由守卫：未登录去登录页并带上来源；已登录不能回登录页', () => {
  const to = (fullPath: string, extra: { public?: boolean; permission?: string } = {}) => ({
    path: fullPath.split('?')[0],
    fullPath,
    ...extra,
  })
  assert.deepEqual(guard(to('/devices?page=2', { permission: 'device:view' }), null), {
    kind: 'redirect',
    path: LOGIN_PATH,
    query: { redirect: '/devices?page=2' },
  })
  assert.deepEqual(
    guard(to('/'), null),
    { kind: 'redirect', path: LOGIN_PATH },
    '根路径不带 redirect',
  )
  assert.deepEqual(guard(to('/password'), null), {
    kind: 'redirect',
    path: LOGIN_PATH,
    query: { redirect: '/password' },
  })
  assert.deepEqual(guard(to('/login', { public: true }), null), { kind: 'allow' })

  assert.deepEqual(guard(to('/login', { public: true }), ops), {
    kind: 'redirect',
    path: '/dashboard',
  })
  assert.deepEqual(guard(to('/'), support), { kind: 'redirect', path: '/dashboard' })
  assert.deepEqual(guard(to('/'), admin('SUPPORT', ['user:view'])), {
    kind: 'redirect',
    path: '/users',
  })
})

test('路由守卫：没有权限点进无权限页；无需权限的页登录即可进', () => {
  const to = (path: string, permission?: string) => ({ path, fullPath: path, permission })
  assert.deepEqual(guard(to('/users', 'user:view'), ops), {
    kind: 'redirect',
    path: FORBIDDEN_PATH,
  })
  assert.deepEqual(guard(to('/settings', SYSTEM_PERMISSION), support), {
    kind: 'redirect',
    path: FORBIDDEN_PATH,
  })
  assert.deepEqual(guard(to('/settings', SYSTEM_PERMISSION), superAdmin), { kind: 'allow' })
  assert.deepEqual(guard(to('/users', 'user:view'), support), { kind: 'allow' })
  assert.deepEqual(guard(to('/password'), ops), { kind: 'allow' })
  assert.deepEqual(guard(to(FORBIDDEN_PATH), ops), { kind: 'allow' })
})

test('路由守卫：仍在用临时密码时只能进修改密码页', () => {
  const temp = admin('SUPER_ADMIN', ['*'], { mustChangePassword: true })
  const to = (path: string, extra: { public?: boolean; permission?: string } = {}) => ({
    path,
    fullPath: path,
    ...extra,
  })
  assert.deepEqual(guard(to('/password'), temp), { kind: 'allow' })
  for (const path of ['/dashboard', '/', '/403', '/settings']) {
    assert.deepEqual(
      guard(to(path, { permission: 'dashboard:view' }), temp),
      { kind: 'redirect', path: PASSWORD_PATH },
      path,
    )
  }
  assert.deepEqual(guard(to('/login', { public: true }), temp), {
    kind: 'redirect',
    path: PASSWORD_PATH,
  })
})
