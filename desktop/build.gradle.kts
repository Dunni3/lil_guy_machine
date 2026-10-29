plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    runtimeOnly(libs.slf4j.nop)
    implementation(project(":app"))
    implementation(libs.gdx.backend.lwjgl3)
    implementation(variantOf(libs.gdx.platform) { classifier("natives-desktop") })
}

application {
    mainClass.set("dev.lilguy.desktop.DesktopLauncherKt")
    // GLFW on macOS must own the first thread.
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        applicationDefaultJvmArgs = listOf("-XstartOnFirstThread")
    }
}

// Run from the repo root so config/endpoints.yaml and runs/ resolve there.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
