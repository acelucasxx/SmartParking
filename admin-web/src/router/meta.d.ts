import 'vue-router'
import type { AdminRole } from '@/stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    public?: boolean
    title?: string
    task?: string
    roles?: AdminRole[]
  }
}
