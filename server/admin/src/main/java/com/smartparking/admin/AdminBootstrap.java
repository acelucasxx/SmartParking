package com.smartparking.admin;

import com.smartparking.admin.account.AdminAccounts;
import com.smartparking.admin.account.Passwords;
import com.smartparking.common.web.AdminRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;

/**
 * 初始超级管理员：库里没有任何后台账号、且配置了 {@code smartparking.admin.bootstrap-password} 时，启动时创建。该密码按临时密码处理，
 * 首次登录必须修改。已有账号时什么也不做，所以上线后可以删掉这项配置。
 */
class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AdminAccounts accounts;
    private final Passwords passwords;
    private final AdminProperties properties;

    AdminBootstrap(AdminAccounts accounts, Passwords passwords, AdminProperties properties) {
        this.accounts = accounts;
        this.passwords = passwords;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (accounts.count() > 0) {
            return;
        }
        String password = properties.bootstrapPassword();
        if (password == null || password.isBlank()) {
            log.warn("还没有任何后台账号：设置 smartparking.admin.bootstrap-password（ADMIN_BOOTSTRAP_PASSWORD）以创建初始超级管理员");
            return;
        }
        try {
            accounts.create(properties.bootstrapUsername(), passwords.hash(password), "超级管理员", AdminRole.SUPER_ADMIN);
            log.warn("已创建初始超级管理员 {}，首次登录必须修改密码", properties.bootstrapUsername());
        } catch (DuplicateKeyException e) {
            // 多实例同时启动，另一实例已创建
        }
    }
}
