plugins {
    id("smartparking.spring-app")
}

dependencies {
    implementation(project(":common"))
    implementation(project(":iot"))
    implementation(project(":device"))
    implementation(project(":ownership"))
    implementation(project(":member"))
    implementation(project(":control"))
    implementation(project(":notify"))
    implementation(project(":admin"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
}

tasks.bootJar {
    archiveFileName.set("smartparking-server.jar")
}
