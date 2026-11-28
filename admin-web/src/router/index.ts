import { createRouter, createWebHistory, type RouteLocationNormalized } from 'vue-router'
import AdminLayout from '@/layouts/AdminLayout.vue'
import { FORBIDDEN_PATH, LOGIN_PATH, MENU, PASSWORD_PATH, guard } from '@/auth/access'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: LOGIN_PATH,
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      path: '/',
      component: AdminLayout,
      children: [
        ...MENU.map((m) => ({
          path: m.path.slice(1),
          name: m.name,
          component: () => import('@/views/PlaceholderView.vue'),
          meta: { title: m.title, task: m.task, permission: m.permission },
        })),
        {
          path: PASSWORD_PATH.slice(1),
          name: 'password',
          component: () => import('@/views/PasswordView.vue'),
          meta: { title: '修改密码' },
        },
        {
          path: FORBIDDEN_PATH.slice(1),
          name: 'forbidden',
          component: () => import('@/views/ForbiddenView.vue'),
          meta: { title: '无权限' },
        },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

export function target(to: RouteLocationNormalized) {
  return {
    path: to.path,
    fullPath: to.fullPath,
    public: to.meta.public,
    permission: to.meta.permission,
  }
}

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  // 刷新页面后只有 token：先取回账号再判断
  const profile = await auth.ensureProfile()
  const result = guard(target(to), profile)
  return result.kind === 'allow' ? true : { path: result.path, query: result.query, replace: true }
})

router.afterEach((to) => {
  document.title = to.meta.title
    ? `${to.meta.title} - 智能车位道闸管理后台`
    : '智能车位道闸管理后台'
})

export default router
