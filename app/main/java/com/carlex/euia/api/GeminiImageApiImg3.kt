// File: api/GeminiImageApiImg3.kt
package com.carlex.euia.api

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.carlex.euia.BuildConfig
import com.carlex.euia.data.AudioDataStoreManager // <<<--- ADICIONAR/GARANTIR IMPORTAÇÃO
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import com.carlex.euia.utils.BitmapUtils
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

// --- Data Classes (Modelos para Requisição e Resposta API Imagen 3) ---
// ... (Data classes permanecem as mesmas da versão anterior) ...
/**
 * Representa a requisição para a API Gemini Imagen 3.
 * @property instances Lista de instâncias de entrada, cada uma contendo um prompt.
 * @property parameters Parâmetros de configuração para a geração da imagem.
 */
@Serializable
data class GeminiImageRequest( // Nome mantido para consistência, mas representa uma requisição para Imagen 3
    val instances: List<InstanceBody>,
    val parameters: ImageParametersBody
)

/**
 * Corpo da instância para a requisição Imagen 3, contendo o prompt textual.
 * @property prompt O prompt de texto para a geração da imagem.
 */
@Serializable
data class InstanceBody(
    val prompt: String
)

/**
 * Parâmetros de configuração para a geração de imagem com Imagen 3, conforme estrutura original.
 * @property sampleCount Número de imagens a serem geradas por prompt.
 * @property aspectRatio Proporção da imagem a ser gerada (ex: "1:1", "9:16").
 */
@Serializable
data class ImageParametersBody(
    val sampleCount: Int = 1,       // Valor padrão original
    val aspectRatio: String = "9:16" // Valor padrão original
)

/**
 * Resposta da API Gemini Imagen 3.
 * @property predictions Lista de predições, cada uma contendo os bytes da imagem gerada.
 */
@Serializable
data class GeminiImageResponse( // Nome mantido para consistência
    val predictions: List<PredictionBody>
)

/**
 * Corpo da predição contendo a imagem gerada em Base64.
 * @property bytesBase64Encoded String contendo os bytes da imagem codificados em Base64.
 */
@Serializable
data class PredictionBody(
    @SerialName("bytesBase64Encoded") // SerialName para corresponder ao JSON da API Imagen
    val bytesBase64Encoded: String? = null
)

/**
 * Estrutura para um erro retornado pela API.
 * (Pode ser específica para Imagen 3, verificar documentação).
 */
@Serializable
data class GeminiApiErrorResponse( // Nome mantido
    val error: ApiErrorDetail
)

/**
 * Detalhes de um erro da API.
 */
@Serializable
data class ApiErrorDetail(
    val code: Int,
    val message: String,
    val status: String
)

// --- Interface do Serviço Retrofit ---
/**
 * Interface Retrofit para interagir com a API Gemini Imagen 3.
 */
internal interface GeminiApiServiceImg3 {
    @POST("v1beta/models/imagen-3.0-generate-002:predict")
    suspend fun generateImage(
        @Query("key") apiKey: String,
        @Body requestBody: GeminiImageRequest
    ): Response<GeminiImageResponse>
}
// --- FIM: Interface do Serviço Retrofit ---

// --- Objeto Principal da API ---
/**
 * Objeto singleton responsável por interagir com a API Gemini Imagen 3 para gerar imagens.
 * Lida com a construção da requisição, chamada à API, processamento da resposta
 * e salvamento da imagem gerada.
 */
object GeminiImageApiImg3 {

    private const val TAG = "GeminiImageApiImg3"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"
    private val apiKey = BuildConfig.GEMINI_API_KEY

    private const val DEFAULT_SAVE_WIDTH = 720
    private const val DEFAULT_SAVE_HEIGHT = 1280

    private const val API_SAMPLE_COUNT_INTERNAL: Int = 1
    private const val API_ASPECT_RATIO_INTERNAL: String = "9:16"

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Volatile
    private var apiServiceInstance: GeminiApiServiceImg3? = null

    private fun getService(): GeminiApiServiceImg3 {
        return apiServiceInstance ?: synchronized(this) {
            apiServiceInstance ?: buildRetrofitService().also { apiServiceInstance = it }
        }
    }

    private fun buildRetrofitService(): GeminiApiServiceImg3 {
        // ... (buildRetrofitService permanece o mesmo)
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.NONE
        }
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(jsonParser.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GeminiApiServiceImg3::class.java)
    }

    /**
     * Gera uma imagem usando a API Gemini Imagen 3 com base em um prompt textual.
     * O prompt principal é enriquecido com `videoExtrasAudio` do [AudioDataStoreManager].
     *
     * @param cena Prefixo para o nome do arquivo da imagem gerada.
     * @param prompt O prompt textual principal para guiar a geração da imagem.
     * @param context Contexto da aplicação.
     * @param imagensReferencia Lista de caminhos de arquivos de imagem de referência (atualmente não usada na requisição para Imagen 3 com esta estrutura).
     * @return Um [Result] contendo o caminho absoluto da imagem salva em caso de sucesso, ou uma exceção em caso de falha.
     */
    suspend fun gerarImagem(
        cena: String,
        prompt: String,
        context: Context,
        imagensReferencia: List<String> // Mantido na assinatura
    ): Result<String> {
        // Instanciar AudioDataStoreManager para obter videoExtrasAudio
        val audioDataStoreManager = AudioDataStoreManager(context)
        val videoExtrasFromDataStore = audioDataStoreManager.videoExtrasAudio.first()

        val promptLimpo = prompt.replace("\"", "")
        val promptFinalParaApi = buildString {
            append(promptLimpo)
            if (videoExtrasFromDataStore.isNotBlank()) {
                appendLine() // Adiciona uma nova linha para separar
                appendLine()
                append("--- INFORMAÇÕES MUITO IMPORTANTE DETALHES DE OBJETOS OU ROUPAS DA IMAGEN ---") // Separador claro
                appendLine()
                append(videoExtrasFromDataStore)
                appendLine()
                append("--- FIM DAS INFORMAÇÕES ADICIONAIS ---")
            }
        }

       // Log.i(TAG, "Gerando imagem (Imagen 3) para cena: '$cena' | Usando config interna: SampleCount=${API_SAMPLE_COUNT_INTERNAL}, AspectRatio=${API_ASPECT_RATIO_INTERNAL} | Prompt Final (com extras de áudio): '${promptFinalParaApi.take(15000)}...' | Refs (não usadas na API call): ${imagensReferencia.size}")

        return withContext(Dispatchers.IO) {
            var apiBitmap: Bitmap? = null
            try {
                val videoPreferencesManager = VideoPreferencesDataStoreManager(context)
                val projectDirName = videoPreferencesManager.videoProjectDir.first()
                val larguraSalvarPreferida = videoPreferencesManager.videoLargura.first()
                val alturaSalvarPreferida = videoPreferencesManager.videoAltura.first()

                val request = GeminiImageRequest(
                    instances = listOf(InstanceBody(prompt = promptFinalParaApi)), // Usa o promptFinalParaApi
                    parameters = ImageParametersBody(
                        sampleCount = API_SAMPLE_COUNT_INTERNAL,
                        aspectRatio = API_ASPECT_RATIO_INTERNAL
                    )
                )
                //Log.d(TAG, "Enviando requisição para Gemini API (Imagen 3) com config: ${request.parameters} e prompt (início): ${promptFinalParaApi.take(150)}")

                val response = getService().generateImage(apiKey, request)

                if (response.isSuccessful) {
                    val responseBody = response.body() // Pode ser null
                    if (responseBody == null) {
                        Log.e(TAG, "Resposta da API (Imagen 3) OK, mas corpo está nulo.")
                        return@withContext Result.failure(Exception("API (Imagen 3) retornou sucesso, mas com corpo de resposta nulo."))
                    }
                    val base64Image = responseBody?.predictions?.firstOrNull()?.bytesBase64Encoded

                    if (!base64Image.isNullOrEmpty()) {
                        Log.i(TAG, "Imagem recebida com sucesso da API (Imagen 3 Base64).")
                        apiBitmap = BitmapUtils.decodeBitmapFromBase64(
                            base64Image,
                            larguraSalvarPreferida ?: DEFAULT_SAVE_WIDTH,
                            alturaSalvarPreferida ?: DEFAULT_SAVE_HEIGHT
                        )
                        if (apiBitmap == null) {
                            return@withContext Result.failure(Exception("Falha ao decodificar bitmap da API (Imagen 3)."))
                        }

                        val caminhoImagem = saveGeneratedBitmap(
                            prefixo = cena,
                            apiBitmap = apiBitmap,
                            context = context,
                            projectDirName = projectDirName,
                            targetSaveWidth = larguraSalvarPreferida,
                            targetSaveHeight = alturaSalvarPreferida
                        )

                        if (caminhoImagem.isNotEmpty()) {
                            Log.i(TAG, "Imagem (Imagen 3) salva em: $caminhoImagem")
                            Result.success(caminhoImagem)
                        } else {
                            Log.e(TAG, "Falha ao salvar a imagem (Imagen 3) decodificada.")
                            Result.failure(Exception("Falha ao salvar a imagem (Imagen 3) gerada."))
                        }
                    } else {
                        Log.w(TAG, "Resposta da API (Imagen 3) OK, mas sem dados de imagem Base64. Predictions: ${responseBody?.predictions}")
                        Result.failure(Exception("API (Imagen 3) retornou sucesso, mas a imagem não foi encontrada na resposta."))
                    }
                } else {
                    val errorCode = response.code()
                    var errorBodyString: String? = null
                    try {
                        errorBodyString = response.errorBody()?.string()
                    } catch (e: Exception) {
                        Log.w(TAG, "Falha ao ler o corpo do erro (Imagen 3): ${e.message}")
                    }
                    Log.e(TAG, "Erro da API Gemini (Imagen 3): Código $errorCode. Corpo: ${errorBodyString?.take(500)}")
                    if (!errorBodyString.isNullOrBlank()) {
                        // Log.e(TAG, "Corpo do erro (Imagen 3): $errorBodyString") // Log já feito acima
                        try {
                            val apiError = jsonParser.decodeFromString<GeminiApiErrorResponse>(errorBodyString)
                            Result.failure(Exception("Erro da API Gemini (Imagen 3) ($errorCode): ${apiError.error.message} (Status: ${apiError.error.status})"))
                        } catch (e: Exception) {
                            Log.e(TAG, "Falha ao parsear corpo do erro JSON da API (Imagen 3): ", e)
                            Result.failure(Exception("Erro da API Gemini (Imagen 3) ($errorCode): ${errorBodyString.take(200)} (Não foi possível parsear detalhes do erro)"))
                        }
                    } else {
                        Result.failure(Exception("Erro da API Gemini (Imagen 3) ($errorCode) sem corpo de resposta."))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exceção durante a chamada à API Gemini (Imagen 3) ou processamento da resposta: ${e.message}", e)
                Result.failure(Exception("Falha na comunicação com a API Gemini (Imagen 3): ${e.message}", e))
            } finally {
                BitmapUtils.safeRecycle(apiBitmap, "gerarImagem_Imagen3 (apiBitmap final)")
            }
        }
    }

    /**
     * Salva o Bitmap gerado pela API Imagen 3 após redimensioná-lo para as dimensões de salvamento alvo.
     * O Bitmap da API original NÃO é reciclado por esta função.
     */
    private suspend fun saveGeneratedBitmap(
        prefixo: String,
        apiBitmap: Bitmap,
        context: Context,
        projectDirName: String,
        targetSaveWidth: Int?,
        targetSaveHeight: Int?
    ): String {
        // ... (saveGeneratedBitmap permanece o mesmo)
        var resizedBitmap: Bitmap? = null
        try {
            val finalSaveWidth = targetSaveWidth ?: DEFAULT_SAVE_WIDTH
            val finalSaveHeight = targetSaveHeight ?: DEFAULT_SAVE_HEIGHT

            Log.d(TAG, "Redimensionando bitmap da API (Imagen 3) de ${apiBitmap.width}x${apiBitmap.height} para ${finalSaveWidth}x${finalSaveHeight} (prefixo: $prefixo)")
            resizedBitmap = BitmapUtils.resizeWithTransparentBackground(apiBitmap, finalSaveWidth, finalSaveHeight)

            if (resizedBitmap == null) {
                Log.e(TAG, "Falha ao redimensionar bitmap da API (Imagen 3) para prefixo '$prefixo'.")
                return ""
            }

            val saveFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            val quality = if (saveFormat == Bitmap.CompressFormat.WEBP_LOSSLESS) 100 else 95

            return BitmapUtils.saveBitmapToFile(
                context = context,
                bitmap = resizedBitmap,
                projectDirName = projectDirName,
                subDir = "gemini_img3_generated_images",
                baseName = prefixo,
                format = saveFormat,
                quality = quality
            ) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar bitmap gerado (Imagen 3, prefixo '$prefixo'): ${e.message}", e)
            return ""
        } finally {
            BitmapUtils.safeRecycle(resizedBitmap, "saveGeneratedBitmap_Imagen3 (resized for $prefixo)")
        }
    }
}