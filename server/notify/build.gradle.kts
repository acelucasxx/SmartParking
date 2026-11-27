plugins {
    id("smartparking.java-module")
}

dependencies {
    api(project(":common"))
    // 告警（BIZ-07）订阅 iot 的状态变化、在线状态变化、指令终结事件
    implementation(project(":iot"))
    // 站内消息（BIZ-15）订阅授权、转让、解绑事件
    implementation(project(":member"))
    implementation(project(":ownership"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
}
