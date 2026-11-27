package com.smartparking.member.members;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.ApiResponse;
import com.smartparking.control.access.AccessLevel;
import com.smartparking.control.access.DeviceAccess;
import com.smartparking.control.access.DeviceGrant;
import com.smartparking.iot.DbTime;
import com.smartparking.member.invite.InviteController.PeriodBody;
import com.smartparking.member.invite.Period;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 成员管理（契约 member 分组：listMembers、updateMember、revokeMember、quitMembership） */
@RestController
@RequestMapping("/api/app/devices/{deviceId}")
public class MemberController {

    private static final Set<String> PATCH_FIELDS = Set.of("period", "remark");

    private final MemberService members;
    private final ObjectMapper json;

    public MemberController(MemberService members, ObjectMapper json) {
        this.members = members;
        this.json = json;
    }

    public record Member(
            long id, String nickname, String remark, OffsetDateTime validFrom, OffsetDateTime validTo, String status) {

        static Member of(MemberService.Member m) {
            return new Member(m.id(), m.nickname(), m.remark(), time(m.validFrom()), time(m.validTo()), m.status());
        }
    }

    public record PendingInvite(
            long id, String remark, PeriodBody period, OffsetDateTime expireAt, OffsetDateTime createdAt) {}

    public record MemberOverview(int limit, int used, List<Member> members, List<PendingInvite> invites) {}

    @GetMapping("/members")
    @DeviceAccess(AccessLevel.OWNER)
    public ApiResponse<MemberOverview> list(DeviceGrant grant) {
        MemberService.Overview o = members.overview(grant);
        return ApiResponse.ok(new MemberOverview(
                o.limit(),
                o.used(),
                o.members().stream().map(Member::of).toList(),
                o.invites().stream()
                        .map(i -> new PendingInvite(
                                i.id(), i.remark(), PeriodBody.of(i.period()), time(i.expireAt()), time(i.createdAt())))
                        .toList()));
    }

    /** 只改出现的字段；空对象、未知字段 10001；{@code remark} 为 null 或空串表示清空 */
    @PatchMapping("/members/{memberId}")
    @DeviceAccess(AccessLevel.OWNER_ENABLED)
    public ApiResponse<Member> update(DeviceGrant grant, @PathVariable long memberId, @RequestBody JsonNode body) {
        if (body == null || !body.isObject() || body.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "至少修改有效期或备注中的一项");
        }
        for (Iterator<String> it = body.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (!PATCH_FIELDS.contains(field)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "不支持修改 " + field);
            }
        }
        Period period = null;
        if (body.has("period")) {
            PeriodBody p;
            try {
                p = body.get("period").isObject() ? json.treeToValue(body.get("period"), PeriodBody.class) : null;
            } catch (Exception e) {
                p = null;
            }
            period = Period.of(
                    p == null ? null : p.type(),
                    p == null ? null : p.durationDays(),
                    p == null ? null : p.validFrom(),
                    p == null ? null : p.validTo(),
                    members.now());
        }
        boolean hasRemark = body.has("remark");
        JsonNode remark = body.get("remark");
        if (hasRemark && !remark.isNull() && !remark.isTextual()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "备注须为字符串");
        }
        return ApiResponse.ok(Member.of(members.update(
                grant, memberId, period, hasRemark, remark == null || remark.isNull() ? null : remark.asText())));
    }

    @DeleteMapping("/members/{memberId}")
    @DeviceAccess(AccessLevel.OWNER)
    public ApiResponse<Void> revoke(DeviceGrant grant, @PathVariable long memberId) {
        members.revoke(grant, memberId);
        return ApiResponse.ok();
    }

    @DeleteMapping("/membership")
    @DeviceAccess(AccessLevel.VIEW)
    public ApiResponse<Void> quit(DeviceGrant grant) {
        members.quit(grant);
        return ApiResponse.ok();
    }

    private static OffsetDateTime time(Instant at) {
        return at == null ? null : at.atZone(DbTime.ZONE).toOffsetDateTime();
    }
}
