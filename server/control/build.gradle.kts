plugins {
    id("smartparking.java-module")
}

dependencies {
    api(project(":common"))
    // 远程控制接口（BIZ-05）调用 iot 的指令状态机；iot 不感知用户与权限，由这里判定后传入发起人
    implementation(project(":iot"))
    implementation("org.springframework.boot:spring-boot-starter")
    // 权限判定直接查 device / device_member / app_user（PRD 9）
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
}
