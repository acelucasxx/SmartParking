import { config } from '../config'

/** 与服务端 ApiResponse 对应。code 为 0 表示成功。 */
export interface ApiResponse<T> {
  code: number
  message: string
  data?: T
}

export class ApiError extends Error {
  constructor(
    public readonly code: number,
    message: string,
  ) {
    super(message)
  }
}

const TOKEN_KEY = 'sp_token'

export function getToken(): string | undefined {
  return wx.getStorageSync<string>(TOKEN_KEY) || undefined
}

export function setToken(token: string | null): void {
  if (token) wx.setStorageSync(TOKEN_KEY, token)
  else wx.removeStorageSync(TOKEN_KEY)
}

type Method = 'GET' | 'POST' | 'PUT' | 'DELETE'

/**
 * 统一请求封装：带 Token、按 code 判定成功、错误抛 ApiError。
 * 是否弹 toast 由调用方决定，因为控制页需要把错误显示在按钮附近。
 */
export function request<T>(method: Method, path: string, data?: object): Promise<T> {
  const token = getToken()
  return new Promise((resolve, reject) => {
    wx.request<ApiResponse<T>>({
      url: config.apiBase + path,
      method,
      data,
      timeout: config.requestTimeoutMs,
      header: token ? { Authorization: `Bearer ${token}` } : {},
      success(res) {
        const body = res.data
        if (res.statusCode === 401) setToken(null)
        if (body && typeof body === 'object' && body.code === 0) resolve(body.data as T)
        else reject(new ApiError(body?.code ?? res.statusCode, body?.message ?? '服务异常'))
      },
      fail() {
        reject(new ApiError(-1, '网络异常，请检查网络后重试'))
      },
    })
  })
}
