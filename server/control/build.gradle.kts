plugins {
    id("smartparking.java-module")
}

dependencies {
    api(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter")
    // 权限判定直接查 device / device_member / app_user（PRD 9）
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
}
