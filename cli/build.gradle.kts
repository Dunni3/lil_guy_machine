// Headless runs, evals and replays. No graphics.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":agent"))
}

application {
    mainClass.set("dev.lilguy.cli.MainKt")
}
