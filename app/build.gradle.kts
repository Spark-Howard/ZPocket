plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.zpocket.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zpocket.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.5"
    }

    signingConfigs {
        create("release") {
            storeFile = file(rootProject.property("ZPOCKET_STORE_FILE") as String)
            storePassword = rootProject.property("ZPOCKET_STORE_PASSWORD") as String
            keyAlias = rootProject.property("ZPOCKET_KEY_ALIAS") as String
            keyPassword = rootProject.property("ZPOCKET_KEY_PASSWORD") as String
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
}
