package com.smartparking.ownership.bind;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 激活码错误计数与锁定（PRD 5.2④、9）：键按 SN，{@code sp:bind-fail:<SN>}。
 *
 * <p>每次尝试<b>先占用一次机会</b>（原子 INCR），再校验激活码：并发提交再多，第 6 次起一律锁定，不会出现“先查后记”之间多试几次的窗口。
 *
 * <ul>
 *   <li>输错：保留这次计数。首次计数起 30 分钟内无新尝试则自动清零。
 *   <li>第 5 次时把 TTL 重置为完整的 30 分钟：锁定从第 5 次错误起算满 30 分钟；锁定期间再试不会延长锁定。
 *   <li>没走到校验激活码的尝试（设备不存在、已被绑定、已禁用）归还这次机会；绑定成功清零（“连续”输错才锁定）。
 * </ul>
 *
 * <p>按 SN 而不是按用户计数：激活码只有 8 位，换账号重试也必须被限住。代价是他人可以故意输错让该 SN 锁 30 分钟，PRD 接受此取舍。
 */
public class BindAttempts {

    public static final int MAX_FAILURES = 5;
    public static final Duration LOCK = Duration.ofMinutes(30);

    private static final RedisScript<Long> RESERVE = new DefaultRedisScript<>(
            """
            local n = redis.call('INCR', KEYS[1])
            if n == 1 or n == tonumber(ARGV[1]) then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return n
            """,
            Long.class);

    /** 键已过期时不能 DECR，否则会留下一个没有 TTL 的 -1 */
    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>(
            """
            if redis.call('EXISTS', KEYS[1]) == 1 then
              return redis.call('DECR', KEYS[1])
            end
            return 0
            """,
            Long.class);

    private final StringRedisTemplate redis;

    public BindAttempts(StringRedisTemplate redis) {
        this.redis = redis;
    }

    static String key(String sn) {
        return "sp:bind-fail:" + sn.toUpperCase(Locale.ROOT);
    }

    /** 占用一次机会，返回这是第几次；大于 {@link #MAX_FAILURES} 表示已锁定 */
    public long reserve(String sn) {
        Long n = redis.execute(
                RESERVE, List.of(key(sn)), Integer.toString(MAX_FAILURES), Long.toString(LOCK.toMillis()));
        return n == null ? 1 : n;
    }

    public void release(String sn) {
        redis.execute(RELEASE, List.of(key(sn)));
    }

    public void clear(String sn) {
        redis.delete(key(sn));
    }

    /** 锁定剩余时长 */
    public Duration remaining(String sn) {
        Long ttl = redis.getExpire(key(sn));
        return Duration.ofSeconds(ttl == null || ttl < 0 ? LOCK.toSeconds() : ttl);
    }
}
