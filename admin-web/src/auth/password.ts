/**
 * 新密码规则，与服务端一致（docs/biz/admin-auth.md“修改密码”）：10–64 位、同时含字母与数字、只含可见 ASCII 字符、不能与原密码相同。
 * 前端先校验，提示更及时；服务端仍会校验（10001）。
 */
export const PASSWORD_MIN = 10
export const PASSWORD_MAX = 64

/** @returns 不合法时的提示，合法为 null */
export function passwordProblem(
  newPassword: string,
  oldPassword: string,
  confirm: string,
): string | null {
  if (!newPassword) return '请输入新密码'
  if (newPassword.length < PASSWORD_MIN || newPassword.length > PASSWORD_MAX)
    return `新密码长度需为 ${PASSWORD_MIN}–${PASSWORD_MAX} 位`
  if (!/^[\x21-\x7e]+$/.test(newPassword)) return '新密码只能包含字母、数字和英文符号，不能有空格'
  if (!/[A-Za-z]/.test(newPassword) || !/\d/.test(newPassword)) return '新密码需同时包含字母和数字'
  if (newPassword === oldPassword) return '新密码不能与原密码相同'
  if (newPassword !== confirm) return '两次输入的新密码不一致'
  return null
}
