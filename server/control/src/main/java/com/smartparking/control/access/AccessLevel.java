package com.smartparking.control.access;

/**
 * 设备类接口要求的权限级别（PRD 5.3①、5.8、9）。各接口取哪一级以接口契约的 {@code x-error-codes} 为准：列了 20002 的接口要求设备启用。
 *
 * <table>
 *   <caption>级别</caption>
 *   <tr><th>级别</th><th>谁可以</th><th>设备禁用时</th><th>典型接口</th></tr>
 *   <tr><td>VIEW</td><td>主人、有效期内的被授权人</td><td>可以</td><td>设备详情、状态、操作记录、退出授权</td></tr>
 *   <tr><td>CONTROL</td><td>主人、有效期内的被授权人</td><td>拒绝（20002）</td><td>抬杆 / 落杆、蓝牙票据</td></tr>
 *   <tr><td>OWNER</td><td>主人</td><td>可以</td><td>成员列表、撤销成员、取消邀请 / 转让、解绑、改名称</td></tr>
 *   <tr><td>OWNER_ENABLED</td><td>主人</td><td>拒绝（20002）</td><td>邀请、改有效期、发起转让</td></tr>
 * </table>
 */
public enum AccessLevel {
    VIEW(false, false),
    CONTROL(false, true),
    OWNER(true, false),
    OWNER_ENABLED(true, true);

    private final boolean ownerOnly;
    private final boolean requiresEnabled;

    AccessLevel(boolean ownerOnly, boolean requiresEnabled) {
        this.ownerOnly = ownerOnly;
        this.requiresEnabled = requiresEnabled;
    }

    public boolean ownerOnly() {
        return ownerOnly;
    }

    public boolean requiresEnabled() {
        return requiresEnabled;
    }
}
