plugins {
    kotlin("jvm") version "2.3.10" apply false
    kotlin("plugin.spring") version "2.3.10" apply false
    kotlin("plugin.jpa") version "2.3.10" apply false
    id("org.springframework.boot") version "4.0.3" apply false
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
