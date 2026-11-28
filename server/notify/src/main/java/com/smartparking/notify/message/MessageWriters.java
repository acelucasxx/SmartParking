package com.smartparking.notify.message;

import com.smartparking.iot.DbTime;
import com.smartparking.member.invite.MemberGrantedEvent;
import com.smartparking.member.members.MemberEndedEvent;
import com.smartparking.member.members.MemberUpdatedEvent;
import com.smartparking.notify.alarm.AlarmRaisedEvent;
import com.smartparking.notify.alarm.AlarmType;
import com.smartparking.ownership.transfer.DeviceTransferredEvent;
import com.smartparking.ownership.unbind.DeviceUnboundEvent;
import com.smartparking.ownership.unbind.OwnershipRelease;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 站内消息写入点（PRD 5.5–5.8、6.1 页面 13）：订阅授权、转让、解绑、故障事件，给相关的人各写一条。
 *
 * <p>事件都在业务事务提交后发布；这里写入失败只记日志，不影响业务结果。
 */
public class MessageWriters {

    private static final Logger log = LoggerFactory.getLogger(MessageWriters.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    static final String DEFAULT_NICKNAME = "微信用户";

    private final MessageStore store;
    private final JdbcClient jdbc;

    public MessageWriters(MessageStore store, JdbcClient jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------ 授权

    /** 接受邀请：被授权人收到“获得权限”，主人收到“已接受邀请” */
    @EventListener
    public void onGranted(MemberGrantedEvent e) {
        safely("授权", () -> {
            String device = deviceName(e.deviceId());
            String period = period(e.memberId());
            store.write(
                    e.memberUserId(),
                    MessageType.GRANT,
                    e.deviceId(),
                    "你已获得「" + device + "」的使用权限",
                    nickname(e.ownerId()) + "邀请你使用该车位，" + period + "。");
            store.write(
                    e.ownerId(),
                    MessageType.GRANT,
                    e.deviceId(),
                    memberName(e.memberId(), e.memberUserId()) + "已接受邀请",
                    memberName(e.memberId(), e.memberUserId()) + "现在可以使用「" + device + "」，" + period + "。");
        });
    }

    /** 主人修改有效期：被授权人收到；只改备注不通知 */
    @EventListener
    public void onUpdated(MemberUpdatedEvent e) {
        if (!e.periodChanged()) {
            return;
        }
        safely(
                "修改有效期",
                () -> store.write(
                        e.userId(),
                        MessageType.GRANT,
                        e.deviceId(),
                        "「" + deviceName(e.deviceId()) + "」的使用期限已调整",
                        "主人已将你的使用期限调整为" + range(e.validFrom(), e.validTo(), e.at()) + "。"));
    }

    /** 撤销、到期通知被授权人；退出通知主人 */
    @EventListener
    public void onEnded(MemberEndedEvent e) {
        safely("授权结束", () -> {
            String device = deviceName(e.deviceId());
            switch (e.reason()) {
                case REVOKED -> store.write(
                        e.userId(),
                        MessageType.REVOKE,
                        e.deviceId(),
                        "你在「" + device + "」的使用权限已被撤销",
                        "主人已撤销你的使用权限，该车位已从“我的车位”中移除。");
                case EXPIRED -> store.write(
                        e.userId(),
                        MessageType.EXPIRE,
                        e.deviceId(),
                        "你在「" + device + "」的使用权限已到期",
                        "使用期限已到，该车位已从“我的车位”中移除；如需继续使用，请联系主人重新邀请。");
                case QUIT -> {
                    if (e.ownerId() > 0) {
                        String who = memberName(e.memberId(), e.userId());
                        store.write(
                                e.ownerId(),
                                MessageType.REVOKE,
                                e.deviceId(),
                                who + "已退出「" + device + "」的授权",
                                who + "已主动退出授权，名额已释放。");
                    }
                }
            }
        });
    }

    // ------------------------------------------------------------ 转让与解绑

    /** 原主人、新主人与被撤销的被授权人各一条（新主人原是被授权人时只收到“成为主人”） */
    @EventListener
    public void onTransferred(DeviceTransferredEvent e) {
        safely("转让", () -> {
            String device = deviceName(e.deviceId());
            store.write(
                    e.fromUser(),
                    MessageType.TRANSFER,
                    e.deviceId(),
                    "「" + device + "」已转让",
                    "你已将该设备转让给" + nickname(e.toUser()) + "，你对它的全部权限已失效。");
            store.write(
                    e.toUser(),
                    MessageType.TRANSFER,
                    e.deviceId(),
                    "你已成为「" + device + "」的主人",
                    nickname(e.fromUser()) + "已将该设备转让给你，原有的被授权人已全部清空，可在成员管理中重新邀请。");
            for (long member : e.revokedMembers()) {
                if (member == e.toUser()) {
                    continue;
                }
                store.write(member, MessageType.TRANSFER, e.deviceId(), "「" + device + "」已转让", "该车位已转让给新主人，你的使用权限已失效。");
            }
        });
    }

    /** 主人解绑或客服强制解绑：原主人与被撤销的被授权人各一条 */
    @EventListener
    public void onUnbound(DeviceUnboundEvent e) {
        safely("解绑", () -> {
            boolean forced = e.type() == OwnershipRelease.Type.FORCE_UNBIND;
            store.write(
                    e.formerOwner(),
                    MessageType.UNBIND,
                    e.deviceId(),
                    "「" + e.deviceName() + "」已解绑",
                    forced ? "客服已解除你与该设备的绑定，如有疑问请联系客服。" : "你已解绑该设备，被授权人的权限已全部失效；重新绑定需要 SN 与激活码。");
            for (long member : e.revokedMembers()) {
                store.write(
                        member, MessageType.UNBIND, e.deviceId(), "「" + e.deviceName() + "」已解绑", "该车位已解除绑定，你的使用权限已失效。");
            }
        });
    }

    // ------------------------------------------------------------ 故障

    /**
     * 电机故障、离线超 24 小时：通知主人（被授权人不收故障消息）。合并进已有告警的不再重复通知；遇阻、红外、连续超时属于单次动作问题，
     * 已在操作记录与控制页体现，不发消息。
     */
    @EventListener
    public void onAlarm(AlarmRaisedEvent e) {
        if (e.merged() || (e.type() != AlarmType.MOTOR_FAULT && e.type() != AlarmType.OFFLINE_24H)) {
            return;
        }
        safely("故障", () -> {
            Optional<Long> owner = jdbc.sql("SELECT owner_user_id FROM device WHERE id = ? AND bind_status = 'BOUND'")
                    .param(e.deviceId())
                    .query(Long.class)
                    .optional();
            if (owner.isEmpty()) {
                return;
            }
            String device = deviceName(e.deviceId());
            if (e.type() == AlarmType.MOTOR_FAULT) {
                store.write(
                        owner.get(),
                        MessageType.FAULT,
                        e.deviceId(),
                        "「" + device + "」电机故障",
                        "设备报告电机故障，暂时无法抬杆 / 落杆，请联系客服处理。");
            } else {
                store.write(
                        owner.get(),
                        MessageType.FAULT,
                        e.deviceId(),
                        "「" + device + "」已离线超过 24 小时",
                        "设备长时间未联网，请检查电池电量和安装位置。");
            }
        });
    }

    // ------------------------------------------------------------

    private void safely(String what, Runnable write) {
        try {
            write.run();
        } catch (RuntimeException ex) {
            log.error("写入{}消息失败", what, ex);
        }
    }

    private String deviceName(long deviceId) {
        return jdbc.sql("SELECT COALESCE(NULLIF(TRIM(name), ''), CONCAT('车位 ', sn)) FROM device WHERE id = ?")
                .param(deviceId)
                .query(String.class)
                .optional()
                .orElse("车位");
    }

    private String nickname(long userId) {
        return jdbc.sql("SELECT nickname FROM app_user WHERE id = ?").param(userId).query(String.class).list().stream()
                .filter(n -> n != null && !n.isBlank())
                .findFirst()
                .orElse(DEFAULT_NICKNAME);
    }

    /** 给主人看的被授权人名称：备注优先，其次昵称 */
    private String memberName(long memberId, long userId) {
        return jdbc
                .sql("SELECT remark FROM device_member WHERE id = ?")
                .param(memberId)
                .query(String.class)
                .list()
                .stream()
                .filter(r -> r != null && !r.isBlank())
                .findFirst()
                .orElseGet(() -> nickname(userId));
    }

    private String period(long memberId) {
        record Range(LocalDateTime from, LocalDateTime to) {}
        Range r = jdbc.sql("SELECT valid_from, valid_to FROM device_member WHERE id = ?")
                .param(memberId)
                .query((rs, n) -> new Range(
                        rs.getObject("valid_from", LocalDateTime.class), rs.getObject("valid_to", LocalDateTime.class)))
                .single();
        return range(DbTime.fromDb(r.from()), DbTime.fromDb(r.to()), Instant.now());
    }

    /** 未开始的写明起始；永久写“永久有效” */
    static String range(Instant from, Instant to, Instant now) {
        String start = from != null && from.isAfter(now) ? "自 " + format(from) + " 起" : "";
        if (to == null) {
            return start.isEmpty() ? "永久有效" : start + "永久有效";
        }
        return (start.isEmpty() ? "" : start + "，") + "有效期至 " + format(to);
    }

    private static String format(Instant at) {
        return TIME.format(at.atZone(DbTime.ZONE));
    }
}
