plugins {
    id("smartparking.java-module")
}

dependencies {
    api(project(":common"))
    // 邀请家人（BIZ-11）：设备级权限判定与车位卡片在 control；库内时间工具在 iot
    implementation(project(":control"))
    implementation(project(":iot"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
}
