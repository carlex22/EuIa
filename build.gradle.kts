// Top-level build file
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.google.gms.services) apply false
}

tasks.register("clean", Delete::class) { // Sintaxe Kotlin para registrar task
    delete(rootProject.buildDir)
}