plugins {
    id("smartparking.java-module")
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-validation")
}

// ErrorCodeContractTest 读取接口契约，校验错误码与 docs/api 一致
val apiDocsDir = rootDir.resolve("../docs/api").normalize()

tasks.test {
    inputs.dir(apiDocsDir).withPropertyName("apiDocs")
    systemProperty("smartparking.apiDocsDir", apiDocsDir.absolutePath)
}
