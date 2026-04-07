plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("gg.jte.gradle") version "3.2.3"
}

// Override Jackson version ahead of next Spring Boot release
extra["jackson.version"] = "3.1.0"

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.fasterxml.jackson.core" && requested.name == "jackson-annotations") {
            useVersion("2.21")
            because("Jackson 3.1.0 requires jackson-annotations 2.21")
        }
    }
}

dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter-vault-config:5.0.1")
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
    implementation("gg.jte:jte:3.2.3")
    implementation("gg.jte:jte-kotlin:3.2.3")
    implementation("gg.jte:jte-spring-boot-starter-4:3.2.3")
    implementation("org.webjars.npm:chart.js:4.5.1")
    implementation("org.postgresql:postgresql")

    developmentOnly("org.springframework.boot:spring-boot-devtools")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("org.wiremock:wiremock-standalone:3.13.0")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.seleniumhq.selenium:selenium-java:4.33.0")
    testImplementation("org.testcontainers:testcontainers-selenium")
    testRuntimeOnly("com.h2database:h2")
}

val tailwindBuild by tasks.registering(Exec::class) {
    description = "Build Tailwind CSS from source"
    val inputCss = file("src/main/resources/static/css/input.css")
    val outputCss = file("src/main/resources/static/css/tailwind.css")
    inputs.file(inputCss)
    inputs.files(fileTree("src/main/jte") { include("**/*.kte") })
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

tasks.withType<Test> {
    useJUnitPlatform()
}
