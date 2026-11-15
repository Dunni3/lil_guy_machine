// Pure Kotlin world. No graphics or network imports here.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    // The bedroom's content files (content/bedroom/*.yaml).
    implementation(libs.kaml)
    // The verbs he writes are JavaScript, run in a sandbox (Scripts.kt).
    implementation(libs.rhino)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
