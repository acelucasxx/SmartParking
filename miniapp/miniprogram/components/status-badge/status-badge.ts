import type { GateStatus, OnlineState } from '../../api/types'
import { gateBadge, onlineBadge } from '../../utils/format'

/**
 * 状态徽标。
 * - kind="online"（默认）：在线 / 休眠 / 离线，停用与故障优先
 * - kind="gate"：闸杆状态
 */
Component({
  properties: {
    kind: { type: String, value: 'online' },
    enabled: { type: Boolean, value: true },
    onlineState: { type: String, value: 'OFFLINE' },
    gateStatus: { type: String, value: 'UNKNOWN' },
  },
  data: { label: '', tone: 'muted' },
  observers: {
    'kind, enabled, onlineState, gateStatus'(kind: string, enabled: boolean, online: string, gate: string) {
      const badge =
        kind === 'gate'
          ? gateBadge(gate as GateStatus)
          : onlineBadge(enabled, online as OnlineState, gate as GateStatus)
      this.setData({ label: badge.label, tone: badge.tone })
    },
  },
})
