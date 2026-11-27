plugins {
    id("smartparking.java-module")
}

dependencies {
    api(project(":common"))
    // 后台查询（BIZ-09）：手机号脱敏与按手机号查询用用户域的 PhoneCipher，在线状态按当前时刻判定用 iot
    implementation(project(":user"))
    implementation(project(":iot"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // 后台会话存 Redis（BIZ-08）：退出登录、停用、重置密码立即生效
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    // 只用密码哈希（BCrypt），不引入 Spring Security 的过滤器链
    implementation("org.springframework.security:spring-security-crypto")
    // 指令流水导出 xlsx（流式写出）
    implementation("org.dhatim:fastexcel:0.18.4")
}
