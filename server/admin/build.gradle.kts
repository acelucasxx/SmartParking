plugins {
    id("smartparking.java-module")
}

dependencies {
    api(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // 后台会话存 Redis（BIZ-08）：退出登录、停用、重置密码立即生效
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    // 只用密码哈希（BCrypt），不引入 Spring Security 的过滤器链
    implementation("org.springframework.security:spring-security-crypto")
}
