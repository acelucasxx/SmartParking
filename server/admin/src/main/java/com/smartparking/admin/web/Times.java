package com.smartparking.admin.web;

import com.smartparking.admin.account.AdminAccounts;
import java.time.Instant;
import java.time.OffsetDateTime;

/** 接口时间统一按东八区输出 */
final class Times {

    private Times() {}

    static OffsetDateTime of(Instant at) {
        return at == null ? null : at.atZone(AdminAccounts.ZONE).toOffsetDateTime();
    }
}
