import { AGREEMENT_IS_PLACEHOLDER, AGREEMENT_VERSION, agreement } from '../../content/agreements'

/** 用户协议 / 隐私政策（?type=user|privacy） */
Page({
  data: { title: '', sections: [] as Array<{ heading: string; body: string }>, placeholder: AGREEMENT_IS_PLACEHOLDER, version: AGREEMENT_VERSION },
  onLoad(query: Record<string, string | undefined>) {
    const doc = agreement(query.type)
    wx.setNavigationBarTitle({ title: doc.title })
    this.setData({ title: doc.title, sections: doc.sections })
  },
})
