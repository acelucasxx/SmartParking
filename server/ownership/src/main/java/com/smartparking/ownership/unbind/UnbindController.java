package com.smartparking.ownership.unbind;

import com.smartparking.common.web.ApiResponse;
import com.smartparking.control.access.AccessLevel;
import com.smartparking.control.access.DeviceAccess;
import com.smartparking.control.access.DeviceGrant;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 主人解绑（契约 unbindDevice），前端二次确认 */
@RestController
@RequestMapping("/api/app/devices/{deviceId}")
public class UnbindController {

    private final UnbindService unbind;

    public UnbindController(UnbindService unbind) {
        this.unbind = unbind;
    }

    @PostMapping("/unbind")
    @DeviceAccess(AccessLevel.OWNER)
    public ApiResponse<Void> unbind(DeviceGrant grant) {
        unbind.unbind(grant);
        return ApiResponse.ok();
    }
}
