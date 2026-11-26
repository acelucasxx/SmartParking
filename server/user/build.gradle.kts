plugins {
    id("smartparking.java-module")
}

dependencies {
    api(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // 会话存 Redis：退出登录与封禁可立即失效
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
}
