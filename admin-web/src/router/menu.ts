import type { AdminRole } from '@/stores/auth'

export interface MenuItem {
  name: string
  path: string
  title: string
  /** 可见角色；为空表示全部角色可见。对应 PRD 第 7 章“使用角色”。 */
  roles?: AdminRole[]
  /** 对应开发任务号，页面实现后可删除。 */
  task: string
}

export const menu: MenuItem[] = [
  { name: 'dashboard', path: '/dashboard', title: '仪表盘', task: 'ADM-09' },
  {
    name: 'device-import',
    path: '/devices/import',
    title: '设备入库',
    roles: ['SUPER_ADMIN', 'OPS'],
    task: 'ADM-02',
  },
  {
    name: 'devices',
    path: '/devices',
    title: '设备管理',
    roles: ['SUPER_ADMIN', 'OPS', 'SUPPORT'],
    task: 'ADM-03',
  },
  {
    name: 'users',
    path: '/users',
    title: '用户管理',
    roles: ['SUPER_ADMIN', 'SUPPORT'],
    task: 'ADM-07',
  },
  {
    name: 'commands',
    path: '/commands',
    title: '指令审计',
    roles: ['SUPER_ADMIN', 'OPS', 'SUPPORT'],
    task: 'ADM-04',
  },
  {
    name: 'alarms',
    path: '/alarms',
    title: '告警中心',
    roles: ['SUPER_ADMIN', 'OPS'],
    task: 'ADM-05',
  },
  {
    name: 'settings',
    path: '/settings',
    title: '系统设置',
    roles: ['SUPER_ADMIN'],
    task: 'ADM-08',
  },
]
