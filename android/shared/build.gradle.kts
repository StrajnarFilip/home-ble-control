// The BLE client, wire protocol and pairing code, used by both the phone and the watch app.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.strajnar.garage.shared"
    compileSdk = 37

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Pairing.parse calls BluetoothAdapter, which unit tests only have as a stub.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.json) // android.jar's org.json is a stub in unit tests.
}
