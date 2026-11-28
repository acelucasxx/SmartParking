// 消息中心与我的（MP-11）：消息展示、点开去向、角标、分页合并、本地标记已读、帮助内容
const test = require('node:test')
const assert = require('node:assert/strict')
const m = require('../.test-build/messages/messages')
const { HELP } = require('../.test-build/content/help')

const T0 = Date.parse('2026-11-28T10:00:00+08:00')
const msg = (patch = {}) => ({
  id: 1,
  type: 'GRANT',
  deviceId: 2,
  title: '你已获得「公司车位」的使用权限',
  content: '主人 李女士 授权你使用该车位。',
  read: false,
  createdAt: '2026-11-28T09:00:00+08:00',
  ...patch,
})

test('每种消息类型都有标签', () => {
  assert.deepEqual(Object.keys(m.TYPE_LABEL).sort(), ['EXPIRE', 'FAULT', 'GRANT', 'REVOKE', 'SYSTEM', 'TRANSFER', 'UNBIND'])
  assert.equal(m.TYPE_LABEL.FAULT.tone, 'danger')
})

test('消息展示：类型、时间、未读、去向', () => {
  assert.deepEqual(m.messageRow(msg(), T0), {
    id: 1,
    label: '授权',
    tone: 'success',
    title: '你已获得「公司车位」的使用权限',
    content: '主人 李女士 授权你使用该车位。',
    time: '今天 09:00',
    unread: true,
    deviceId: 2,
  })
  assert.equal(m.messageRow(msg({ read: true }), T0).unread, false)
  // 未知类型按系统消息显示，不报错
  assert.equal(m.messageRow(msg({ type: 'NEW_TYPE' }), T0).label, '系统')
})

test('点开去向：设备仍可用的进控制页，已失去权限的只标记已读', () => {
  assert.equal(m.targetDevice(msg({ type: 'GRANT' })), 2)
  assert.equal(m.targetDevice(msg({ type: 'FAULT', deviceId: 1 })), 1)
  assert.equal(m.targetDevice(msg({ type: 'TRANSFER', title: '你已成为「吴先生的车位」的主人', deviceId: 41 })), 41)
  assert.equal(m.targetDevice(msg({ type: 'TRANSFER', title: '「家里车位」已转让', deviceId: 1 })), 0)
  for (const type of ['REVOKE', 'EXPIRE', 'UNBIND', 'SYSTEM']) assert.equal(m.targetDevice(msg({ type })), 0, type)
  assert.equal(m.targetDevice(msg({ deviceId: null })), 0)
})

test('角标：0 不显示，超过 99 显示 99+', () => {
  assert.equal(m.badgeText(0), null)
  assert.equal(m.badgeText(-1), null)
  assert.equal(m.badgeText(NaN), null)
  assert.equal(m.badgeText(1), '1')
  assert.equal(m.badgeText(99), '99')
  assert.equal(m.badgeText(100), '99+')
})

test('分页合并去重；本地标记已读（指定或全部），不改原数组', () => {
  const a = [msg({ id: 3 }), msg({ id: 2 })]
  assert.deepEqual(m.mergeMessages(a, [msg({ id: 2 }), msg({ id: 1 })]).map((x) => x.id), [3, 2, 1])
  const list = [msg({ id: 3 }), msg({ id: 2, read: true }), msg({ id: 1 })]
  const one = m.markRead(list, [3])
  assert.deepEqual(one.map((x) => x.read), [true, true, false])
  assert.equal(list[0].read, false, '原数组不变')
  assert.equal(one[1], list[1], '已读的不复制')
  const all = m.markRead(list, 'all')
  assert.deepEqual(all.map((x) => x.read), [true, true, true])
  assert.equal(all[1], list[1], '已读的标记全部已读时也不复制')
  assert.deepEqual(m.markRead(list, []).map((x) => x.read), [false, true, false])
})

test('帮助内容覆盖 PRD 的主要问题', () => {
  const text = HELP.map((h) => h.q + h.a).join('')
  for (const k of ['激活码', '5 次', '30 分钟', '已有主人', '离线', '15 秒', '邀请家人', '转让', '解绑', '电量']) assert.ok(text.includes(k), k)
  for (const h of HELP) assert.ok(h.q.trim() && h.a.trim())
})
