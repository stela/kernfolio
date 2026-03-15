plugins {
    base
}

val uvSync by tasks.registering(Exec::class) {
    description = "Install Python dependencies via uv"
    workingDir = projectDir
    commandLine("uv", "sync", "--frozen", "--extra", "dev")
    inputs.file("pyproject.toml")
    inputs.file("uv.lock")
    outputs.dir(".venv")
}

val test by tasks.registering(Exec::class) {
    description = "Run pytest via uv"
    dependsOn(uvSync)
    workingDir = projectDir
    commandLine("uv", "run", "pytest")
}

val audit by tasks.registering(Exec::class) {
    description = "Run pip-audit via uv"
    dependsOn(uvSync)
    workingDir = projectDir
    commandLine("uv", "run", "pip-audit")
}

tasks.named("build") {
    dependsOn(test)
}
