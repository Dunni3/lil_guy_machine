// The room's models as data: part lists in pure Kotlin. :app draws them today and the web renderer
// reads them through the export (parts/export/parts.json), so they're written down once and outlast the libGDX app.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.kotlinx.serialization.json)
}

tasks.test { useJUnitPlatform() }

// The checked-in export must follow the part lists: `make parts` regenerates it, and `make test` fails when it's stale.
val checkParts by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Fails when parts/export/parts.json is stale"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.lilguy.parts.PartExportKt")
    args("check", "export/parts.json")
    workingDir = projectDir
}
tasks.test { dependsOn(checkParts) }

val writeParts by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Writes parts/export/parts.json from the part lists"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.lilguy.parts.PartExportKt")
    args("write", "export/parts.json")
    workingDir = projectDir
}
