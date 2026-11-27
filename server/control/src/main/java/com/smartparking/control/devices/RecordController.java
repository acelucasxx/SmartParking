package com.smartparking.control.devices;

import com.smartparking.common.web.ApiResponse;
import com.smartparking.common.web.PageResult;
import com.smartparking.control.access.AccessLevel;
import com.smartparking.control.access.DeviceAccess;
import com.smartparking.control.access.DeviceGrant;
import com.smartparking.control.devices.DeviceViews.OperationRecord;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 操作记录（契约 listRecords）：设备禁用时也可以查看 */
@RestController
@RequestMapping("/api/app/devices/{deviceId}")
public class RecordController {

    private final OperationRecords records;

    public RecordController(OperationRecords records) {
        this.records = records;
    }

    /** {@code date} 为东八区某一天（yyyy-MM-dd），格式不对 10001；缺省为全部 */
    @GetMapping("/records")
    @DeviceAccess(AccessLevel.VIEW)
    public ApiResponse<PageResult<OperationRecord>> list(
            DeviceGrant grant,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        int[] ps = PageResult.normalize(page, size);
        return ApiResponse.ok(records.page(grant, date, ps[0], ps[1]));
    }
}
