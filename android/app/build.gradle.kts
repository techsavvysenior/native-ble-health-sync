plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose")
}

fun setting(name: String): String =
    providers.gradleProperty("pulselog.firebase.$name")
        .orElse(providers.environmentVariable("PULSELOG_FIREBASE_" + name.uppercase()))
        .getOrElse("")

android {
    namespace = "com.example.pulselog"
    // Compose 1.13 needs the Android 17 QPR SDK (37.1 or later) to compile against.
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "com.example.pulselog"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${setting("projectId")}\"")
        buildConfigField("String", "FIREBASE_APPLICATION_ID", "\"${setting("applicationId")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${setting("apiKey")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        // targetSdk is held one below compileSdk on purpose until Android 17 behavior changes are reviewed.
        disable += "OldTargetApi"
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)

    testImplementation(libs.junit)
}
