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

    // 数据库：连接池 + Flyway 迁移（脚本在 src/main/resources/db/migration）
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    runtimeOnly("com.mysql:mysql-connector-j")

    // 集成测试用 Testcontainers 拉起 MySQL 8.4，本机与 CI 都只需要 Docker
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mysql")
}

tasks.bootJar {
    archiveFileName.set("smartparking-server.jar")
}
