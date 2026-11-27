package com.smartparking.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartparking.TestcontainersConfiguration;
import com.smartparking.admin.TestAdminIdentity;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.AdminRole;
import com.smartparking.device.imports.DeviceImportService;
import com.smartparking.device.registry.ActivateCodes;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 设备入库（BIZ-03）：后台守卫、单台录入、批量导入、失败行、一次性印刷文件。MySQL 为真实容器，后台身份由测试过滤器模拟。 */
@SpringBootTest(
        properties = {
            "smartparking.mqtt.enabled=false",
            "smartparking.device-event.partitions.enabled=false",
            "smartparking.wechat.mode=mock"
        })
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, TestAdminIdentity.class})
class AdminDeviceImportIntegrationTest {

    private static final String CSV_HEADER = "SN,IMEI,蓝牙 MAC,型号,批次\n";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ActivateCodes codes;

    @Autowired
    private DeviceImportService imports;

    private long adminId;
    private String ops;
    private String prefix;

    @BeforeEach
    void setUp() {
        String username = "ops-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql(
                        """
                        INSERT INTO admin_user (username, password_hash, display_name, role_id)
                        VALUES (?, 'x', '运维小王', (SELECT id FROM role WHERE code = 'OPS'))""")
                .param(username)
                .update();
        adminId = jdbc.sql("SELECT id FROM admin_user WHERE username = ?")
                .param(username)
                .query(Long.class)
                .single();
        ops = TestAdminIdentity.header(adminId, AdminRole.OPS, "运维小王");
        // 每个用例用独立的 SN 前缀，互不干扰
        prefix = "T" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
    }

    // ------------------------------------------------------------ 工具

    private String sn(int n) {
        return prefix + String.format("%04d", n);
    }

    /** 随机生成一个通过 Luhn 校验的 15 位 IMEI */
    private static String imei() {
        StringBuilder s = new StringBuilder("86");
        for (int i = 0; i < 12; i++) {
            s.append(ThreadLocalRandom.current().nextInt(10));
        }
        for (int check = 0; check < 10; check++) {
            String candidate = s.toString() + check;
            if (com.smartparking.device.registry.DeviceRules.luhn(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException();
    }

    private static String mac() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                s.append(':');
            }
            s.append(String.format("%02X", ThreadLocalRandom.current().nextInt(256)));
        }
        return s.toString();
    }

    private ResultActions as(String identity, MockHttpServletRequestBuilder request) throws Exception {
        if (identity != null) {
            request.header(TestAdminIdentity.HEADER, identity);
        }
        return mvc.perform(request);
    }

    private JsonNode data(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data");
    }

    private ResultActions create(String identity, String sn, String imei, String mac) throws Exception {
        String body = json.writeValueAsString(Map.of(
                "sn", sn, "imei", imei, "bleMac", mac == null ? "" : mac, "model", "SPG-100", "batchNo", "B20261201"));
        return as(
                identity,
                post("/api/admin/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private ResultActions upload(String fileName, byte[] content) throws Exception {
        return as(
                ops,
                multipart("/api/admin/device-imports").file(new MockMultipartFile("file", fileName, null, content)));
    }

    private JsonNode uploadAndWait(String csv) throws Exception {
        long id = data(upload("batch.csv", csv.getBytes(StandardCharsets.UTF_8)).andExpect(status().isOk()))
                .get("id")
                .asLong();
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    String s = data(as(ops, get("/api/admin/device-imports/" + id)))
                            .get("status")
                            .asText();
                    return s.equals("DONE") || s.equals("FAILED");
                });
        return data(as(ops, get("/api/admin/device-imports/" + id)));
    }

    private String hashOf(String sn) {
        return jdbc.sql("SELECT activate_code_hash FROM device WHERE sn = ?")
                .param(sn)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private static void expectError(ResultActions result, ErrorCode code) throws Exception {
        result.andExpect(status().is(code.status().value()))
                .andExpect(jsonPath("$.code").value(code.code()));
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                entries.put(e.getName(), in.readAllBytes());
            }
        }
        return entries;
    }

    private static List<List<String>> sheet(byte[] xlsx) throws Exception {
        try (ReadableWorkbook wb = new ReadableWorkbook(new ByteArrayInputStream(xlsx))) {
            List<Row> rows = wb.getFirstSheet().read();
            return rows.stream()
                    .map(r -> r.stream().map(c -> c == null ? "" : c.getText()).toList())
                    .toList();
        }
    }

    // ------------------------------------------------------------ 后台守卫

    @Test
    void adminApisRejectWithoutAdminIdentity() throws Exception {
        expectError(as(null, get("/api/admin/device-imports")), ErrorCode.UNAUTHORIZED);
        expectError(as(null, get("/api/admin/device-imports/template")), ErrorCode.UNAUTHORIZED);
        expectError(create(null, sn(1), imei(), null), ErrorCode.UNAUTHORIZED);
        // 小程序的会话不能用于后台
        expectError(
                as(null, get("/api/admin/device-imports").header("Authorization", "Bearer " + "A".repeat(43))),
                ErrorCode.UNAUTHORIZED);
        assertThat(hashOf(sn(1))).isNull();
    }

    @Test
    void supportRoleIsForbidden() throws Exception {
        String support = TestAdminIdentity.header(adminId, AdminRole.SUPPORT, "客服");
        expectError(create(support, sn(1), imei(), null), ErrorCode.FORBIDDEN);
        expectError(as(support, get("/api/admin/device-imports")), ErrorCode.FORBIDDEN);
        assertThat(hashOf(sn(1))).isNull();

        String superAdmin = TestAdminIdentity.header(adminId, AdminRole.SUPER_ADMIN, "超管");
        create(superAdmin, sn(2), imei(), null).andExpect(status().isOk());
    }

    // ------------------------------------------------------------ 单台录入

    @Test
    void singleDeviceIsPendingWithOneTimeCode() throws Exception {
        String imei = imei();
        JsonNode body = data(create(ops, sn(1).toLowerCase(), imei, "aa-bb-cc-" + prefix.substring(2, 4) + "-00-01")
                .andExpect(status().isOk()));

        String code = body.get("activateCode").asText();
        assertThat(code).matches("[23456789ABCDEFGHJKMNPQRSTUVWXYZ]{8}");
        JsonNode device = body.get("device");
        assertThat(device.get("sn").asText()).isEqualTo(sn(1));
        assertThat(device.get("bleMac").asText()).matches("AA:BB:CC:[0-9A-F]{2}:00:01");
        assertThat(device.get("bindStatus").asText()).isEqualTo("PENDING");
        assertThat(device.get("enabled").asBoolean()).isTrue();
        assertThat(device.get("onlineState").asText()).isEqualTo("OFFLINE");
        assertThat(device.get("gateStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(device.get("createdAt").asText()).endsWith("+08:00");

        String hash = hashOf(sn(1));
        assertThat(hash).doesNotContain(code);
        assertThat(codes.matches(code, hash)).isTrue();
        assertThat(jdbc.sql("SELECT bind_status FROM device WHERE sn = ?")
                        .param(sn(1))
                        .query(String.class)
                        .single())
                .isEqualTo("PENDING");
    }

    @Test
    void singleDeviceValidationAndDuplicates() throws Exception {
        String imei = imei();
        String mac = mac();
        create(ops, sn(1), imei, mac).andExpect(status().isOk());

        expectError(create(ops, sn(1), imei(), null), ErrorCode.SN_DUPLICATE);
        expectError(create(ops, sn(2), imei, null), ErrorCode.IMEI_DUPLICATE);
        expectError(create(ops, sn(3), imei(), mac.toLowerCase()), ErrorCode.MAC_DUPLICATE);
        expectError(create(ops, sn(4), "490154203237517", null), ErrorCode.IMEI_INVALID);
        expectError(create(ops, "BAD-SN", imei(), null), ErrorCode.BAD_REQUEST);
        expectError(
                as(
                        ops,
                        post("/api/admin/devices")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sn\":\"X\"}")),
                ErrorCode.BAD_REQUEST);
    }

    // ------------------------------------------------------------ 批量导入

    @Test
    void importValidatesRowsAndRecordsFailures() throws Exception {
        create(ops, sn(90), imei(), null).andExpect(status().isOk()); // 库内已存在
        String dupImei = imei();
        String csv = CSV_HEADER
                + sn(1) + "," + imei() + "," + mac() + ",SPG-100,B1\n" // 2 成功
                + sn(2) + ",490154203237517,,SPG-100,B1\n" // 3 IMEI 校验位
                + sn(3) + "," + dupImei + ",,SPG-100,B1\n" // 4 成功
                + sn(4) + "," + dupImei + ",,SPG-100,B1\n" // 5 与第 4 行重复
                + sn(1) + "," + imei() + ",,SPG-100,B1\n" // 6 SN 与第 2 行重复
                + sn(90) + "," + imei() + ",,SPG-100,B1\n" // 7 库内已存在
                + sn(5) + "," + imei() + ",,,B1\n"; // 8 型号为空
        JsonNode task = uploadAndWait(csv);

        assertThat(task.get("status").asText()).isEqualTo("DONE");
        assertThat(task.get("totalRows").asInt()).isEqualTo(7);
        assertThat(task.get("successRows").asInt()).isEqualTo(2);
        assertThat(task.get("failedRows").asInt()).isEqualTo(5);
        assertThat(task.get("operatorName").asText()).isEqualTo("运维小王");
        assertThat(task.get("fileName").asText()).isEqualTo("batch.csv");
        assertThat(task.get("printExportedAt").isNull()).isTrue();

        Map<Integer, String> reasons = new HashMap<>();
        task.get("failedRowsDetail")
                .forEach(r -> reasons.put(r.get("row").asInt(), r.get("reason").asText()));
        assertThat(reasons)
                .containsEntry(3, "IMEI 校验位不正确")
                .containsEntry(5, "IMEI 与第 4 行重复")
                .containsEntry(6, "SN 与第 2 行重复")
                .containsEntry(7, "SN 已存在")
                .containsEntry(8, "型号不能为空")
                .hasSize(5);
        assertThat(hashOf(sn(1))).isNotNull();
        assertThat(hashOf(sn(3))).isNotNull();
        assertThat(hashOf(sn(4))).isNull();

        // 失败行文件：原始列 + 失败原因，可直接重新导入
        byte[] failed = as(
                        ops, get("/api/admin/device-imports/" + task.get("id").asLong() + "/failed-rows"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", AdminXlsx.TYPE))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        List<List<String>> rows = sheet(failed);
        assertThat(rows.get(0)).containsExactly("SN", "IMEI", "蓝牙 MAC", "型号", "批次", "失败原因");
        assertThat(rows).hasSize(6);
        assertThat(rows.get(1).get(1)).isEqualTo("490154203237517");
        upload("failed.xlsx", failed)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalRows").value(5));
    }

    @Test
    void printFileIsExportedExactlyOnce() throws Exception {
        String csv = CSV_HEADER + sn(1) + "," + imei() + ",,SPG-100,B1\n" + sn(2) + "," + imei() + ",,SPG-100,B1\n";
        long id = uploadAndWait(csv).get("id").asLong();

        byte[] zip = as(ops, get("/api/admin/device-imports/" + id + "/print-file"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        Map<String, byte[]> entries = unzip(zip);
        assertThat(entries).containsKeys("manifest.xlsx", "qr/" + sn(1) + ".png", "qr/" + sn(2) + ".png");
        List<List<String>> manifest = sheet(entries.get("manifest.xlsx"));
        assertThat(manifest).hasSize(3);
        for (List<String> row : manifest.subList(1, 3)) {
            // 印刷的激活码与库里的哈希对得上
            assertThat(codes.matches(row.get(2), hashOf(row.get(1)))).isTrue();
            assertThat(row.get(5)).isEqualTo("https://smartparking.invalid/bind?sn=" + row.get(1));
        }

        expectError(as(ops, get("/api/admin/device-imports/" + id + "/print-file")), ErrorCode.PRINT_ALREADY_EXPORTED);
        assertThat(jdbc.sql("SELECT print_payload IS NULL FROM import_task WHERE id = ?")
                        .param(id)
                        .query(Boolean.class)
                        .single())
                .as("导出后不再保留激活码")
                .isTrue();
        as(ops, get("/api/admin/device-imports/" + id))
                .andExpect(jsonPath("$.data.printExportedAt").isNotEmpty());
    }

    @Test
    void printFileRequiresFinishedTaskWithDevices() throws Exception {
        long allFailed =
                uploadAndWait(CSV_HEADER + sn(1) + ",123,,M,B\n").get("id").asLong();
        expectError(as(ops, get("/api/admin/device-imports/" + allFailed + "/print-file")), ErrorCode.CONFLICT);

        jdbc.sql(
                        "INSERT INTO import_task (file_name, status, total_rows, operator_id) VALUES ('x.csv', 'RUNNING', 1, ?)")
                .param(adminId)
                .update();
        long running =
                jdbc.sql("SELECT MAX(id) FROM import_task").query(Long.class).single();
        expectError(as(ops, get("/api/admin/device-imports/" + running + "/print-file")), ErrorCode.CONFLICT);
        expectError(as(ops, get("/api/admin/device-imports/" + Long.MAX_VALUE + "/print-file")), ErrorCode.NOT_FOUND);
        expectError(as(ops, get("/api/admin/device-imports/" + Long.MAX_VALUE)), ErrorCode.NOT_FOUND);
    }

    @Test
    void badFilesAreRejectedSynchronously() throws Exception {
        long before =
                jdbc.sql("SELECT COUNT(*) FROM import_task").query(Long.class).single();
        expectError(upload("a.txt", "x".getBytes(StandardCharsets.UTF_8)), ErrorCode.IMPORT_FILE_INVALID);
        expectError(upload("a.csv", "a,b\n1,2".getBytes(StandardCharsets.UTF_8)), ErrorCode.IMPORT_FILE_INVALID);
        expectError(upload("a.csv", new byte[0]), ErrorCode.IMPORT_FILE_INVALID);
        expectError(upload("a.csv", new byte[6 * 1024 * 1024]), ErrorCode.IMPORT_FILE_INVALID);
        expectError(as(ops, multipart("/api/admin/device-imports")), ErrorCode.BAD_REQUEST);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM import_task")
                        .query(Long.class)
                        .single())
                .isEqualTo(before);
    }

    @Test
    void templateAndPaging() throws Exception {
        byte[] template = as(ops, get("/api/admin/device-imports/template"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertThat(sheet(template).get(0)).containsExactly("SN", "IMEI", "蓝牙 MAC", "型号", "批次");

        uploadAndWait(CSV_HEADER + sn(1) + "," + imei() + ",,M,B\n");
        long latest = uploadAndWait(CSV_HEADER + sn(2) + "," + imei() + ",,M,B\n")
                .get("id")
                .asLong();
        JsonNode page =
                data(as(ops, get("/api/admin/device-imports?page=1&size=1")).andExpect(status().isOk()));
        assertThat(page.get("page").asInt()).isEqualTo(1);
        assertThat(page.get("size").asInt()).isEqualTo(1);
        assertThat(page.get("total").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(page.get("items")).hasSize(1);
        assertThat(page.get("items").get(0).get("id").asLong()).isEqualTo(latest);

        expectError(as(ops, get("/api/admin/device-imports?page=0")), ErrorCode.BAD_REQUEST);
        expectError(as(ops, get("/api/admin/device-imports?size=101")), ErrorCode.BAD_REQUEST);
        expectError(as(ops, get("/api/admin/device-imports?page=abc")), ErrorCode.BAD_REQUEST);
    }

    @Test
    void staleTasksAreMarkedFailed() {
        jdbc.sql(
                        """
                        INSERT INTO import_task (file_name, status, total_rows, operator_id, created_at)
                        VALUES ('stale.csv', 'RUNNING', 10, ?, NOW(3) - INTERVAL 11 MINUTE)""")
                .param(adminId)
                .update();
        long id = jdbc.sql("SELECT MAX(id) FROM import_task").query(Long.class).single();
        assertThat(imports.failStale()).isPositive();
        assertThat(jdbc.sql("SELECT status FROM import_task WHERE id = ?")
                        .param(id)
                        .query(String.class)
                        .single())
                .isEqualTo("FAILED");
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate template;

    @Autowired
    private com.smartparking.device.registry.DeviceRepository devices;

    @Autowired
    private DeviceProperties deviceProperties;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate tx;

    @Test
    void importRollsBackWhenTaskWasSweptMeanwhile() {
        // 处理期间任务被超时清理记为失败：finish 推进不了，整批回滚，不留下无处导出激活码的设备
        var repo = new com.smartparking.device.imports.ImportTaskRepository(jdbc, template) {
            @Override
            public boolean finish(long id, int success, int failed, String detail, byte[] payload) {
                return false;
            }
        };
        var service = new DeviceImportService(repo, devices, codes, deviceProperties, tx, Runnable::run, json);
        String csv = CSV_HEADER + sn(1) + "," + imei() + ",,SPG-100,B1\n";
        long id = service.submit(adminId, "x.csv", csv.getBytes(StandardCharsets.UTF_8))
                .id();

        assertThat(hashOf(sn(1))).isNull();
        assertThat(jdbc.sql("SELECT status FROM import_task WHERE id = ?")
                        .param(id)
                        .query(String.class)
                        .single())
                .isEqualTo("FAILED");
    }

    /** xlsx 的 Content-Type */
    private static final class AdminXlsx {
        static final String TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }
}
