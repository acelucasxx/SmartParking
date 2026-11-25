plugins {
    id("smartparking.java-module")
}

dependencies {
    api(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter")
}
