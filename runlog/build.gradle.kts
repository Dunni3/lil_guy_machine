// What a run records, as rows: shared by every writer (CLI, desktop, TV) and, later, the server. Pure Kotlin.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

// The commit a build came from, so every run row says what code made it. "unknown" outside a git checkout.
val gitSha = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }
val gitDirty = providers.exec {
    commandLine("git", "status", "--porcelain", "--untracked-files=no")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.isNotBlank() }

val buildInfo = tasks.register("buildInfo") {
    val out = layout.buildDirectory.dir("generated/build-info")
    inputs.property("sha", gitSha)
    inputs.property("dirty", gitDirty)
    outputs.dir(out)
    doLast {
        val file = out.get().file("dev/lilguy/runlog/build.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("git_sha=${gitSha.get()}\ngit_dirty=${gitDirty.get()}\n")
    }
}
sourceSets.main { resources.srcDir(buildInfo) }
