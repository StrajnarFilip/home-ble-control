plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.strajnar.garage.wear"
    compileSdk = 37

    defaultConfig {
        // Same as the phone app, as Google asks for a phone and watch app that belong together.
        applicationId = "com.strajnar.garage"
        minSdk = 33 // Wear OS 4, the first version with Android 12's Bluetooth permissions.
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // For Robolectric, which also uses JDK internals to create file descriptors.
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.wear.compose.material3)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric) // The pulse goes through the real BLE client, over a fake GATT server.
}
