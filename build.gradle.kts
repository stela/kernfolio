plugins {
    kotlin("jvm") version "2.3.10" apply false
    kotlin("plugin.spring") version "2.3.10" apply false
    kotlin("plugin.jpa") version "2.3.10" apply false
    id("org.springframework.boot") version "4.0.5" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("org.owasp.dependencycheck") version "12.2.0" apply false
}

allprojects {
    group = "com.kernfolio"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

val dockerBuild by tasks.registering(Exec::class) {
    description = "Build production Docker images (web, optimizer)"
    group = "docker"
    dependsOn(":web:bootJar")
    commandLine("docker", "compose", "build", "web", "optimizer")
}

val dockerBuildDev by tasks.registering(Exec::class) {
    description = "Build all Docker images including digital twins"
    group = "docker"
    dependsOn(":web:bootJar", ":digital-twins:yfinance-fake:bootJar", ":digital-twins:frankfurter-fake:bootJar")
    commandLine(
        "docker", "compose",
        "-f", "docker-compose.yml", "-f", "docker-compose.dev.yml",
        "build", "web", "optimizer", "yfinance-twin", "frankfurter-twin"
    )
}

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        apply(plugin = "org.owasp.dependencycheck")
        extensions.configure<org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension> {
            failBuildOnCVSS = 5.0f
            formats = listOf("HTML", "JSON")
            nvd {
                apiKey = findProperty("nvd.apiKey") as String?
            }
            analyzers {
                assemblyEnabled = false
                ossIndex {
                    username = providers.gradleProperty("ossIndex.username").orNull
                    password = providers.gradleProperty("ossIndex.password").orNull
                }
            }
        }
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(25))
            }
        }
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            compilerOptions {
                freeCompilerArgs.addAll("-Xjsr305=strict")
            }
        }
    }
}
