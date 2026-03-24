plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// Override BOM versions ahead of next Spring Boot release (fix for CVE-2026-29062)
// See: https://github.com/spring-projects/spring-boot/issues/49383
extra["jackson.version"] = "3.1.0"
extra["spring-framework.version"] = "7.0.6"

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.fasterxml.jackson.core" && requested.name == "jackson-annotations") {
            useVersion("2.21")
            because("Jackson 3.1.0 requires jackson-annotations 2.21")
        }
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.boot:spring-boot-liquibase")
    implementation("org.liquibase:liquibase-core")
    implementation("io.github.wimdeblauwe:htmx-spring-boot-thymeleaf:5.1.0")
    implementation("org.webjars.npm:htmx.org:2.0.4")
    implementation("org.webjars.npm:alpinejs:3.15.8")
    implementation("org.webjars.npm:chart.js:4.5.1")
    runtimeOnly("org.postgresql:postgresql")

    developmentOnly("org.springframework.boot:spring-boot-devtools")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.mockk:mockk:1.14.9")
    testImplementation("org.wiremock:wiremock-standalone:3.13.0")
    testRuntimeOnly("com.h2database:h2")
}

val tailwindBuild by tasks.registering(Exec::class) {
    description = "Build Tailwind CSS from source"
    val inputCss = file("src/main/resources/static/css/input.css")
    val outputCss = file("src/main/resources/static/css/tailwind.css")
    inputs.file(inputCss)
    inputs.files(fileTree("src/main/resources/templates") { include("**/*.html") })
    outputs.file(outputCss)
    commandLine("tailwindcss", "-i", inputCss.path, "-o", outputCss.path, "--minify")
}

tasks.named("processResources") {
    dependsOn(tailwindBuild)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
