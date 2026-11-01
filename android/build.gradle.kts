// Declaring the Kotlin plugins here (with a version) pins the Kotlin Gradle plugin that
// AGP 9's built-in Kotlin support uses for :app.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
