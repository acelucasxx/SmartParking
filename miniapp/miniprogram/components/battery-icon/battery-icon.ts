import { battery } from '../../utils/format'

/** 电量图标：4 格 + 百分比；未上报显示“—”，≤ 20% 变红 */
Component({
  properties: {
    pct: { type: null, value: null },
    showText: { type: Boolean, value: true },
  },
  data: { text: '—', bars: -1, low: false, slots: [1, 2, 3, 4] },
  observers: {
    pct(pct: number | null) {
      const b = battery(pct)
      this.setData({ text: b.text, bars: b.bars, low: b.low })
    },
  },
})
