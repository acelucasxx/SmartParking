plugins {
    id("smartparking.java-module")
    application
}

dependencies {
    implementation(project(":iot"))
    // DTU（B100Ym-C1）按 MQTT 3.1.1 接入，模拟器用同协议版本的客户端
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("org.slf4j:slf4j-api")
    runtimeOnly("ch.qos.logback:logback-classic")

    testImplementation("org.testcontainers:junit-jupiter")
}

application {
    mainClass.set("com.smartparking.dtusim.DtuSimulator")
}

tasks.named<JavaExec>("run") {
    // 控制台命令从标准输入读取
    standardInput = System.`in`
}
