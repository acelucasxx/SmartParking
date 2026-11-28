import { parseScan } from './flow'

/**
 * 调起扫码并取 SN；用户取消返回 null，识别不了提示后返回 null。
 * 只允许相机扫码：从相册选图容易扫到别人发来的码，绑定场景应当在设备旁边。
 */
export function scanSn(): Promise<string | null> {
  return new Promise((resolve) => {
    wx.scanCode({
      onlyFromCamera: true,
      scanType: ['qrCode'],
      success: (res) => {
        const sn = parseScan(res.result)
        if (!sn) wx.showToast({ title: '无法识别，请扫描车位锁机身上的二维码', icon: 'none' })
        resolve(sn)
      },
      // 取消或失败：不打扰
      fail: () => resolve(null),
    })
  })
}
