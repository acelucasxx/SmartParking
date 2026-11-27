package com.smartparking.notify.web;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.AdminPrincipal;
import com.smartparking.common.web.AdminRole;
import com.smartparking.common.web.AdminRoles;
import com.smartparking.common.web.ApiResponse;
import com.smartparking.common.web.PageResult;
import com.smartparking.iot.DbTime;
import com.smartparking.notify.alarm.AlarmService;
import com.smartparking.notify.alarm.AlarmService.AlarmView;
import com.smartparking.notify.alarm.AlarmType;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 告警中心（契约 alarm 分组）：列表与标记处理。仅超级管理员与运维 */
@RestController
@RequestMapping("/api/admin/alarms")
@AdminRoles({AdminRole.SUPER_ADMIN, AdminRole.OPS})
public class AdminAlarmController {

    private final AlarmService alarms;

    public AdminAlarmController(AlarmService alarms) {
        this.alarms = alarms;
    }

    /** 契约 Alarm，时间按东八区输出 */
    public record Alarm(
            long id,
            long deviceId,
            String sn,
            String type,
            String level,
            OffsetDateTime firstAt,
            OffsetDateTime lastAt,
            int count,
            String status,
            String handlerName,
            OffsetDateTime handledAt,
            String note) {

        static Alarm of(AlarmView v) {
            return new Alarm(
                    v.id(),
                    v.deviceId(),
                    v.sn(),
                    v.type().name(),
                    v.level().name(),
                    time(v.firstAt()),
                    time(v.lastAt()),
                    v.count(),
                    v.status(),
                    v.handlerName(),
                    time(v.handledAt()),
                    v.note());
        }
    }

    public record HandleRequest(String note) {}

    @GetMapping
    public ApiResponse<PageResult<Alarm>> list(
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) String sn,
            @RequestParam(required = false) AlarmType type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        if (status != null && !status.equals("OPEN") && !status.equals("HANDLED")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "status 为 OPEN 或 HANDLED");
        }
        int[] ps = PageResult.normalize(page, size);
        PageResult<AlarmView> result = alarms.list(new AlarmService.Filter(deviceId, sn, type, status), ps[0], ps[1]);
        return ApiResponse.ok(new PageResult<>(
                result.total(),
                result.page(),
                result.size(),
                result.items().stream().map(Alarm::of).toList()));
    }

    @PostMapping("/{alarmId}/handle")
    public ApiResponse<Alarm> handle(
            @PathVariable long alarmId, @RequestBody HandleRequest body, AdminPrincipal admin) {
        return ApiResponse.ok(Alarm.of(alarms.handle(alarmId, admin, body.note())));
    }

    private static OffsetDateTime time(Instant at) {
        return at == null ? null : at.atZone(DbTime.ZONE).toOffsetDateTime();
    }
}
