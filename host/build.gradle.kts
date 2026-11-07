// The sim host: runs lives headless in real time (the same SimSession loop the app runs) and
// streams their state to renderers over WebSocket. No libGDX.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":agent"))
    implementation(project(":protocol"))
    implementation(project(":runlog-jdbc"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.sessions)
    implementation(libs.ktor.client.okhttp)
    runtimeOnly(libs.slf4j.nop)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
}

application {
    mainClass.set("dev.lilguy.host.MainKt")
}

// Run from the repo root so config/endpoints.yaml and runs/ resolve there.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.test { useJUnitPlatform() }
