plugins {
    id("smartparking.java-module")
}

dependencies {
    api(project(":common"))
    // 实现 iot 的 DeviceDirectory 端口：设备表归设备域，iot 只通过接口查询
    implementation(project(":iot"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
}
