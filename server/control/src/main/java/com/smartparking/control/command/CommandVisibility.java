package com.smartparking.control.command;

import com.smartparking.control.access.DeviceGrant;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 指令对当前用户是否可见（PRD 4 权限表“查看操作记录”）：主人看自成为主人（{@code device.owner_since}）起的全部指令和本人发起的指令；
 * 被授权人只看本人发起的。不可见与不存在同样处理，不透露他人的操作。
 */
public class CommandVisibility {

    private final JdbcClient jdbc;

    public CommandVisibility(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean visible(DeviceGrant grant, long commandId) {
        return jdbc.sql(
                        """
                        SELECT c.user_id = :user
                               OR (:owner AND (d.owner_since IS NULL OR c.issued_at >= d.owner_since))
                          FROM control_command c
                          JOIN device d ON d.id = c.device_id
                         WHERE c.id = :command AND c.device_id = :device""")
                .param("user", grant.userId())
                .param("owner", grant.isOwner())
                .param("command", commandId)
                .param("device", grant.deviceId())
                .query(Boolean.class)
                .optional()
                .map(Boolean.TRUE::equals)
                .orElse(false);
    }
}
