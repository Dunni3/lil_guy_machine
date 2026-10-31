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
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
