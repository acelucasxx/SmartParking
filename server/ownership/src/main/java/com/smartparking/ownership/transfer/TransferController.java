package com.smartparking.ownership.transfer;

import com.smartparking.common.web.ApiResponse;
import com.smartparking.common.web.AppPrincipal;
import com.smartparking.control.access.AccessLevel;
import com.smartparking.control.access.DeviceAccess;
import com.smartparking.control.access.DeviceGrant;
import com.smartparking.control.devices.DeviceViews.DeviceCard;
import com.smartparking.iot.DbTime;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 转让设备（契约 transfer 分组） */
@RestController
@RequestMapping("/api/app")
public class TransferController {

    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    public record ShareCard(long id, String token, OffsetDateTime expireAt, String path, String title) {}

    public record PendingTransfer(long id, OffsetDateTime expireAt, OffsetDateTime createdAt) {}

    public record TransferPreview(
            String deviceName, String sn, String fromNickname, OffsetDateTime expireAt, TransferService.State state) {}

    /**
     * 契约：没有进行中的转让时 {@code data} 为 null。统一信封会省略 null 字段，这里显式输出 {@code "data": null}，前端据此区分
     * “没有转让”与字段缺失。
     */
    @GetMapping("/devices/{deviceId}/transfer")
    @DeviceAccess(AccessLevel.OWNER)
    public Map<String, Object> pending(DeviceGrant grant) {
        ApiResponse<Void> ok = ApiResponse.ok();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ok.code());
        body.put("message", ok.message());
        body.put(
                "data",
                transfers
                        .pending(grant)
                        .map(p -> new PendingTransfer(p.id(), time(p.expireAt()), time(p.createdAt())))
                        .orElse(null));
        return body;
    }

    @PostMapping("/devices/{deviceId}/transfer")
    @DeviceAccess(AccessLevel.OWNER_ENABLED)
    public ApiResponse<ShareCard> create(DeviceGrant grant) {
        TransferService.ShareCard c = transfers.create(grant);
        return ApiResponse.ok(new ShareCard(c.id(), c.token(), time(c.expireAt()), c.path(), c.title()));
    }

    @DeleteMapping("/devices/{deviceId}/transfer")
    @DeviceAccess(AccessLevel.OWNER)
    public ApiResponse<Void> cancel(DeviceGrant grant) {
        transfers.cancel(grant);
        return ApiResponse.ok();
    }

    @GetMapping("/transfers/{token}")
    public ApiResponse<TransferPreview> preview(AppPrincipal user, @PathVariable String token) {
        TransferService.Preview p = transfers.preview(user.userId(), token);
        return ApiResponse.ok(
                new TransferPreview(p.deviceName(), p.sn(), p.fromNickname(), time(p.expireAt()), p.state()));
    }

    @PostMapping("/transfers/{token}/accept")
    public ApiResponse<DeviceCard> accept(AppPrincipal user, @PathVariable String token) {
        return ApiResponse.ok(transfers.accept(user.userId(), token));
    }

    private static OffsetDateTime time(Instant at) {
        return at == null ? null : at.atZone(DbTime.ZONE).toOffsetDateTime();
    }
}
