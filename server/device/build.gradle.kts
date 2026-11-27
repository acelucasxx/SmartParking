plugins {
    id("smartparking.java-module")
}

dependencies {
    api(project(":common"))
    // 实现 iot 的 DeviceDirectory 端口：设备表归设备域，iot 只通过接口查询
    implementation(project(":iot"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    // 设备入库（BIZ-03）：读写 xlsx（流式，不引入 POI）与印刷文件中的二维码
    implementation("org.dhatim:fastexcel:0.18.4")
    implementation("org.dhatim:fastexcel-reader:0.18.4")
    implementation("com.google.zxing:core:3.5.3")
}
