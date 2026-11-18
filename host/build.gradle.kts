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
    runtimeOnly(libs.slf4j.nop)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
}

application {
    mainClass.set("dev.lilguy.host.MainKt")
}

// Run from the repo root so config/endpoints.yaml and runs/ resolve there.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}

tasks.test { useJUnitPlatform() }

// The web renderer, when it's been built (make web-build), goes in the jar; the host serves it at /room.
tasks.processResources { from(rootProject.file("web/dist")) { into("web") } }

// A real life's stream as a file, for the web client's test to replay (make web-test).
val recordStream by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Records a short scripted life's stream to build/stream-recording.jsonl"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.lilguy.host.StreamRecordingKt")
    args(layout.buildDirectory.file("stream-recording.jsonl").get().asFile.path)
}
