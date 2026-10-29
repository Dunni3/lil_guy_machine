plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// libGDX ships its .so files inside per-ABI jars; unpack them into jniLibs.
// The Streamer and Apple Silicon emulators are arm64; x86_64 covers Intel emulators.
val gdxAbis = listOf("arm64-v8a", "x86_64")
val natives: Configuration by configurations.creating
val nativesDir = layout.buildDirectory.dir("gdx-natives")

android {
    namespace = "dev.lilguy.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.lilguy.machine"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += gdxAbis }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    sourceSets["main"].jniLibs.srcDir(nativesDir)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":app"))
    implementation(libs.gdx.backend.android)
    gdxAbis.forEach { abi ->
        natives(variantOf(libs.gdx.platform) { classifier("natives-$abi") })
    }
}

val copyAndroidNatives by tasks.registering(Sync::class) {
    into(nativesDir)
    gdxAbis.forEach { abi ->
        into(abi) {
            from(provider { natives.filter { it.name.endsWith("natives-$abi.jar") }.map { zipTree(it) } })
            include("*.so")
        }
    }
}

tasks.matching { it.name.contains("merge") && it.name.contains("JniLibFolders") }.configureEach {
    dependsOn(copyAndroidNatives)
}
