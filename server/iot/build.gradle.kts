plugins {
    id("smartparking.java-module")
}

dependencies {
    api(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter")
}

// CodeTableContractTest 读取接口契约，校验码表枚举名与 docs/api 一致
val apiDocsDir = rootDir.resolve("../docs/api").normalize()

tasks.test {
    inputs.dir(apiDocsDir).withPropertyName("apiDocs")
    systemProperty("smartparking.apiDocsDir", apiDocsDir.absolutePath)
}
