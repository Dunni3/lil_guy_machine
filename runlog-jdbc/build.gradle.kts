// The run store's SQLite driver on the JVM (CLI, desktop, the sim host). Not in the TV build:
// sqlite-jdbc's natives aren't packaged for its 32-bit userspace, so :android has its own driver.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":runlog"))
    implementation(libs.sqlite.jdbc)
}
