import { battery } from '../../utils/format'

/** 电量图标：4 格 + 百分比；未上报显示“—”；不做低电量提醒（PRD 6.2） */
Component({
  properties: {
    pct: { type: null, value: null },
    showText: { type: Boolean, value: true },
  },
  data: { text: '—', bars: -1, slots: [1, 2, 3, 4] },
  observers: {
    pct(pct: number | null) {
      const b = battery(pct)
      this.setData({ text: b.text, bars: b.bars })
    },
  },
})
