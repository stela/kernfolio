plugins {
    kotlin("jvm") version "2.3.20" apply false
    kotlin("plugin.spring") version "2.3.20" apply false
    kotlin("plugin.jpa") version "2.3.20" apply false
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

// JVM images (web, digital twins) are produced via Spring Boot's
// bootBuildImage task (Paketo buildpacks; see each module's build.gradle.kts
// for the image tag). Non-JVM images (optimizer, vault-init) are still built
// from Dockerfiles via `docker compose build`. The aggregate tasks below tie
// both paths together so the user never has to remember the right sequence.
// Gradle daemons started via IDEs or system services often have a minimal
// PATH that doesn't include /usr/local/bin or /opt/homebrew/bin, so resolve
// the docker binary explicitly once at configuration time.
val dockerExecutable: String = listOf(
    "/usr/local/bin/docker",
    "/opt/homebrew/bin/docker",
    "/usr/bin/docker",
).firstOrNull { java.io.File(it).canExecute() } ?: "docker"

val dockerComposeBuildProd by tasks.registering(Exec::class) {
    description = "Build Dockerfile-based compose services for production"
    commandLine(dockerExecutable, "compose", "build", "optimizer", "vault-init")
}

val dockerComposeBuildDev by tasks.registering(Exec::class) {
    description = "Build Dockerfile-based compose services for dev"
    commandLine(
        dockerExecutable, "compose",
        "-f", "docker-compose.yml", "-f", "docker-compose.dev.yml",
        "build", "optimizer", "vault-init",
    )
}

val dockerBuild by tasks.registering {
    description = "Build production Docker images (web JVM image + optimizer + vault-init)"
    group = "docker"
    dependsOn(":web:bootBuildImage", dockerComposeBuildProd)
}

val dockerBuildDev by tasks.registering {
    description = "Build every Docker image used by the dev stack"
    group = "docker"
    dependsOn(
        ":web:bootBuildImage",
        ":digital-twins:yfinance-fake:bootBuildImage",
        ":digital-twins:frankfurter-fake:bootBuildImage",
        dockerComposeBuildDev,
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
