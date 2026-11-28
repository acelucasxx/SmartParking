package com.smartparking.admin.ops;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.AdminPrincipal;
import com.smartparking.common.web.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 封禁 / 解封 C 端用户（PRD 5.8②③）。必须填原因（规则同 {@link AdminDeviceOps#reason}），原因写入操作日志。
 *
 * <p>只改 {@code app_user.status}，不动绑定与授权：封禁不自动解绑，解封后一切恢复原状。封禁的效果由各处按状态判定：
 *
 * <ul>
 *   <li>登录与全部 C 端接口拒绝（10010），每个请求都查库，即时生效；退出登录除外。
 *   <li>其作为主人的设备，被授权人在有效期内照常使用；其作为被授权人的授权不变但无法使用。
 *   <li>其发出、尚未被接受的邀请与转让卡片暂不能接受（显示为已失效）；解封后在有效期内恢复。
 * </ul>
 */
public class AdminUserOps {

    private static final Logger log = LoggerFactory.getLogger(AdminUserOps.class);

    private final JdbcClient jdbc;

    public AdminUserOps(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 重复封禁、重复解封视为成功；用户不存在 10004 */
    public void setBanned(AdminPrincipal admin, long userId, boolean banned, String rawReason) {
        String reason = AdminDeviceOps.reason(rawReason);
        String status = banned ? "BANNED" : "ACTIVE";
        int changed = jdbc.sql("UPDATE app_user SET status = ? WHERE id = ? AND status <> ?")
                .params(status, userId, status)
                .update();
        if (changed == 0
                && jdbc.sql("SELECT COUNT(*) FROM app_user WHERE id = ?")
                                .param(userId)
                                .query(Integer.class)
                                .single()
                        == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }
        AuditTrail.detail("changed", changed > 0);
        log.warn("后台 {} {}用户 {}：{}", admin.username(), banned ? "封禁" : "解封", userId, reason);
    }
}
