plugins {
    id("com.android.application") version "8.4.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

android {
    namespace = "com.floradex.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.floradex.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        val apiKey = System.getenv("GEMINI_API_KEY") ?: ""
        buildConfigField("String", "GEMINI_API_KEY", "\"$apiKey\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")
}
