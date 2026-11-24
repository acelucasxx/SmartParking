import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'
import { useAuthStore } from '@/stores/auth'

/** 与服务端 ApiResponse 对应。code 为 0 表示成功。 */
export interface ApiResponse<T> {
  code: number
  message: string
  data?: T
}

const http = axios.create({ baseURL: '/api/admin', timeout: 10_000 })

http.interceptors.request.use((config) => {
  const { token } = useAuthStore()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (resp) => {
    const body = resp.data as ApiResponse<unknown>
    if (body.code !== 0) {
      ElMessage.error(body.message)
      return Promise.reject(body)
    }
    return resp
  },
  (error) => {
    const body = error.response?.data as ApiResponse<unknown> | undefined
    if (error.response?.status === 401) {
      useAuthStore().setToken(null)
      router.push({ name: 'login' })
    }
    ElMessage.error(body?.message ?? '网络异常，请稍后重试')
    return Promise.reject(error)
  },
)

export async function request<T>(config: Parameters<typeof http.request>[0]): Promise<T> {
  const resp = await http.request<ApiResponse<T>>(config)
  return resp.data.data as T
}
