package com.smartparking.device.imports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.PageResult;
import com.smartparking.device.DeviceProperties;
import com.smartparking.device.imports.ImportFileReader.RawRow;
import com.smartparking.device.imports.ImportSheets.FailedRow;
import com.smartparking.device.imports.ImportSheets.PrintItem;
import com.smartparking.device.imports.ImportTaskRepository.PrintState;
import com.smartparking.device.imports.ImportTaskRepository.Status;
import com.smartparking.device.imports.ImportTaskRepository.Task;
import com.smartparking.device.registry.ActivateCodes;
import com.smartparking.device.registry.DeviceRepository;
import com.smartparking.device.registry.DeviceRepository.NewDevice;
import com.smartparking.device.registry.DeviceRules;
import com.smartparking.device.registry.DeviceRules.DeviceDraft;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 批量导入（PRD 5.1②–⑤）。
 *
 * <ol>
 *   <li>同步：读文件、校验表头与行数，格式问题直接 40014；建任务（PENDING），交给后台线程。
 *   <li>异步：在<b>一个事务</b>内逐行校验（格式、文件内重复、库内重复）→ 生成激活码 → 批量插入 → 写入结果、失败行与加密的印刷数据。
 *       中途失败整批回滚，不会留下半批没有激活码可印的设备，修正后重新导入即可。
 *   <li>印刷文件只能导出一次：导出时加行锁，生成文件后清空暂存的激活码。
 * </ol>
 *
 * <p>处理任务的实例退出时，任务停留在 PENDING / RUNNING：{@link #failStale()} 把超过 {@link #STALE_MINUTES} 分钟的记为失败。事务已回滚，
 * 重新导入即可。
 */
public class DeviceImportService {

    private static final Logger log = LoggerFactory.getLogger(DeviceImportService.class);

    static final int STALE_MINUTES = 10;

    private final ImportTaskRepository tasks;
    private final DeviceRepository devices;
    private final ActivateCodes codes;
    private final DeviceProperties properties;
    private final TransactionTemplate tx;
    private final Executor executor;
    private final ObjectMapper json;

    public DeviceImportService(
            ImportTaskRepository tasks,
            DeviceRepository devices,
            ActivateCodes codes,
            DeviceProperties properties,
            TransactionTemplate tx,
            Executor executor,
            ObjectMapper json) {
        this.tasks = tasks;
        this.devices = devices;
        this.codes = codes;
        this.properties = properties;
        this.tx = tx;
        this.executor = executor;
        this.json = json;
    }

    /** @throws BizException 40014 文件格式、表头或行数不对 */
    public Task submit(long operatorId, String fileName, byte[] content) {
        List<RawRow> rows = ImportFileReader.read(fileName, content);
        long id = tasks.create(fileName, rows.size(), operatorId);
        executor.execute(() -> process(id, rows));
        return tasks.find(id).orElseThrow();
    }

    void process(long id, List<RawRow> rows) {
        if (!tasks.markRunning(id)) {
            return;
        }
        try {
            tx.executeWithoutResult(status -> importRows(id, rows));
        } catch (DuplicateKeyException e) {
            log.warn("导入任务 {} 与并发的录入冲突，整批回滚", id);
            tasks.fail(id, "与同时进行的导入或录入冲突，本批未入库，请重新导入");
        } catch (RuntimeException e) {
            log.error("导入任务 {} 失败，整批回滚", id, e);
            tasks.fail(id, "导入失败（系统错误），本批未入库，请重新导入");
        }
    }

    private void importRows(long id, List<RawRow> rows) {
        List<FailedRow> failed = new ArrayList<>();
        List<RawRow> candidates = new ArrayList<>();
        List<DeviceDraft> drafts = new ArrayList<>();
        Map<String, Integer> seenSn = new HashMap<>();
        Map<String, Integer> seenImei = new HashMap<>();
        Map<String, Integer> seenMac = new HashMap<>();

        for (RawRow raw : rows) {
            DeviceDraft d;
            try {
                d = DeviceRules.validate(raw.sn(), raw.imei(), raw.bleMac(), null, raw.model(), raw.batchNo());
            } catch (DeviceRules.Invalid e) {
                failed.add(failure(raw, e.getMessage()));
                continue;
            }
            String dup = firstDuplicate(raw.row(), d, seenSn, seenImei, seenMac);
            if (dup != null) {
                failed.add(failure(raw, dup));
                continue;
            }
            candidates.add(raw);
            drafts.add(d);
        }

        Set<String> snInDb =
                devices.existing("sn", drafts.stream().map(DeviceDraft::sn).toList());
        Set<String> imeiInDb =
                devices.existing("imei", drafts.stream().map(DeviceDraft::imei).toList());
        Set<String> macInDb = devices.existing(
                "ble_mac",
                drafts.stream().map(DeviceDraft::bleMac).filter(m -> m != null).toList());

        List<NewDevice> accepted = new ArrayList<>();
        List<PrintItem> print = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            DeviceDraft d = drafts.get(i);
            String reason = snInDb.contains(d.sn())
                    ? "SN 已存在"
                    : imeiInDb.contains(d.imei())
                            ? "IMEI 已存在"
                            : d.bleMac() != null && macInDb.contains(d.bleMac()) ? "蓝牙 MAC 已存在" : null;
            if (reason != null) {
                failed.add(failure(candidates.get(i), reason));
                continue;
            }
            String code = codes.generate();
            accepted.add(new NewDevice(d, codes.hash(code)));
            print.add(new PrintItem(d.sn(), code, d.model(), d.batchNo()));
        }

        devices.insertAll(accepted);
        failed.sort((a, b) -> Integer.compare(a.row(), b.row()));
        byte[] payload = print.isEmpty() ? null : codes.seal(write(print));
        String failedJson = new String(write(failed), StandardCharsets.UTF_8);
        if (!tasks.finish(id, accepted.size(), failed.size(), failedJson, payload)) {
            // 任务已被超时清理记为失败：回滚，避免设备入库而激活码无处导出
            throw new IllegalStateException("导入任务 " + id + " 已不在处理中，回滚本批");
        }
        log.info("导入任务 {} 完成：成功 {} 行，失败 {} 行", id, accepted.size(), failed.size());
    }

    /** 文件内重复：以先出现的行为准，后出现的记为“与第 N 行重复” */
    private static String firstDuplicate(
            int row,
            DeviceDraft d,
            Map<String, Integer> seenSn,
            Map<String, Integer> seenImei,
            Map<String, Integer> seenMac) {
        Integer earlier = seenSn.get(d.sn());
        if (earlier != null) {
            return "SN 与第 " + earlier + " 行重复";
        }
        earlier = seenImei.get(d.imei());
        if (earlier != null) {
            return "IMEI 与第 " + earlier + " 行重复";
        }
        earlier = d.bleMac() == null ? null : seenMac.get(d.bleMac());
        if (earlier != null) {
            return "蓝牙 MAC 与第 " + earlier + " 行重复";
        }
        seenSn.put(d.sn(), row);
        seenImei.put(d.imei(), row);
        if (d.bleMac() != null) {
            seenMac.put(d.bleMac(), row);
        }
        return null;
    }

    private static FailedRow failure(RawRow raw, String reason) {
        return new FailedRow(raw.row(), raw.sn(), raw.imei(), raw.bleMac(), raw.model(), raw.batchNo(), reason);
    }

    public <T> PageResult<T> page(int page, int size, Function<Task, T> mapper) {
        return new PageResult<>(
                tasks.count(),
                page,
                size,
                tasks.page(page, size).stream().map(mapper).toList());
    }

    public Task get(long id) {
        return tasks.find(id).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "导入任务不存在"));
    }

    public List<FailedRow> failedRows(long id) {
        get(id);
        String detail = tasks.failedDetail(id);
        if (detail == null) {
            return List.of();
        }
        try {
            return json.readValue(detail, new TypeReference<List<FailedRow>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("导入任务 " + id + " 的失败行无法解析", e);
        }
    }

    /**
     * 导出印刷文件，每个任务只能一次。
     *
     * @throws BizException 10004 任务不存在；10009 任务未完成或没有成功入库的设备；40015 已导出过
     */
    public byte[] exportPrintFile(long id) {
        return tx.execute(status -> {
            PrintState state =
                    tasks.lockForPrint(id).orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "导入任务不存在"));
            if (state.exportedAt() != null) {
                throw new BizException(ErrorCode.PRINT_ALREADY_EXPORTED);
            }
            if (state.status() != Status.DONE) {
                throw new BizException(ErrorCode.CONFLICT, "导入尚未完成，完成后再导出");
            }
            if (state.payload() == null || state.successRows() == 0) {
                throw new BizException(ErrorCode.CONFLICT, "本次导入没有成功入库的设备");
            }
            List<PrintItem> items = read(codes.open(state.payload()), new TypeReference<List<PrintItem>>() {});
            Function<String, String> qr = properties::qrContent;
            // 先生成完整文件再标记已导出；标记与清空激活码在同一事务内提交
            byte[] zip = ImportSheets.printZip(items, qr);
            tasks.markPrintExported(id);
            log.info("导入任务 {} 的印刷文件已导出（{} 台），激活码明文已清除", id, items.size());
            return zip;
        });
    }

    public int failStale() {
        int n = tasks.failStale(STALE_MINUTES, "处理中断（服务重启），本批未入库，请重新导入");
        if (n > 0) {
            log.warn("{} 个导入任务超过 {} 分钟未完成，已记为失败", n, STALE_MINUTES);
        }
        return n;
    }

    private byte[] write(Object value) {
        try {
            return json.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T read(byte[] bytes, TypeReference<T> type) {
        try {
            return json.readValue(bytes, type);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("印刷数据无法解析", e);
        }
    }
}
