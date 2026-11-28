import { config } from '../config'
import { mockTransport } from '../mock/transport'
import { ApiClient, buildQuery, type HttpRequest, type HttpResponse, type KeyValueStore, type Transport } from './client'

/** 真实请求：wx.request。HTTP 4xx / 5xx 也走 success，由信封里的 code 判定结果 */
export function wxTransport(baseUrl: string, timeoutMs: number): Transport {
  return (request: HttpRequest) =>
    new Promise<HttpResponse>((resolve, reject) => {
      wx.request({
        url: baseUrl + request.path + buildQuery(request.query),
        // 不支持 PATCH，ApiClient 已改写成 POST + X-HTTP-Method-Override
        method: request.method as 'GET' | 'POST' | 'PUT' | 'DELETE',
        data: request.data as WechatMiniprogram.IAnyObject | undefined,
        header: { 'Content-Type': 'application/json', ...request.headers },
        timeout: timeoutMs,
        success: (res) => resolve({ status: res.statusCode, body: res.data }),
        fail: (err) => reject(new Error(err.errMsg)),
      })
    })
}

const wxStore: KeyValueStore = {
  get: (key) => wx.getStorageSync(key) || undefined,
  set: (key, value) => wx.setStorageSync(key, value),
  remove: (key) => wx.removeStorageSync(key),
}

function wxLoginCode(): Promise<string> {
  return new Promise((resolve, reject) => {
    wx.login({
      success: (res) => (res.code ? resolve(res.code) : reject(new Error(res.errMsg))),
      fail: (err) => reject(new Error(err.errMsg)),
    })
  })
}

let banned = false
const bannedListeners: Array<() => void> = []

/** 账号已停用（10010）时通知，只通知一次；MP-02 的停用提示页据此跳转 */
export function onBanned(listener: () => void): void {
  if (banned) listener()
  else bannedListeners.push(listener)
}

/** 全局唯一的接口客户端 */
export const api = new ApiClient({
  transport: config.mock ? mockTransport(config.mockDelayMs) : wxTransport(config.apiBase, config.requestTimeoutMs),
  store: wxStore,
  loginCode: wxLoginCode,
  onBanned: () => {
    if (banned) return
    banned = true
    bannedListeners.splice(0).forEach((listener) => listener())
  },
})

export { ApiError, ErrorCode, errorAction, isApiError } from './errors'
export type { ErrorAction } from './errors'
