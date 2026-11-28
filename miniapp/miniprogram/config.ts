/**
 * 环境配置。正式域名在 EXT-11 备案完成后填入，并在小程序后台配置 request 合法域名。
 *
 * - `mock: true`：不连后端，接口由 mock/ 下的模拟数据返回，用于没有后端时开发页面。
 * - `mock: false`：连 `apiBase`。本地后端用 `smartparking.wechat.mode=mock` 启动时任何 wx.login 的 code 都能登录。
 */
export const config = {
  apiBase: 'http://localhost:8080/api/app',
  requestTimeoutMs: 10_000,
  mock: false,
  /** mock 模式下每个请求的模拟延迟，便于看到加载态 */
  mockDelayMs: 300,
}
