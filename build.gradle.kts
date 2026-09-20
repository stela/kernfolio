plugins {
    kotlin("jvm") version "2.4.20" apply false
    kotlin("plugin.spring") version "2.4.20" apply false
    kotlin("plugin.jpa") version "2.4.20" apply false
    id("org.springframework.boot") version "4.1.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("org.owasp.dependencycheck") version "13.0.0"
}

// Applied at the root so `./gradlew dependencyCheckAggregate` scans every
// subproject in one pass and writes a single de-duplicated report to
// build/reports/dependency-check/. Not wired into `check`/`build` — run it explicitly.
dependencyCheck {
    failBuildOnCVSS = 5.0f
    formats = listOf("HTML", "JSON")
    nvd {
        apiKey = findProperty("nvd.apiKey") as String?
    }
    // Skip classpaths that never reach the image: Kotlin compiler / build
    // tooling, the JTE template compiler, and developmentOnly (devtools).
    // (An allow-list via scanConfigurations makes the aggregate task scan no
    // jars at all, so this has to be a deny-list.)
    skipConfigurations = listOf(
        "kotlinCompilerClasspath",
        "kotlinBuildToolsApiClasspath",
        "kotlinCompilerPluginClasspathMain",
        "kotlinCompilerPluginClasspathTest",
        "kotlinKlibCommonizerClasspath",
        "kotlinInternalAbiValidation",
        "kotlinAbiValidationCompatClasspath",
        "jteKotlinCompiler",
        "developmentOnly",
    )
    analyzers {
        assemblyEnabled = false
        // WebJars embed package.json files; the node analyzers only produce
        // "no lock file" noise for them.
        nodePackage { enabled = false }
        nodeAudit { enabled = false }
        ossIndex {
            username = providers.gradleProperty("ossIndex.username").orNull
            password = providers.gradleProperty("ossIndex.password").orNull
        }
    }
}

// The plugin declares build-file inputs only, so after one passing run Gradle
// would report UP-TO-DATE and skip the scan even though new CVEs have been
// published since. Always re-run.
tasks.withType<org.owasp.dependencycheck.gradle.tasks.AbstractAnalyze>().configureEach {
    outputs.upToDateWhen { false }
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

val dockerComposeBuildProd = tasks.register<Exec>("dockerComposeBuildProd") {
    description = "Build Dockerfile-based compose services for production"
    commandLine(dockerExecutable, "compose", "build", "optimizer", "vault-init")
}

val dockerComposeBuildDev = tasks.register<Exec>("dockerComposeBuildDev") {
    description = "Build Dockerfile-based compose services for dev"
    commandLine(
        dockerExecutable, "compose",
        "-f", "docker-compose.yml", "-f", "docker-compose.dev.yml",
        "build", "optimizer", "vault-init",
    )
}

val dockerBuild = tasks.register("dockerBuild") {
    description = "Build production Docker images (web JVM image + optimizer + vault-init)"
    group = "docker"
    dependsOn(":web:bootBuildImage", dockerComposeBuildProd)
}

val dockerBuildDev = tasks.register("dockerBuildDev") {
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
