/** 契约 openapi-admin.yaml 的 AdminRole / AdminProfile / AdminLoginResult */
export type AdminRole = 'SUPER_ADMIN' | 'OPS' | 'SUPPORT'

export interface AdminProfile {
  id: number
  username: string
  displayName: string
  role: AdminRole
  /** 权限点编码，用于按钮级控制；超级管理员为 ["*"] */
  permissions: string[]
  /** 仍在用临时密码：必须先修改密码，其余接口服务端返回 10003 */
  mustChangePassword: boolean
}

export interface AdminLoginResult {
  token: string
  expiresAt: string
  admin: AdminProfile
}

export const ROLE_LABEL: Record<AdminRole, string> = {
  SUPER_ADMIN: '超级管理员',
  OPS: '运维',
  SUPPORT: '客服',
}
