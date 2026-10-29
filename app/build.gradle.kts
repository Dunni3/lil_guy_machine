// libGDX renderer + wiring, shared by :desktop and :android.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(libs.gdx)
    api(project(":agent"))
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
