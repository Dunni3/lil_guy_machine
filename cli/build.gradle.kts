// Headless runs, evals and replays. No graphics.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    runtimeOnly(libs.slf4j.nop)
    implementation(project(":agent"))
}

application {
    mainClass.set("dev.lilguy.cli.MainKt")
}

// Run from the repo root so config/endpoints.yaml and runs/ resolve there.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
