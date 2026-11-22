import org.springframework.boot.buildpack.platform.build.PullPolicy
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
    imageName.set("kernfolio-yfinance-twin:latest")
    environment.put("BP_JVM_VERSION", "25")
    // Always fetch the current builder and run image: the JRE (and its security
    // patches) comes from the builder's Liberica buildpack. Also the default.
    pullPolicy.set(PullPolicy.ALWAYS)
    // Spring Cloud Bindings (added by the Spring Boot buildpack) breaks Boot 4.2 apps.
    environment.put("BP_SPRING_CLOUD_BINDINGS_DISABLED", "true")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
