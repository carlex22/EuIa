// Top-level build file
plugins {
    // Declara os plugins disponíveis para os módulos, mas não os aplica aqui (apply false).
    // Isso serve como um "cardápio" para os módulos do projeto.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
    alias(libs.plugins.google.gms.services) apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}