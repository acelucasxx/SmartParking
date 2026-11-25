plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-gradle-plugin:3.5.7")
    implementation("com.diffplug.spotless:spotless-plugin-gradle:7.0.2")
}
