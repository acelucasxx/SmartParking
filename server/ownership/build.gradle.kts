plugins {
    id("smartparking.java-module")
}

dependencies {
    api(project(":common"))
    // 扫码绑定（BIZ-04）：激活码校验在设备域，绑定成功后的状态查询在 iot
    implementation(project(":device"))
    implementation(project(":iot"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // 激活码错误计数与锁定（PRD 9：键按 SN，TTL 30 分钟）
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
}
