import { HELP } from '../../content/help'

/** 帮助与常见问题：点问题展开答案；解决不了的联系客服 */
Page({
  data: { items: HELP.map((h, i) => ({ ...h, i, open: i === 0 })) },

  onToggle(e: WechatMiniprogram.BaseEvent) {
    const i = e.currentTarget.dataset.i as number
    this.setData({ [`items[${i}].open`]: !this.data.items[i].open })
  },
})
