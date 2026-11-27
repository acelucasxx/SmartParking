package com.smartparking.device.web;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.AdminPrincipal;
import com.smartparking.common.web.AdminRole;
import com.smartparking.common.web.AdminRoles;
import com.smartparking.common.web.ApiResponse;
import com.smartparking.common.web.PageResult;
import com.smartparking.device.imports.DeviceImportService;
import com.smartparking.device.imports.ImportSheets;
import com.smartparking.device.imports.ImportSheets.FailedRow;
import com.smartparking.device.imports.ImportTaskRepository;
import com.smartparking.device.imports.ImportTaskRepository.Task;
import com.smartparking.device.registry.DeviceRegistry;
import com.smartparking.device.registry.DeviceRegistry.Registered;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 设备入库（契约 import 分组）：单台录入、导入模板、批量导入、导入记录、失败行与印刷文件。仅超级管理员与运维 */
@RestController
@RequestMapping("/api/admin")
@AdminRoles({AdminRole.SUPER_ADMIN, AdminRole.OPS})
public class AdminDeviceImportController {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    private final DeviceRegistry registry;
    private final DeviceImportService imports;

    public AdminDeviceImportController(DeviceRegistry registry, DeviceImportService imports) {
        this.registry = registry;
        this.imports = imports;
    }

    // ------------------------------------------------------------ 单台录入

    public record DeviceInput(
            @NotNull String sn,
            @NotNull String imei,
            String bleMac,
            String iccid,
            @NotNull String model,
            @NotNull String batchNo) {}

    /** 契约 AdminDevice（新录入的设备：待激活、启用、尚无运行态） */
    public record AdminDevice(
            long id,
            String sn,
            String imei,
            String bleMac,
            String model,
            String batchNo,
            String bindStatus,
            boolean enabled,
            String onlineState,
            String gateStatus,
            Integer batteryPct,
            String ownerPhoneMasked,
            OffsetDateTime lastSeenAt,
            OffsetDateTime createdAt) {}

    public record Created(AdminDevice device, String activateCode) {}

    @PostMapping("/devices")
    public ApiResponse<Created> create(@Valid @RequestBody DeviceInput in) {
        Registered r = registry.register(in.sn(), in.imei(), in.bleMac(), in.iccid(), in.model(), in.batchNo());
        var d = r.device();
        AdminDevice device = new AdminDevice(
                r.id(),
                d.sn(),
                d.imei(),
                d.bleMac(),
                d.model(),
                d.batchNo(),
                "PENDING",
                true,
                "OFFLINE",
                "UNKNOWN",
                null,
                null,
                null,
                time(r.createdAt()));
        return ApiResponse.ok(new Created(device, r.activateCode()));
    }

    // ------------------------------------------------------------ 批量导入

    /** 契约 ImportTask */
    public record ImportTaskView(
            long id,
            String fileName,
            ImportTaskRepository.Status status,
            int totalRows,
            int successRows,
            int failedRows,
            String errorMessage,
            OffsetDateTime printExportedAt,
            String operatorName,
            OffsetDateTime createdAt,
            OffsetDateTime finishedAt) {

        static ImportTaskView of(Task t) {
            return new ImportTaskView(
                    t.id(),
                    t.fileName(),
                    t.status(),
                    t.totalRows(),
                    t.successRows(),
                    t.failedRows(),
                    t.errorMessage(),
                    time(t.printExportedAt()),
                    t.operatorName(),
                    time(t.createdAt()),
                    time(t.finishedAt()));
        }
    }

    public record FailedRowView(int row, String sn, String imei, String bleMac, String reason) {}

    /** 契约 ImportTaskDetail：ImportTask 的字段 + failedRowsDetail */
    public record ImportTaskDetail(
            long id,
            String fileName,
            ImportTaskRepository.Status status,
            int totalRows,
            int successRows,
            int failedRows,
            String errorMessage,
            OffsetDateTime printExportedAt,
            String operatorName,
            OffsetDateTime createdAt,
            OffsetDateTime finishedAt,
            List<FailedRowView> failedRowsDetail) {}

    @GetMapping("/device-imports/template")
    public ResponseEntity<byte[]> template() {
        return download(ImportSheets.template(), XLSX, "设备导入模板.xlsx");
    }

    @GetMapping("/device-imports")
    public ApiResponse<PageResult<ImportTaskView>> list(
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        int[] ps = PageResult.normalize(page, size);
        return ApiResponse.ok(imports.page(ps[0], ps[1], ImportTaskView::of));
    }

    @PostMapping(value = "/device-imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ImportTaskView> upload(AdminPrincipal admin, @RequestPart("file") MultipartFile file)
            throws IOException {
        if (file.isEmpty()) {
            throw new BizException(ErrorCode.IMPORT_FILE_INVALID, "文件为空");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BizException(ErrorCode.IMPORT_FILE_INVALID, "文件超过大小限制（5 MB）");
        }
        String name = file.getOriginalFilename() == null ? "import" : file.getOriginalFilename();
        if (name.length() > 255) {
            name = name.substring(name.length() - 255);
        }
        return ApiResponse.ok(ImportTaskView.of(imports.submit(admin.adminId(), name, file.getBytes())));
    }

    @GetMapping("/device-imports/{taskId}")
    public ApiResponse<ImportTaskDetail> get(@PathVariable long taskId) {
        Task t = imports.get(taskId);
        List<FailedRowView> failed = imports.failedRows(taskId).stream()
                .map(f -> new FailedRowView(f.row(), blank(f.sn()), blank(f.imei()), blank(f.bleMac()), f.reason()))
                .toList();
        ImportTaskView v = ImportTaskView.of(t);
        return ApiResponse.ok(new ImportTaskDetail(
                v.id(),
                v.fileName(),
                v.status(),
                v.totalRows(),
                v.successRows(),
                v.failedRows(),
                v.errorMessage(),
                v.printExportedAt(),
                v.operatorName(),
                v.createdAt(),
                v.finishedAt(),
                failed));
    }

    @GetMapping("/device-imports/{taskId}/failed-rows")
    public ResponseEntity<byte[]> failedRows(@PathVariable long taskId) {
        List<FailedRow> rows = imports.failedRows(taskId);
        return download(ImportSheets.failedRows(rows), XLSX, "导入失败行-" + taskId + ".xlsx");
    }

    @GetMapping("/device-imports/{taskId}/print-file")
    public ResponseEntity<byte[]> printFile(@PathVariable long taskId) {
        return download(
                imports.exportPrintFile(taskId),
                MediaType.parseMediaType("application/zip"),
                "印刷文件-" + taskId + ".zip");
    }

    private static ResponseEntity<byte[]> download(byte[] body, MediaType type, String fileName) {
        return ResponseEntity.ok()
                .contentType(type)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(fileName, java.nio.charset.StandardCharsets.UTF_8)
                                .build()
                                .toString())
                // 印刷文件含激活码明文，禁止任何缓存
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    private static String blank(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static OffsetDateTime time(LocalDateTime local) {
        return local == null ? null : local.atZone(ZONE).toOffsetDateTime();
    }
}
