package com.smartparking.ownership.bind;

import com.smartparking.common.web.ApiResponse;
import com.smartparking.common.web.AppPrincipal;
import com.smartparking.control.devices.DeviceViews;
import com.smartparking.ownership.bind.BindService.Preview;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 扫码绑定（契约 bind 分组，PRD 5.2）。路径按 SN 而不是 {@code {deviceId}}：绑定前用户与设备尚无关系，不经统一权限判定，
 * 规则见 {@link BindService}。
 */
@RestController
@RequestMapping("/api/app/bind")
public class BindController {

    private final BindService binds;
    private final DeviceViews cards;

    public BindController(BindService binds, DeviceViews cards) {
        this.binds = binds;
        this.cards = cards;
    }

    public record BindRequest(@NotNull String sn, @NotNull String activateCode) {}

    @GetMapping("/{sn}")
    public ApiResponse<Preview> preview(AppPrincipal user, @PathVariable String sn) {
        return ApiResponse.ok(binds.preview(user.userId(), sn));
    }

    @PostMapping
    public ApiResponse<DeviceViews.DeviceCard> bind(AppPrincipal user, @Valid @RequestBody BindRequest request) {
        long deviceId = binds.bind(user.userId(), request.sn(), request.activateCode());
        return ApiResponse.ok(cards.card(user.userId(), deviceId));
    }
}
