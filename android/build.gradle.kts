plugins {
    id("com.android.library")
    kotlin("android")
    id("maven-publish")
}

android {
    namespace = "dev.mitranalytics.sdk.android"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

// JitPack builds this publication and republishes it under
// com.github.shiv9thakur:mitr-sdk-kotlin:<tag> — groupId/artifactId/version
// here are placeholders JitPack overrides at build time.
afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.shiv9thakur"
                artifactId = "mitr-sdk-kotlin"
            }
        }
    }
}
