import java.util.Properties
import java.io.FileInputStream
import java.io.IOException
import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Aplica os plugins necessários para este módulo, "pedindo" do cardápio definido na raiz.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose) // Aplica o plugin do Compose, garantindo a versão correta do compilador.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.gms.services)
    alias(libs.plugins.firebase.crashlytics)
}

// Declaração e leitura das propriedades do local.properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")

if (localPropertiesFile.exists()) {
    try {
        FileInputStream(localPropertiesFile).use { inputStream ->
            localProperties.load(inputStream)
        }
    } catch (e: IOException) {
        println("ERRO ao carregar local.properties: ${e.message}")
    }
} else {
    // throw GradleException("O arquivo local.properties não foi encontrado na raiz do projeto. Certifique-se de que ele exista.")
    println("AVISO: O arquivo local.properties não foi encontrado na raiz do projeto. As chaves de API e configurações de assinatura podem não ser definidas.")
}


var StorePassword = "${localProperties.getProperty("STORE_PASSWORD")}"
var KeyPassword = "${localProperties.getProperty("KEY_PASSWORD")}"

// --- BLOCO PRINCIPAL DO ANDROID ---
// Este bloco usa a sintaxe do Kotlin DSL
android {
    namespace = "com.carlex.euia"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.carlex.euia"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.1.14"

        vectorDrawables {
            useSupportLibrary = true
        }        
        
        // Chaves de Segurança e IDs
        /* Se a chave não for encontrada, o valor padrão será "" (string vazia).
        buildConfigField("String", "CRYPTO_MASTER_KEY", "\"${localProperties.getProperty("CRYPTO_MASTER_KEY", "").replace("\"", "\\\"")}\"")
        buildConfigField("String", "ADMIN_USER_ID", "\"${localProperties.getProperty("ADMIN_USER_ID", "").replace("\"", "\\\"")}\"")
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"${localProperties.getProperty("GOOGLE_WEB_CLIENT_ID", "").replace("\"", "\\\"")}\"")

        // Nomes de Campos e Coleções do Armazenamento Remoto (Firebase Firestore)
        // Use defaults seguros que correspondam aos nomes reais no Firestore se a propriedade não existir.
        buildConfigField("String", "FIREBASE_COLLECTION_USERS", "\"${localProperties.getProperty("FIREBASE_COLLECTION_USERS", "users").replace("\"", "\\\"")}\"")
        buildConfigField("String", "FIREBASE_FIELD_CREDITS", "\"${localProperties.getProperty("FIREBASE_FIELD_CREDITS", "creditos_criptografados").replace("\"", "\\\"")}\"")
        buildConfigField("String", "FIREBASE_FIELD_VALIDATION_KEY", "\"${localProperties.getProperty("FIREBASE_FIELD_VALIDATION_KEY", "chave_validacao_firebase").replace("\"", "\\\"")}\"")
        buildConfigField("String", "FIREBASE_FIELD_IS_PREMIUM", "\"${localProperties.getProperty("FIREBASE_FIELD_IS_PREMIUM", "isPremium").replace("\"", "\\\"")}\"")
        buildConfigField("String", "FIREBASE_FIELD_CREDIT_EXPIRY", "\"${localProperties.getProperty("FIREBASE_FIELD_CREDIT_EXPIRY", "data_expira_credito").replace("\"", "\\\"")}\"")
        buildConfigField("String", "FIREBASE_FIELD_EXPERIENCE", "\"${localProperties.getProperty("FIREBASE_FIELD_EXPERIENCE", "experiencia_usuario").replace("\"", "\\\"")}\"")

        // Valores de Processo (Custos/Créditos)
        // Tente converter para Long, use default seguro (0L) se a propriedade não for encontrada ou não for um número válido.
        buildConfigField("long", "TASK_COST_CRE_FREE", "${localProperties.getProperty("CreFre")?.toLongOrNull() ?: 1000L}")
        buildConfigField("long", "TASK_COST_DEB_TEXT", "${localProperties.getProperty("DebText")?.toLongOrNull() ?: -1L}")
        buildConfigField("long", "TASK_COST_DEB_TEXT_PRO", "${localProperties.getProperty("DebTexPro")?.toLongOrNull() ?: -5L}")
        buildConfigField("long", "TASK_COST_DEB_AUD", "${localProperties.getProperty("DebAudSingle")?.toLongOrNull() ?: -10L}")
        buildConfigField("long", "TASK_COST_DEB_AUD_MULT", "${localProperties.getProperty("DebAudMult")?.toLongOrNull() ?: -20L}")
        buildConfigField("long", "TASK_COST_DEB_IMG", "${localProperties.getProperty("DebImg")?.toLongOrNull() ?: -10L}")
        buildConfigField("String", "YOUTUBE_UPLOAD_SCOPE", "\"${localProperties.getProperty("YOUTUBE_UPLOAD_SCOPE", "https://www.googleapis.com/auth/youtube.upload")}\"")

        // Configurações de API e Serviços
        // Adicione outras chaves de API ou URLs base aqui, lendo de local.properties.
        // Exemplo para chaves que podem estar no local.properties (se não vierem do Firestore ou outro lugar)
        buildConfigField("String", "GEMINI_API_KEY", "\"${localProperties.getProperty("GEMINI_API_KEY", "").replace("\"", "\\\"")}\"")
        buildConfigField("String", "GROQ_API_KEY", "\"${localProperties.getProperty("GROQ_API_KEY", "").replace("\"", "\\\"")}\"")

        // Configurações Específicas de Serviços (URLs, Modelos, Parâmetros, Retries, Timeouts)
        // Use defaults seguros que correspondam aos seus valores de fallback no código ou API.

        // Serviço de Troca de Figurino (Virtual Try-On)
        buildConfigField("String", "TRYON_BASE_URL", "\"${localProperties.getProperty("TRYON_BASE_URL", "https://default.url").replace("\"", "\\\"")}\"")
        buildConfigField("long", "TRYON_SSE_TIMEOUT_MS", "${localProperties.getProperty("TRYON_SSE_TIMEOUT_MS")?.toLongOrNull() ?: 300000L}")
        buildConfigField("long", "TRYON_CONNECT_TIMEOUT_S", "${localProperties.getProperty("TRYON_CONNECT_TIMEOUT_S")?.toLongOrNull() ?: 30L}")
        buildConfigField("long", "TRYON_READ_TIMEOUT_S", "${localProperties.getProperty("TRYON_READ_TIMEOUT_S")?.toLongOrNull() ?: 300L}")
        buildConfigField("long", "TRYON_WRITE_TIMEOUT_S", "${localProperties.getProperty("TRYON_WRITE_TIMEOUT_S")?.toLongOrNull() ?: 30L}")
        buildConfigField("String", "TRYON_TIPO_CHAVE", "\"${localProperties.getProperty("TRYON_TIPO_CHAVE", "tryon").replace("\"", "\\\"")}\"")
        buildConfigField("int", "TRYON_MAX_RETRIES", "${localProperties.getProperty("TRYON_MAX_RETRIES")?.toIntOrNull() ?: 3}")
        buildConfigField("int", "TRYON_GRADIO_FN_INDEX", "${localProperties.getProperty("TRYON_GRADIO_FN_INDEX")?.toIntOrNull() ?: 2}")
        buildConfigField("int", "TRYON_DEFAULT_WIDTH", "${localProperties.getProperty("TRYON_DEFAULT_WIDTH")?.toIntOrNull() ?: 800}")
        buildConfigField("int", "TRYON_DEFAULT_HEIGHT", "${localProperties.getProperty("TRYON_DEFAULT_HEIGHT")?.toIntOrNull() ?: 1600}")
        buildConfigField("int", "TRYON_WEBP_QUALITY", "${localProperties.getProperty("TRYON_WEBP_QUALITY")?.toIntOrNull() ?: 60}")
        buildConfigField("String", "TRYON_COMPRESS_FORMAT", "\"${localProperties.getProperty("TRYON_COMPRESS_FORMAT", "WEBP").replace("\"", "\\\"")}\"")

        // API Gemini Video (Veo)
        buildConfigField("String", "VEO_BASE_URL", "\"${localProperties.getProperty("VEO_BASE_URL", "https://default.api.url").replace("\"", "\\\"")}\"")
        buildConfigField("String", "VEO_MODEL_ID", "\"${localProperties.getProperty("VEO_MODEL_ID", "veo-default-model").replace("\"", "\\\"")}\"")
        buildConfigField("String", "VEO_DEFAULT_PERSON_POLICY", "\"${localProperties.getProperty("VEO_DEFAULT_PERSON_POLICY", "dont_allow").replace("\"", "\\\"")}\"")
        buildConfigField("String", "VEO_DEFAULT_ASPECT_RATIO", "\"${localProperties.getProperty("VEO_DEFAULT_ASPECT_RATIO", "9:16").replace("\"", "\\\"")}\"")
        buildConfigField("int", "VEO_DEFAULT_SAMPLE_COUNT", "${localProperties.getProperty("VEO_DEFAULT_SAMPLE_COUNT")?.toIntOrNull() ?: 1}")
        buildConfigField("int", "VEO_DEFAULT_DURATION_SECONDS", "${localProperties.getProperty("VEO_DEFAULT_DURATION_SECONDS")?.toIntOrNull() ?: 5}")
        buildConfigField("long", "VEO_POLLING_INTERVAL_MS", "${localProperties.getProperty("VEO_POLLING_INTERVAL_MS")?.toLongOrNull() ?: 10000L}")
        buildConfigField("int", "VEO_MAX_POLLING_ATTEMPTS", "${localProperties.getProperty("VEO_MAX_POLLING_ATTEMPTS")?.toIntOrNull() ?: 60}")
        buildConfigField("int", "VEO_MAX_CONSECUTIVE_FAILURES", "${localProperties.getProperty("VEO_MAX_CONSECUTIVE_FAILURES")?.toIntOrNull() ?: 5}")

        // Seus Endpoints de Cloud Functions (para orquestração da Vertex AI/Veo)
        buildConfigField("String", "CLOUD_FUNCTIONS_BASE_URL", "\"${localProperties.getProperty("CLOUD_FUNCTIONS_BASE_URL", "https://default.cloudfunctions.url").replace("\"", "\\\"")}\"")

        // API Gemini Imagen Direta (via Retrofit, se usar esta implementação)
        buildConfigField("String", "DIRECT_IMAGEN_BASE_URL", "\"${localProperties.getProperty("DIRECT_IMAGEN_BASE_URL", "https://default.api.url").replace("\"", "\\\"")}\"")
        buildConfigField("String", "DIRECT_IMAGEN_MODEL_NAME", "\"${localProperties.getProperty("DIRECT_IMAGEN_MODEL_NAME", "imagen-default-model").replace("\"", "\\\"")}\"")
        buildConfigField("String", "DIRECT_IMAGEN_TIPO_CHAVE", "\"${localProperties.getProperty("DIRECT_IMAGEN_TIPO_CHAVE", "img").replace("\"", "\\\"")}\"")
        buildConfigField("int", "DIRECT_IMAGEN_MAX_RETRIES", "${localProperties.getProperty("DIRECT_IMAGEN_MAX_RETRIES")?.toIntOrNull() ?: 10}")

        // Parâmetros Comuns para APIs de Imagem (Direct e Img3)
        buildConfigField("float", "IMG_API_TEMPERATURE", "${localProperties.getProperty("IMG_API_TEMPERATURE")?.toFloatOrNull() ?: 0.4f}")
        buildConfigField("int", "IMG_API_TOP_K", "${localProperties.getProperty("IMG_API_TOP_K")?.toIntOrNull() ?: 32}")
        buildConfigField("float", "IMG_API_TOP_P", "${localProperties.getProperty("IMG_API_TOP_P")?.toFloatOrNull() ?: 0.95f}")
        buildConfigField("int", "IMG_API_CANDIDATE_COUNT", "${localProperties.getProperty("IMG_API_CANDIDATE_COUNT")?.toIntOrNull() ?: 1}")


        // API Gemini Text/Vision Standard (via Firebase SDK)
        // STANDARD_TEXT_BASE_URL é opcional para SDK, pode ser informativa
        buildConfigField("String", "STANDARD_TEXT_MODEL_NAME", "\"${localProperties.getProperty("STANDARD_TEXT_MODEL_NAME", "gemini-default-standard").replace("\"", "\\\"")}\"")
        buildConfigField("String", "STANDARD_TEXT_TIPO_CHAVE", "\"${localProperties.getProperty("STANDARD_TEXT_TIPO_CHAVE", "text").replace("\"", "\\\"")}\"")
        buildConfigField("int", "STANDARD_TEXT_MAX_RETRIES", "${localProperties.getProperty("STANDARD_TEXT_MAX_RETRIES")?.toIntOrNull() ?: 10}")
        buildConfigField("float", "STANDARD_TEXT_TEMPERATURE", "${localProperties.getProperty("STANDARD_TEXT_TEMPERATURE")?.toFloatOrNull() ?: 0.7f}")
        buildConfigField("int", "STANDARD_TEXT_TOP_K", "${localProperties.getProperty("STANDARD_TEXT_TOP_K")?.toIntOrNull() ?: 32}")
        buildConfigField("float", "STANDARD_TEXT_TOP_P", "${localProperties.getProperty("STANDARD_TEXT_TOP_P")?.toFloatOrNull() ?: 0.8f}")
        buildConfigField("long", "STANDARD_TEXT_TIMEOUT_SECONDS", "${localProperties.getProperty("STANDARD_TEXT_TIMEOUT_SECONDS")?.toLongOrNull() ?: 120L}")


        // API Gemini Text/Vision Pro (via Firebase SDK)
        // PRO_TEXT_BASE_URL é opcional para SDK, pode ser informativa
        buildConfigField("String", "PRO_TEXT_MODEL_NAME", "\"${localProperties.getProperty("PRO_TEXT_MODEL_NAME", "gemini-default-pro").replace("\"", "\\\"")}\"")
        buildConfigField("String", "PRO_TEXT_TIPO_CHAVE", "\"${localProperties.getProperty("PRO_TEXT_TIPO_CHAVE", "text").replace("\"", "\\\"")}\"")
        buildConfigField("int", "PRO_TEXT_MAX_RETRIES", "${localProperties.getProperty("PRO_TEXT_MAX_RETRIES")?.toIntOrNull() ?: 10}")
        buildConfigField("float", "PRO_TEXT_TEMPERATURE", "${localProperties.getProperty("PRO_TEXT_TEMPERATURE")?.toFloatOrNull() ?: 2.0f}") // Mais criativo
        buildConfigField("int", "PRO_TEXT_TOP_K", "${localProperties.getProperty("PRO_TEXT_TOP_K")?.toIntOrNull() ?: 32}")
        buildConfigField("float", "PRO_TEXT_TOP_P", "${localProperties.getProperty("PRO_TEXT_TOP_P")?.toFloatOrNull() ?: 0.95f}")
        buildConfigField("long", "PRO_TEXT_TIMEOUT_SECONDS", "${localProperties.getProperty("PRO_TEXT_TIMEOUT_SECONDS")?.toLongOrNull() ?: 120L}")

        // API Gemini Multi-Speaker Audio (via OkHttp direto)
        buildConfigField("String", "MULTI_AUDIO_BASE_URL", "\"${localProperties.getProperty("MULTI_AUDIO_BASE_URL", "https://default.api.url").replace("\"", "\\\"")}\"")
        buildConfigField("String", "MULTI_AUDIO_MODEL_ID", "\"${localProperties.getProperty("MULTI_AUDIO_MODEL_ID", "gemini-default-tts-multi").replace("\"", "\\\"")}\"")
        buildConfigField("String", "MULTI_AUDIO_TIPO_CHAVE", "\"${localProperties.getProperty("MULTI_AUDIO_TIPO_CHAVE", "audio").replace("\"", "\\\"")}\"")
        buildConfigField("int", "MULTI_AUDIO_MAX_RETRIES", "${localProperties.getProperty("MULTI_AUDIO_MAX_RETRIES")?.toIntOrNull() ?: 10}")
        buildConfigField("float", "MULTI_AUDIO_TEMPERATURE", "${localProperties.getProperty("MULTI_AUDIO_TEMPERATURE")?.toFloatOrNull() ?: 2.0f}") // Temperatura

        // Seu Serviço de Fila (se usar backend customizado)
        buildConfigField("String", "QUEUE_BASE_URL", "\"${localProperties.getProperty("QUEUE_BASE_URL", "http://default.queue.url").replace("\"", "\\\"")}\"")  */
        
        packagingOptions {
            exclude("META-INF/DEPENDENCIES")
        }
    }

    signingConfigs {
        if (StorePassword == null || KeyPassword == null) {
            // throw GradleException("As propriedades STORE_PASSWORD e KEY_PASSWORD são obrigatórias em local.properties para o build de assinatura.")
            println("AVISO: STORE_PASSWORD ou KEY_PASSWORD não encontrados em local.properties. O build de assinatura pode falhar.")
        }
        
        println("STORE_PASSWORD $StorePassword KEY_PASSWORD $KeyPassword")

        create("release") {
            storeFile = file("/data/user/0/com.itsaky.androidide/files/home/euia2/app/signing-key.jks")
            storePassword = StorePassword
            keyAlias = "carlex"
            keyPassword = KeyPassword
        }

        getByName("debug") {
            storeFile = file("/data/user/0/com.itsaky.androidide/files/home/euia2/app/signing-key.jks")
            storePassword = StorePassword
            keyPassword = KeyPassword
            keyAlias = "carlex"
        }
    }

    buildTypes {
        release {
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    tasks.withType<KotlinCompile>().configureEach {
        kotlinOptions.jvmTarget = "11"
    }
}
// --- FIM DO BLOCO ANDROID ---


// --- BLOCO DE DEPENDÊNCIAS ---
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
    implementation("com.google.apis:google-api-services-youtube:v3-rev222-1.25.0")
    implementation("com.google.android.gms:play-services-auth:21.0.0")
    implementation("com.google.firebase:firebase-auth-ktx:22.3.1")
    implementation("androidx.startup:startup-runtime:1.1.1")

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