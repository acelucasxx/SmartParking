plugins {
    id("smartparking.java-module")
    application
}

dependencies {
    implementation(project(":iot"))
    // 站在云端一侧：与业务服务一样用 MQTT 5，下行带消息过期时间，设备离线时不排队
    implementation("org.eclipse.paho:org.eclipse.paho.mqttv5.client:1.2.5")
    implementation("org.slf4j:slf4j-api")
    runtimeOnly("ch.qos.logback:logback-classic")

    // 测试用 DTU 模拟器冒充真机
    testImplementation(project(":dtu-sim"))
    testImplementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    testImplementation("org.testcontainers:junit-jupiter")
}

application {
    mainClass.set("com.smartparking.dtubench.DtuBench")
}
