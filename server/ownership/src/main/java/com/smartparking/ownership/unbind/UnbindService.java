package com.smartparking.ownership.unbind;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.control.access.DeviceGrant;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 主人解绑（PRD 5.7①④）：设备回到待激活，全部被授权人失效，重新绑定仍需激活码（激活码不变）。设备禁用时也可以解绑。
 */
public class UnbindService {

    private static final Logger log = LoggerFactory.getLogger(UnbindService.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final OwnershipRelease release;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public UnbindService(
            JdbcClient jdbc,
            TransactionTemplate tx,
            OwnershipRelease release,
            ApplicationEventPublisher events,
            Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.release = release;
        this.events = events;
        this.clock = clock;
    }

    /** 调用方已按 OWNER 判定；事务内锁设备行后再确认一次本人仍是主人（并发转让 / 解绑时只有一方成功） */
    public void unbind(DeviceGrant owner) {
        Instant now = clock.instant();
        OwnershipRelease.Released released = Objects.requireNonNull(tx.execute(status -> {
            boolean stillOwner =
                    jdbc.sql("SELECT COUNT(*) FROM device WHERE id = ? AND bind_status = 'BOUND' AND owner_user_id = ?"
                                            + " FOR UPDATE")
                                    .params(owner.deviceId(), owner.userId())
                                    .query(Long.class)
                                    .single()
                            > 0;
            if (!stillOwner) {
                throw new BizException(ErrorCode.NOT_FOUND, "设备不存在");
            }
            return release.release(
                    owner.deviceId(), owner.userId(), OwnershipRelease.Type.UNBIND, "USER", owner.userId(), null, now);
        }));
        log.info(
                "用户 {} 解绑设备 {}，撤销授权 {} 个",
                owner.userId(),
                owner.deviceId(),
                released.revokedMembers().size());
        events.publishEvent(new DeviceUnboundEvent(
                owner.deviceId(), owner.userId(), released.revokedMembers(), OwnershipRelease.Type.UNBIND, now));
    }
}
