package com.smartparking.control.ble;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.ApiResponse;
import com.smartparking.common.web.AppPrincipal;
import com.smartparking.control.access.AccessLevel;
import com.smartparking.control.access.DeviceAccess;
import com.smartparking.control.access.DeviceGrant;
import com.smartparking.control.ble.BleTicketService.FailReason;
import com.smartparking.control.ble.BleTicketService.Result;
import com.smartparking.iot.DbTime;
import com.smartparking.iot.command.CommandAction;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 蓝牙票据与结果上报（契约 ble 分组） */
@RestController
@RequestMapping("/api/app")
public class BleController {

    private static final Pattern TICKET_ID = Pattern.compile("[0-9a-f]{32}");

    private final BleTicketService tickets;

    public BleController(BleTicketService tickets) {
        this.tickets = tickets;
    }

    public record TicketRequest(CommandAction action) {}

    /** 契约 BleTicket；{@code payload} 格式待固件确定，暂不返回 */
    public record BleTicket(String ticketId, OffsetDateTime expireAt, String bleMac, String sn, CommandAction action) {}

    public record ReportBody(
            String ticketId,
            CommandAction action,
            Result result,
            FailReason failReason,
            OffsetDateTime at,
            Integer durationMs) {}

    public record ReportsRequest(List<ReportBody> reports) {}

    public record ReportOutcome(int accepted, List<String> rejected) {}

    @PostMapping("/devices/{deviceId}/ble-tickets")
    @DeviceAccess(AccessLevel.CONTROL)
    public ApiResponse<BleTicket> issue(
            @PathVariable long deviceId, DeviceGrant grant, @RequestBody TicketRequest body) {
        if (body == null || body.action() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "缺少 action");
        }
        BleTicketService.Ticket t = tickets.issue(grant, body.action());
        return ApiResponse.ok(new BleTicket(
                t.ticketId(), t.expireAt().atZone(DbTime.ZONE).toOffsetDateTime(), t.bleMac(), t.sn(), t.action()));
    }

    @PostMapping("/ble-reports")
    public ApiResponse<ReportOutcome> report(AppPrincipal user, @RequestBody ReportsRequest body) {
        List<ReportBody> reports = body == null ? null : body.reports();
        if (reports == null || reports.isEmpty() || reports.size() > BleTicketService.MAX_REPORTS) {
            throw new BizException(ErrorCode.BAD_REQUEST, "reports 需要 1–" + BleTicketService.MAX_REPORTS + " 条");
        }
        List<BleTicketService.Report> parsed =
                reports.stream().map(BleController::parse).toList();
        BleTicketService.ReportOutcome outcome = tickets.report(user.userId(), parsed);
        return ApiResponse.ok(new ReportOutcome(outcome.accepted(), outcome.rejected()));
    }

    /** 字段缺失或取值不合法 10001（整批拒绝，手机端是程序错误，不是票据问题） */
    private static BleTicketService.Report parse(ReportBody r) {
        if (r == null || r.ticketId() == null || r.action() == null || r.result() == null || r.at() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "每条上报需要 ticketId、action、result、at");
        }
        if (!TICKET_ID.matcher(r.ticketId()).matches()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "ticketId 格式不正确");
        }
        if ((r.result() == Result.FAILED) != (r.failReason() != null)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "失败时必须给出 failReason，成功时不能给");
        }
        return new BleTicketService.Report(
                r.ticketId(), r.action(), r.result(), r.failReason(), r.at().toInstant(), r.durationMs());
    }
}
