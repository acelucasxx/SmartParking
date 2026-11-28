import type { Transport } from '../api/client'
import { createMockServer } from './server'

/** mock 模式的传输层：模拟延迟后交给内存里的模拟后端 */
export function mockTransport(delayMs: number): Transport {
  const server = createMockServer()
  return (request) =>
    new Promise((resolve) => {
      setTimeout(() => resolve(server.handle(request)), delayMs)
    })
}
