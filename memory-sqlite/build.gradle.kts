// The long-term memory in a SQLite file. JVM only (CLI, desktop); the TV uses InMemoryStore until it gets an Android-side store.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":agent"))
    implementation(libs.sqlite.jdbc)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
