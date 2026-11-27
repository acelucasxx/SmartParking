package com.smartparking.member.invite;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.smartparking.common.web.ApiResponse;
import com.smartparking.common.web.AppPrincipal;
import com.smartparking.control.access.AccessLevel;
import com.smartparking.control.access.DeviceAccess;
import com.smartparking.control.access.DeviceGrant;
import com.smartparking.control.devices.DeviceViews.DeviceCard;
import com.smartparking.iot.DbTime;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 邀请家人（契约 member 分组：createInvite、cancelInvite、previewInvite、acceptInvite） */
@RestController
@RequestMapping("/api/app")
public class InviteController {

    private final InviteService invites;

    public InviteController(InviteService invites) {
        this.invites = invites;
    }

    /** 契约 Period；按类型只输出相关字段 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PeriodBody(String type, Integer durationDays, OffsetDateTime validFrom, OffsetDateTime validTo) {

        public static PeriodBody of(Period p) {
            return new PeriodBody(p.type().name(), p.durationDays(), time(p.validFrom()), time(p.validTo()));
        }
    }

    public record CreateInvite(PeriodBody period, String remark) {}

    public record ShareCard(long id, String token, OffsetDateTime expireAt, String path, String title) {}

    public record InvitePreview(
            String deviceName,
            String ownerNickname,
            PeriodBody period,
            String remark,
            OffsetDateTime expireAt,
            InviteService.State state) {}

    @PostMapping("/devices/{deviceId}/invites")
    @DeviceAccess(AccessLevel.OWNER_ENABLED)
    public ApiResponse<ShareCard> create(DeviceGrant grant, @RequestBody CreateInvite body) {
        PeriodBody p = body.period();
        Period period = Period.of(
                p == null ? null : p.type(),
                p == null ? null : p.durationDays(),
                p == null ? null : p.validFrom(),
                p == null ? null : p.validTo(),
                invites.now());
        InviteService.ShareCard card = invites.create(grant, period, body.remark());
        return ApiResponse.ok(new ShareCard(card.id(), card.token(), time(card.expireAt()), card.path(), card.title()));
    }

    @DeleteMapping("/devices/{deviceId}/invites/{inviteId}")
    @DeviceAccess(AccessLevel.OWNER)
    public ApiResponse<Void> cancel(DeviceGrant grant, @PathVariable long inviteId) {
        invites.cancel(grant, inviteId);
        return ApiResponse.ok();
    }

    @GetMapping("/invites/{token}")
    public ApiResponse<InvitePreview> preview(AppPrincipal user, @PathVariable String token) {
        InviteService.Preview p = invites.preview(user.userId(), token);
        return ApiResponse.ok(new InvitePreview(
                p.deviceName(),
                p.ownerNickname(),
                PeriodBody.of(p.period()),
                p.remark(),
                time(p.expireAt()),
                p.state()));
    }

    @PostMapping("/invites/{token}/accept")
    public ApiResponse<DeviceCard> accept(AppPrincipal user, @PathVariable String token) {
        return ApiResponse.ok(invites.accept(user.userId(), token));
    }

    private static OffsetDateTime time(Instant at) {
        return at == null ? null : at.atZone(DbTime.ZONE).toOffsetDateTime();
    }
}
