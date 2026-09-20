plugins {
    base
}

val uvSync = tasks.register<Exec>("uvSync") {
    description = "Install Python dependencies via uv"
    workingDir = projectDir
    commandLine("uv", "sync", "--frozen", "--extra", "dev")
    inputs.file("pyproject.toml")
    inputs.file("uv.lock")
    outputs.dir(".venv")
}

val test = tasks.register<Exec>("test") {
    description = "Run pytest via uv"
    dependsOn(uvSync)
    workingDir = projectDir
    commandLine("uv", "run", "pytest")
}

val audit = tasks.register<Exec>("audit") {
    description = "Run pip-audit via uv"
    dependsOn(uvSync)
    workingDir = projectDir
    commandLine("uv", "run", "pip-audit")
}

tasks.named("build") {
    dependsOn(test)
}
