import java.io.FileInputStream
import java.util.Properties

// Aplica os plugins necessários para este módulo, "pedindo" do cardápio definido na raiz.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose) // Aplica o plugin do Compose, garantindo a versão correta do compilador.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.gms.services)
    alias(libs.plugins.firebase.crashlytics)
}

android {
    namespace = "com.carlex.euia"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.carlex.euia"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.3"

        vectorDrawables {
            useSupportLibrary = true
        }

        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            FileInputStream(localPropertiesFile).use { inputStream ->
                localProperties.load(inputStream)
            }
        } else {
            println("WARNING: local.properties file not found.")
        }

        buildConfigField("String", "GEMINI_API_KEY", "\"${localProperties.getProperty("GEMINI_API_KEY", "")}\"")
        buildConfigField("String", "GROQ_API_KEY", "\"${localProperties.getProperty("GROQ_API_KEY", "")}\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // O bloco `composeOptions` foi removido. O plugin `kotlin-compose` agora gerencia a versão do compilador.
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "11"
}

dependencies {
    // --- Firebase BoM (Bill of Materials) ---
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.appcheck.playintegrity) // Corrigido: Dependência para App Check em produção
    debugImplementation(libs.firebase.appcheck.debug)      // Corrigido: Dependência para App Check em depuração
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.ai)
    implementation(libs.firebase.vertexai)
    implementation(libs.firebase.appdistribution.api)

    // --- Google Services ---
    implementation(libs.google.playServices.auth)
    implementation(libs.google.playServices.ads)
    
    implementation("com.google.android.gms:play-services-ads:24.4.0")


    // --- Google AI Generative ---
    implementation(libs.google.ai.generativeai)

    // Coil (Imagem)
    implementation(libs.coil.compose)
    
    // Billing
    implementation(libs.android.billing)

    // FFmpeg, Smart Exception e OpenCV
    implementation(libs.arthenica.smartException.java)
    implementation(libs.arthenica.smartException.common)
    implementation(libs.github.wseemann.ffmpegMediaRetriever)
    implementation(libs.openpnp.opencv)
    implementation(files("libs/ffmpeg-kit-full-gpl-6.0-2.LTS.aar")) // Arquivo local mantido como está.

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.playServices)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // Networking (OkHttp e Retrofit)
    implementation(libs.squareup.okhttp)
    implementation(libs.squareup.okhttp.sse)
    implementation(libs.squareup.okhttp.loggingInterceptor)
    implementation(libs.squareup.retrofit)
    implementation(libs.squareup.retrofit.converterGson)
    implementation(libs.jakewharton.retrofit.kotlinxSerializationConverter)

    // org.json
    implementation(libs.json.org)

    // --- Jetpack Compose e Relacionados ---
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.uiGraphics)
    implementation(libs.androidx.compose.uiToolingPreview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.materialIconsCore)
    implementation(libs.androidx.compose.materialIconsExtended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodelCompose)
    implementation(libs.androidx.lifecycle.process)

    // WorkManager
    implementation(libs.androidx.work.runtime)

    // DataStore
    implementation(libs.androidx.dataStore.preferences)

    // Core e Outros AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifInterface)

    // Debug/Test
    debugImplementation(libs.androidx.compose.uiTooling)
    debugImplementation(libs.androidx.compose.uiTestManifest)
}