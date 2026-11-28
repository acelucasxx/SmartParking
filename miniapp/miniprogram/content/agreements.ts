/**
 * 用户协议与隐私政策。
 *
 * **正式文本待 EXT-16（法务）提供，这里只是占位**：不要把占位内容当作正式协议上线。正式文本到位后替换 sections，
 * 并把 AGREEMENT_VERSION 改成新版本号——已同意旧版本的用户会被要求重新同意。
 */

export const AGREEMENT_VERSION = 'draft-0'

/** 正式文本未到位时为 true，页面顶部显示“占位文本”提示 */
export const AGREEMENT_IS_PLACEHOLDER = true

export type AgreementType = 'user' | 'privacy'

export interface Agreement {
  title: string
  sections: Array<{ heading: string; body: string }>
}

const PLACEHOLDER = '正式条款待法务提供（EXT-16）。'

export const AGREEMENTS: Record<AgreementType, Agreement> = {
  user: {
    title: '用户协议',
    sections: [
      { heading: '服务内容', body: `${PLACEHOLDER}需覆盖：车位道闸的远程控制、蓝牙近场控制、授权家人使用、设备转让与解绑。` },
      { heading: '账号与设备', body: `${PLACEHOLDER}需覆盖：激活码与绑定、主人与被授权人的权利、禁用与封禁。` },
      { heading: '责任与免责', body: `${PLACEHOLDER}` },
    ],
  },
  privacy: {
    title: '隐私政策',
    sections: [
      {
        heading: '我们收集的信息',
        body: `${PLACEHOLDER}需明示（PRD 9 隐私合规）：手机号（绑定设备、账号找回，加密存储、界面脱敏）；蓝牙（近场控制设备）；位置（蓝牙扫描所需）。`,
      },
      { heading: '信息的使用与共享', body: `${PLACEHOLDER}需明示：被授权人只能看到主人昵称，看不到手机号。` },
      { heading: '你的权利', body: `${PLACEHOLDER}` },
    ],
  },
}

export function agreement(type: string | undefined): Agreement {
  return type === 'privacy' ? AGREEMENTS.privacy : AGREEMENTS.user
}
