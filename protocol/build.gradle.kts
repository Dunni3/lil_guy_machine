// The sim host's wire format: what it streams to renderers and what admins send it. Pure Kotlin
// with no project dependencies, so any client (the web renderer through the generated TypeScript,
// a thin TV client, a Python harness through the JSON Schema) reads the same types.
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

// The checked-in schema and TypeScript must follow the types: `make protocol-types` regenerates them.
// Fails `make test` when either is stale (the TypeScript carries the hash of the schema it came from).
val checkProtocolTypes by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Fails when protocol/schema/protocol.schema.json or protocol/ts/protocol.ts is stale"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.lilguy.protocol.ProtocolSchemaKt")
    args("check", "schema/protocol.schema.json", "ts/protocol.ts")
    workingDir = projectDir
}
tasks.test { dependsOn(checkProtocolTypes) }

val writeProtocolSchema by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Writes protocol/schema/protocol.schema.json from the protocol types"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.lilguy.protocol.ProtocolSchemaKt")
    args("write", "schema/protocol.schema.json")
    workingDir = projectDir
}
