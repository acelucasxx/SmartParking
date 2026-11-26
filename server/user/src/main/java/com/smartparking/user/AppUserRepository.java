package com.smartparking.user;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code app_user} 的读写 */
public class AppUserRepository {

    /** 库内状态 */
    public enum Status {
        ACTIVE,
        BANNED
    }

    /** @param phoneCipher 未授权手机号时为空 */
    public record AppUser(long id, String openid, String nickname, byte[] phoneCipher, Status status) {}

    private static final String COLUMNS = "id, openid, nickname, phone_cipher, status";

    private final JdbcClient jdbc;

    public AppUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 按 openid 取用户，不存在则注册（首次登录自动注册）。微信返回 unionid 时补写，不会用空值覆盖已有的。 */
    public AppUser upsertByOpenid(String openid, String unionid) {
        jdbc.sql(
                        """
                        INSERT INTO app_user (openid, unionid) VALUES (:openid, :unionid) AS new
                        ON DUPLICATE KEY UPDATE unionid = COALESCE(new.unionid, app_user.unionid)""")
                .param("openid", openid)
                .param("unionid", unionid)
                .update();
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_user WHERE openid = ?")
                .param(openid)
                .query(AppUserRepository::map)
                .single();
    }

    public Optional<AppUser> find(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM app_user WHERE id = ?")
                .param(id)
                .query(AppUserRepository::map)
                .optional();
    }

    /** 只取状态，供每个请求的封禁检查使用 */
    public Optional<Status> status(long id) {
        return jdbc.sql("SELECT status FROM app_user WHERE id = ?")
                .param(id)
                .query((rs, n) -> Status.valueOf(rs.getString(1)))
                .optional();
    }

    public void touchLogin(long id) {
        jdbc.sql("UPDATE app_user SET last_login_at = CURRENT_TIMESTAMP(3) WHERE id = ?")
                .param(id)
                .update();
    }

    public void updatePhone(long id, byte[] cipher, String hash) {
        jdbc.sql("UPDATE app_user SET phone_cipher = ?, phone_hash = ? WHERE id = ?")
                .params(cipher, hash, id)
                .update();
    }

    public void updateNickname(long id, String nickname) {
        jdbc.sql("UPDATE app_user SET nickname = ? WHERE id = ?")
                .params(nickname, id)
                .update();
    }

    private static AppUser map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new AppUser(
                rs.getLong("id"),
                rs.getString("openid"),
                rs.getString("nickname"),
                rs.getBytes("phone_cipher"),
                Status.valueOf(rs.getString("status")));
    }
}
