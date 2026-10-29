// Joins :sim and :decision: one AgentRuntime per character.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":sim"))
    api(project(":decision"))
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
