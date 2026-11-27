package com.smartparking.admin.query;

import com.smartparking.common.error.BizException;
import com.smartparking.common.error.ErrorCode;
import com.smartparking.common.web.PageResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;

/** C 端用户列表与详情（PRD 6.2 用户管理）：手机号只显示脱敏值 */
public class AdminUserQueries {

    /** 详情里列出的授权记录上限（最近的在前） */
    static final int MEMBER_HISTORY_LIMIT = 100;

    private final JdbcClient jdbc;
    private final Phones phones;

    AdminUserQueries(JdbcClient jdbc, Phones phones) {
        this.jdbc = jdbc;
        this.phones = phones;
    }

    /**
     * 契约 AppUser。
     *
     * @param ownedCount 作为主人的设备数
     * @param memberCount 生效中的被授权记录数（含尚未开始的）
     */
    public record UserRow(
            long id,
            String nickname,
            String phoneMasked,
            String status,
            int ownedCount,
            int memberCount,
            Instant createdAt,
            Instant lastLoginAt) {}

    public record OwnedDevice(long deviceId, String sn, String name, Instant since) {}

    public record MemberDevice(
            long deviceId, String sn, String name, Instant validFrom, Instant validTo, String status) {}

    public record Detail(UserRow user, List<OwnedDevice> ownedDevices, List<MemberDevice> memberDevices) {}

    private static final String SELECT =
            """
            SELECT u.id, u.nickname, u.phone_cipher, u.status, u.created_at, u.last_login_at,
                   (SELECT COUNT(*) FROM device d WHERE d.owner_user_id = u.id AND d.bind_status = 'BOUND') AS owned_count,
                   (SELECT COUNT(*) FROM device_member m WHERE m.user_id = u.id AND m.status = 'ACTIVE') AS member_count
            FROM app_user u""";

    /** 按注册时间倒序；手机号完整 11 位精确匹配 */
    public PageResult<UserRow> list(String phone, String status, int page, int size) {
        Where w = new Where().and("u.phone_hash = ?", phones.hashOf(phone)).and("u.status = ?", status);
        long total = jdbc.sql("SELECT COUNT(*) FROM app_user u" + w.sql())
                .params(w.params())
                .query(Long.class)
                .single();
        List<UserRow> items = jdbc.sql(SELECT + w.sql() + " ORDER BY u.id DESC LIMIT ? OFFSET ?")
                .params(w.paged(page, size))
                .query(this::row)
                .list();
        return new PageResult<>(total, page, size, items);
    }

    /**
     * 用户不存在 10004。名下设备按成为主人的先后；授权记录含历史（已撤销、已过期、已退出），最近的在前，最多 100 条。
     */
    public Detail detail(long userId) {
        UserRow user = jdbc.sql(SELECT + " WHERE u.id = ?")
                .param(userId)
                .query(this::row)
                .optional()
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "用户不存在"));
        List<OwnedDevice> owned = jdbc.sql(
                        """
                        SELECT id, sn, name, owner_since FROM device
                        WHERE owner_user_id = ? AND bind_status = 'BOUND'
                        ORDER BY owner_since, id""")
                .param(userId)
                .query((rs, n) -> new OwnedDevice(
                        rs.getLong("id"), rs.getString("sn"), rs.getString("name"), Times.read(rs, "owner_since")))
                .list();
        List<MemberDevice> members = jdbc.sql(
                        """
                        SELECT m.device_id, d.sn, d.name, m.valid_from, m.valid_to, m.status
                        FROM device_member m JOIN device d ON d.id = m.device_id
                        WHERE m.user_id = ?
                        ORDER BY m.valid_from DESC, m.id DESC LIMIT ?""")
                .params(userId, MEMBER_HISTORY_LIMIT)
                .query((rs, n) -> new MemberDevice(
                        rs.getLong("device_id"),
                        rs.getString("sn"),
                        rs.getString("name"),
                        Times.read(rs, "valid_from"),
                        Times.read(rs, "valid_to"),
                        rs.getString("status")))
                .list();
        return new Detail(user, owned, members);
    }

    private UserRow row(ResultSet rs, int n) throws SQLException {
        return new UserRow(
                rs.getLong("id"),
                Times.nickname(rs.getString("nickname")),
                phones.masked(rs.getBytes("phone_cipher")),
                rs.getString("status"),
                rs.getInt("owned_count"),
                rs.getInt("member_count"),
                Times.read(rs, "created_at"),
                Times.read(rs, "last_login_at"));
    }
}
