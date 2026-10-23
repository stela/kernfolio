import org.springframework.boot.gradle.tasks.bundling.BootBuildImage

plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("gg.jte.gradle") version "3.2.4"
}

dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter-vault-config:5.0.3")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.boot:spring-boot-liquibase")
    implementation("org.liquibase:liquibase-core")
    implementation("gg.jte:jte:3.2.4")
    implementation("gg.jte:jte-kotlin:3.2.4")
    implementation("gg.jte:jte-spring-boot-starter-4:3.2.4")
    implementation("org.webjars.npm:chart.js:4.5.1")
    implementation("org.postgresql:postgresql")
    implementation("org.springframework.boot:spring-boot-starter-mail")

    constraints {
        implementation("org.bouncycastle:bcprov-jdk18on:1.86") {
            because("spring-cloud-starter 5.0.4 still pulls 1.85.2, which has known CVEs; drop once Spring Cloud catches up")
        }
    }

    developmentOnly("org.springframework.boot:spring-boot-devtools")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.seleniumhq.selenium:selenium-java:4.51.0")
    testImplementation("org.testcontainers:testcontainers-selenium")
    testImplementation("com.icegreen:greenmail-junit5:2.1.14")
    testRuntimeOnly("com.h2database:h2")
}

val tailwindBuild = tasks.register<Exec>("tailwindBuild") {
    description = "Build Tailwind CSS from source"
    val inputCss = file("src/main/resources/static/css/input.css")
    val outputCss = file("src/main/resources/static/css/tailwind.css")
    inputs.file(inputCss)
    inputs.files(fileTree("src/main/jte") { include("**/*.kte") })
    inputs.files(fileTree("src/main/resources/static/js") { include("**/*.js") })
    outputs.file(outputCss)
    commandLine("tailwindcss", "-i", inputCss.path, "-o", outputCss.path, "--minify")
}

tasks.named("processResources") {
    dependsOn(tailwindBuild)
}

jte {
    sourceDirectory.set(file("src/main/jte").toPath())
    contentType.set(gg.jte.ContentType.Html)
    generate()
}

tasks.named<BootBuildImage>("bootBuildImage") {
    imageName.set("kernfolio-web:latest")
    // Default Paketo builder (noble-java-tiny) — a distroless-style runtime
    // with only the JRE and app. No shell, wget, curl, or nc, which is why
    // the compose service for this image has no HEALTHCHECK. See docker-
    // compose.yml for the explanation.
    environment.put("BP_JVM_VERSION", "25")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // OpenBao is wired in by the dev and prod profiles (via spring.config.import
    // in application-{dev,prod}.yml). Tests don't activate either profile, so
    // they never trigger the Spring Cloud Vault config loader. Explicitly disable
    // the auto-config as well so Spring Cloud Vault beans are not created.
    systemProperty("spring.cloud.vault.enabled", "false")
    systemProperty("spring.cloud.vault.config.lifecycle.enabled", "false")
    // The mTLS integration tests run repo files from outside this module: the
    // optimizer image, the postgres TLS config and compose's optimizer
    // healthcheck. Declare them so edits there re-run the tests instead of
    // leaving a stale up-to-date result.
    inputs.files(
        rootProject.fileTree("optimizer") {
            include("Dockerfile", "pyproject.toml", "uv.lock", "run_mtls.py", "app/**")
            exclude("**/__pycache__/**")
        },
        rootProject.fileTree("postgres"),
        rootProject.file("docker-compose.yml"),
    ).withPropertyName("repoFilesUnderTest").withPathSensitivity(PathSensitivity.RELATIVE)
}
