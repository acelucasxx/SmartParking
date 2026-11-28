import 'vue-router'

declare module 'vue-router' {
  interface RouteMeta {
    /** 公开页（登录页）不需要登录 */
    public?: boolean
    title?: string
    task?: string
    /** 进入需要的权限点（auth/access.ts） */
    permission?: string
    /** 不在菜单里的页面，侧边栏高亮哪个菜单 */
    activeMenu?: string
  }
}
