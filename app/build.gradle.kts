import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// API key lives in local.properties (gitignored, never committed) and is
// baked into a BuildConfig field at compile time — never hardcoded in
// source, never logged. Same pattern used by the sibling
// visual-impaired-assistant project.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val openAiApiKey: String = localProperties.getProperty("OPENAI_API_KEY", "")

android {
    namespace = "ai.pivotstudio.voicemode.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.pivotstudio.voicemode.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "OPENAI_API_KEY", "\"$openAiApiKey\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")

    // OkHttp — only for its WebSocket client, used to hold the persistent
    // connection to OpenAI's Realtime API (wss://api.openai.com/v1/realtime).
    // A hand-rolled WebSocket client (handshake, frame masking/parsing,
    // ping/pong, fragmentation) is enough surface area for real bugs that
    // this well-tested, widely-used library is worth the one new
    // dependency, unlike the simple one-shot HTTP POST the cascade
    // version used (plain HttpURLConnection sufficed there).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines — Channel-based audio capture/playback pipeline, same
    // pattern as visual-impaired-assistant's AudioCapture.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
