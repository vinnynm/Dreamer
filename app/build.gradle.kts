plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

ksp {
    arg("room.generateKotlin", "true")
}

android {
    namespace = "com.enigma.dreamer"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.enigma.dreamer"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation("io.mockk:mockk:1.13.10")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation("com.github.vinnynm:lyric-baker:direwolf")

    // ── Kotlin & Coroutines ──────────────────────────────────────────────────
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")




    implementation ("androidx.compose.material:material-icons-extended-android:1.7.8")
    implementation ("androidx.compose.animation:animation:1.11.1")


    // ── Media / MediaSession ─────────────────────────────────────────────────
    implementation ("androidx.media3:media3-exoplayer-smoothstreaming:1.10.0")
    implementation ("androidx.media3:media3-ui:1.10.0")
    implementation ("androidx.media3:media3-common-ktx:1.10.0")
    implementation ("androidx.media3:media3-session:1.10.0")
    implementation ("androidx.media3:media3-exoplayer:1.10.0")



    // ── Room (KSP — no KAPT) ─────────────────────────────────────────────────
    val roomVersion = "2.7.0-alpha11"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.room:room-common:$roomVersion")

    // ── Image loading ─────────────────────────────────────────────────────────
    implementation("io.coil-kt:coil-compose:2.5.0")

    // ── Navigation ────────────────────────────────────────────────────────────
    implementation(libs.androidx.navigation.compose)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // ── Reorderable List ──────────────────────────────────────────────────────
    implementation("sh.calvin.reorderable:reorderable:3.1.0")
    implementation(kotlin("test"))
}