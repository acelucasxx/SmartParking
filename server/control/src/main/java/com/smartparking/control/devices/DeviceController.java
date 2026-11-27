package com.smartparking.control.devices;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.ApiResponse;
import com.smartparking.common.web.AppPrincipal;
import com.smartparking.control.access.AccessLevel;
import com.smartparking.control.access.DeviceAccess;
import com.smartparking.control.access.DeviceGrant;
import com.smartparking.control.devices.DeviceViews.DeviceCard;
import com.smartparking.control.devices.DeviceViews.DeviceDetail;
import com.smartparking.control.devices.DeviceViews.DeviceStatus;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 我的车位、设备详情、实时状态、修改名称与车位号（契约 device 分组，PRD 6.1 页面 2、3、10） */
@RestController
@RequestMapping("/api/app/devices")
public class DeviceController {

    private final DeviceViews views;

    public DeviceController(DeviceViews views) {
        this.views = views;
    }

    @GetMapping
    public ApiResponse<List<DeviceCard>> list(AppPrincipal user) {
        return ApiResponse.ok(views.list(user.userId()));
    }

    @GetMapping("/{deviceId}")
    @DeviceAccess(AccessLevel.VIEW)
    public ApiResponse<DeviceDetail> detail(@PathVariable long deviceId, DeviceGrant grant) {
        return ApiResponse.ok(views.detail(grant));
    }

    @GetMapping("/{deviceId}/status")
    @DeviceAccess(AccessLevel.VIEW)
    public ApiResponse<DeviceStatus> status(@PathVariable long deviceId, DeviceGrant grant) {
        return ApiResponse.ok(views.status(grant));
    }

    /** 请求体按字段是否出现区分“不改”与“清空”，故直接读 JSON */
    @PatchMapping("/{deviceId}")
    @DeviceAccess(AccessLevel.OWNER)
    public ApiResponse<DeviceCard> update(@PathVariable long deviceId, DeviceGrant grant, @RequestBody JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "请求体应为 JSON 对象");
        }
        return ApiResponse.ok(views.update(
                grant, body.has("name"), text(body.get("name")), body.has("spaceNo"), text(body.get("spaceNo"))));
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "名称与车位号应为字符串");
        }
        return node.asText();
    }
}
