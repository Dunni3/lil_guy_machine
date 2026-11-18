// The run database's row types, the local store and the sync worker. Pure Kotlin: the SQLite
// driver is passed in (:runlog-jdbc on the JVM, android.database.sqlite on the TV).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
