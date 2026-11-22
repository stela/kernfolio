import org.springframework.boot.gradle.tasks.bundling.BootBuildImage

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<BootBuildImage>("bootBuildImage") {
    imageName.set("kernfolio-frankfurter-twin:latest")
    environment.put("BP_JVM_VERSION", "25")
    // Spring Cloud Bindings (added by the Spring Boot buildpack) breaks Boot 4.2 apps.
    environment.put("BP_SPRING_CLOUD_BINDINGS_DISABLED", "true")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
