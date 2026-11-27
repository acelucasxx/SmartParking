package com.smartparking.device.registry;

import com.smartparking.device.registry.DeviceRules.DeviceDraft;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/** 设备主档写入与唯一性查询 */
public class DeviceRepository {

    /** 每次 IN 查询的上限，避免超长 SQL */
    static final int CHUNK = 500;

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public DeviceRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    public record Inserted(long id, LocalDateTime createdAt) {}

    /** 待插入的一台设备与它的激活码哈希 */
    public record NewDevice(DeviceDraft draft, String codeHash) {}

    public boolean snExists(String sn) {
        return exists("sn", sn);
    }

    public boolean imeiExists(String imei) {
        return exists("imei", imei);
    }

    public boolean macExists(String mac) {
        return mac != null && exists("ble_mac", mac);
    }

    private boolean exists(String column, String value) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM device WHERE " + column + " = ?)")
                .param(value)
                .query(Boolean.class)
                .single();
    }

    /** 给定的取值里已在库中的那些；{@code column} 只取 sn / imei / ble_mac */
    public Set<String> existing(String column, Collection<String> values) {
        if (!Set.of("sn", "imei", "ble_mac").contains(column)) {
            throw new IllegalArgumentException(column);
        }
        Set<String> found = new HashSet<>();
        List<String> list = new ArrayList<>(values);
        for (int from = 0; from < list.size(); from += CHUNK) {
            List<String> chunk = list.subList(from, Math.min(list.size(), from + CHUNK));
            found.addAll(jdbc.sql("SELECT " + column + " FROM device WHERE " + column + " IN (:values)")
                    .param("values", chunk)
                    .query(String.class)
                    .list());
        }
        return found;
    }

    /** @throws org.springframework.dao.DuplicateKeyException SN / IMEI / MAC 已存在（并发录入） */
    public Inserted insert(DeviceDraft d, String codeHash) {
        var keys = new GeneratedKeyHolder();
        template.update(
                con -> {
                    PreparedStatement ps = con.prepareStatement(
                            """
                            INSERT INTO device (sn, imei, ble_mac, iccid, model, batch_no, activate_code_hash)
                            VALUES (?, ?, ?, ?, ?, ?, ?)""",
                            Statement.RETURN_GENERATED_KEYS);
                    bind(ps, d, codeHash);
                    return ps;
                },
                keys);
        long id = keys.getKey().longValue();
        LocalDateTime createdAt = jdbc.sql("SELECT created_at FROM device WHERE id = ?")
                .param(id)
                .query(LocalDateTime.class)
                .single();
        return new Inserted(id, createdAt);
    }

    /** 批量插入，须在调用方事务内；任一行违反唯一约束则整批失败 */
    public void insertAll(List<NewDevice> devices) {
        template.batchUpdate(
                """
                INSERT INTO device (sn, imei, ble_mac, iccid, model, batch_no, activate_code_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?)""",
                devices,
                CHUNK,
                (ps, d) -> bind(ps, d.draft(), d.codeHash()));
    }

    private static void bind(PreparedStatement ps, DeviceDraft d, String codeHash) throws java.sql.SQLException {
        ps.setString(1, d.sn());
        ps.setString(2, d.imei());
        ps.setString(3, d.bleMac());
        ps.setString(4, d.iccid());
        ps.setString(5, d.model());
        ps.setString(6, d.batchNo());
        ps.setString(7, codeHash);
    }
}
