plugins {
    id("smartparking.java-module")
}

dependencies {
    api(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter")
    // 云端用 MQTT 5：下行消息需要设置过期时间，避免 broker 替离线设备排队（PRD 5.3④）
    implementation("org.eclipse.paho:org.eclipse.paho.mqttv5.client:1.2.5")
}

// CodeTableContractTest 读取接口契约，校验码表枚举名与 docs/api 一致
val apiDocsDir = rootDir.resolve("../docs/api").normalize()

tasks.test {
    inputs.dir(apiDocsDir).withPropertyName("apiDocs")
    systemProperty("smartparking.apiDocsDir", apiDocsDir.absolutePath)
}
