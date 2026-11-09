// Joins :sim and :decision: one AgentRuntime per character.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":sim"))
    api(project(":decision"))
    api(project(":runlog"))
    testImplementation(kotlin("test"))
    testImplementation(project(":runlog-jdbc"))
}

tasks.test { useJUnitPlatform() }
