plugins {
    id("smartparking.java-module")
}

dependencies {
    api(project(":common"))
    // 告警（BIZ-07）订阅 iot 的状态变化、在线状态变化、指令终结事件
    implementation(project(":iot"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
}
