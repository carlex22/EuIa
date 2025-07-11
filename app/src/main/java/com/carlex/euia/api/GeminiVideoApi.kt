//File: euia/api/GeminiVideoApi.kt
package com.carlex.euia.api

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.carlex.euia.BuildConfig
import com.carlex.euia.data.GeminiApiKeyDataStoreManager
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import com.carlex.euia.utils.BitmapUtils
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext // Para isActive

// --- Data Classes ---

@Serializable
data class VideoGeminiRequest(
    val instances: List<VideoInstanceBody>,
    val parameters: VideoParametersBody
)

@Serializable
data class VideoInstanceBody(
    val prompt: String,
    val image: VideoImageInput? = null
)

@Serializable
data class VideoImageInput(
    @SerialName("bytesBase64Encoded")
    val bytesBase64Encoded: String,
    @SerialName("mimeType")
    val mimeType: String
)

@Serializable
data class VideoParametersBody(
    @SerialName("personGeneration")
    val personGeneration: String?,
    val aspectRatio: String,
    val sampleCount: Int,
    val durationSeconds: Int
)

@Serializable
data class LongRunningOperationStartResponse(
    val name: String,
    val metadata: OperationMetadata? = null,
    val done: Boolean? = false
)

@Serializable
data class OperationMetadata(
    @SerialName("@type")
    val type: String? = null
)

@Serializable
data class OperationStatusResponse(
    val name: String,
    val done: Boolean? = false,
    val response: VideoGenerationActualResponse? = null,
    val error: ApiErrorDetail? = null // Usando ApiErrorDetail de GeminiImageApiImg3.kt (assumindo que são compatíveis ou definir um local)
)

@Serializable
data class VideoGenerationActualResponse(
    @SerialName("generateVideoResponse")
    val generateVideoResponse: GeneratedVideoSamplesContainer? = null
)

@Serializable
data class GeneratedVideoSamplesContainer(
    val generatedSamples: List<GeneratedVideoSample>? = null
)

@Serializable
data class GeneratedVideoSample(
    val video: VideoUriContainer? = null
)

@Serializable
data class VideoUriContainer(
    val uri: String? = null
)


// --- Interface do Serviço Retrofit ---
internal interface VideoGeminiApiService {
    @POST("v1beta/models/{modelId}:predictLongRunning")
    suspend fun startVideoGeneration(
        @Path("modelId") modelId: String,
        @Query("key") apiKey: String,
        @Body requestBody: VideoGeminiRequest
    ): Response<LongRunningOperationStartResponse>

    @GET("v1beta/{operationName}")
    suspend fun checkOperationStatus(
        @Path(value = "operationName", encoded = true) operationName: String,
        @Query("key") apiKey: String
    ): Response<OperationStatusResponse>

    @GET
    suspend fun downloadVideo(
        @Url videoUrlWithKey: String
    ): Response<ResponseBody>
}

// --- Objeto Principal da API ---
object GeminiVideoApi {
    private const val TAG = "GeminiVideoApi"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"
    // private val apiKey = BuildConfig.GEMINI_API_KEY
    private const val MODEL_ID = "veo-2.0-generate-001" // <<--- VERIFICAR SE ESTE É O MODELO CORRETO

    private const val DEFAULT_PERSON_GENERATION_POLICY = "dont_allow"
    private const val DEFAULT_ASPECT_RATIO = "9:16" // Alterado para 1:1 como padrão, pode ser ajustado
    private const val DEFAULT_SAMPLE_COUNT = 1
    private const val DEFAULT_DURATION_SECONDS = 5

    private const val POLLING_INTERVAL_MS = 10000L
    private const val MAX_POLLING_ATTEMPTS = 60
    private const val MAX_CONSECUTIVE_POLLING_FAILURES = 5

    private const val REF_IMG_TARGET_WIDTH = 720
    private const val REF_IMG_TARGET_HEIGHT = 1280

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false // Mantido como false para não enviar valores padrão para a API se não forem explicitamente definidos
    }

    @Volatile
    private var apiServiceInstance: VideoGeminiApiService? = null

    private fun getService(): VideoGeminiApiService {
        return apiServiceInstance ?: synchronized(this) {
            apiServiceInstance ?: buildRetrofitService().also { apiServiceInstance = it }
        }
    }

    private fun buildRetrofitService(): VideoGeminiApiService {
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
            .create(VideoGeminiApiService::class.java)
    }

    suspend fun gerarVideo(
        cena: String, // Usado para nomear o arquivo de saída e para logging
        prompt: String,
        context: Context,
        imagemReferenciaPath: String? = null
    ): Result<List<String>> {
    
    
        val apiKeyDataStore = GeminiApiKeyDataStoreManager(context)
        val apiKey = apiKeyDataStore.userGeminiApiKey.first()

        
        // --- INÍCIO DOS LOGS ADICIONAIS ---
        Log.i(TAG, "--- INÍCIO: GeminiVideoApi.gerarVideo ---")
        Log.d(TAG, "Parâmetros de entrada:")
        Log.d(TAG, "  Cena (prefixo arquivo): $cena")
        Log.d(TAG, "  Prompt (primeiros 100 chars): ${prompt.take(100)}...")
        Log.d(TAG, "  Contexto: ${context.javaClass.simpleName}")
        Log.d(TAG, "  Imagem de Referência Path: ${imagemReferenciaPath ?: "Nenhuma"}")
        Log.d(TAG, "Valores Padrão que serão usados para a API:")
        Log.d(TAG, "  Política de Geração de Pessoa: $DEFAULT_PERSON_GENERATION_POLICY")
        Log.d(TAG, "  Proporção do Aspecto: $DEFAULT_ASPECT_RATIO")
        Log.d(TAG, "  Contagem de Amostras: $DEFAULT_SAMPLE_COUNT")
        Log.d(TAG, "  Duração em Segundos: $DEFAULT_DURATION_SECONDS")
        // --- FIM DOS LOGS ADICIONAIS ---

        return withContext(Dispatchers.IO) {
            try {
                val videoPreferencesManager = VideoPreferencesDataStoreManager(context)
                val projectDirName = videoPreferencesManager.videoProjectDir.first()

                var videoImageInput: VideoImageInput? = null
                if (imagemReferenciaPath != null) {
                    val imageFile = File(imagemReferenciaPath)
                    if (imageFile.exists()) {
                        var imageBitmap: Bitmap? = null
                        try {
                            imageBitmap = BitmapUtils.decodeSampledBitmapFromUri(
                                context, Uri.fromFile(imageFile), REF_IMG_TARGET_WIDTH, REF_IMG_TARGET_HEIGHT
                            )
                            if (imageBitmap != null) {
                                val outputStream = ByteArrayOutputStream()
                                imageBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 85, outputStream) // Comprime como webp
                                val imageBytes = outputStream.toByteArray()
                                val encodedImage = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                                videoImageInput = VideoImageInput(bytesBase64Encoded = encodedImage, mimeType = "image/webp") // Define mimeType como JPEG
                                Log.d(TAG, "Imagem de referência processada (webp): $imagemReferenciaPath")
                            } else {
                                Log.w(TAG, "Falha ao decodificar imagem de referência: $imagemReferenciaPath")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Erro ao processar arquivo de imagem de referência: $imagemReferenciaPath", e)
                        } finally {
                            BitmapUtils.safeRecycle(imageBitmap, "GeminiVideoApi_RefImage")
                        }
                    } else {
                        Log.w(TAG, "Arquivo de imagem de referência não encontrado: $imagemReferenciaPath")
                    }
                }

                val promptLimpo = prompt.replace("\"", "")
                val requestInstance = VideoInstanceBody(prompt = promptLimpo, image = videoImageInput)
                Log.d(TAG, "Instância da requisição (VideoInstanceBody):")
                Log.d(TAG, "  Prompt (limpo): ${requestInstance.prompt.take(100)}...")
                Log.d(TAG, "  Imagem Base64 (primeiros 30 chars se presente): ${requestInstance.image?.bytesBase64Encoded?.take(30)}")
                Log.d(TAG, "  MIME Type da Imagem: ${requestInstance.image?.mimeType}")


                val requestParameters = VideoParametersBody(
                    personGeneration = DEFAULT_PERSON_GENERATION_POLICY,
                    aspectRatio = DEFAULT_ASPECT_RATIO,
                    sampleCount = DEFAULT_SAMPLE_COUNT,
                    durationSeconds = DEFAULT_DURATION_SECONDS
                )
                val request = VideoGeminiRequest(instances = listOf(requestInstance), parameters = requestParameters)
                Log.d(TAG, "Parâmetros da requisição (VideoParametersBody): $requestParameters")
                Log.i(TAG, "Enviando request à API Gemini Video (modelo: $MODEL_ID)...")


                val startResponseCall = getService().startVideoGeneration(MODEL_ID, apiKey, request)
                if (!startResponseCall.isSuccessful || startResponseCall.body() == null) {
                    val errorBody = startResponseCall.errorBody()?.string() ?: "Corpo do erro não disponível"
                    Log.e(TAG, "Falha ao iniciar a geração do vídeo. Código: ${startResponseCall.code()}. Erro: $errorBody")
                    return@withContext Result.failure(Exception("Falha ao iniciar a geração do vídeo (HTTP ${startResponseCall.code()}): ${errorBody.take(500)}"))
                }

                val operationInitialBody = startResponseCall.body()!!
                val operationName = operationInitialBody.name
                if (operationName.isBlank()) {
                    Log.e(TAG, "Nome da operação retornado vazio.")
                    return@withContext Result.failure(Exception("Nome da operação retornado vazio pela API."))
                }
                Log.i(TAG, "Operação de geração de vídeo iniciada: $operationName. Resposta inicial 'done': ${operationInitialBody.done}")

                var attempts = 0
                var consecutivePollingFailures = 0
                var operationEffectivelyDone = false
                var finalOperationStatus: OperationStatusResponse? = null

                while (!operationEffectivelyDone && attempts < MAX_POLLING_ATTEMPTS) {
                    attempts++
                    Log.d(TAG, "Verificando status da operação ($operationName), tentativa ${attempts}/${MAX_POLLING_ATTEMPTS}...")
                    delay(POLLING_INTERVAL_MS)
                    
                    if (!coroutineContext.isActive) { // Verifica se a coroutine foi cancelada
                        Log.w(TAG, "Geração de vídeo cancelada durante o polling para $operationName.")
                        throw kotlinx.coroutines.CancellationException("Geração de vídeo cancelada durante polling.")
                    }

                    val statusResponseCall = getService().checkOperationStatus(operationName, apiKey)
                    if (!statusResponseCall.isSuccessful || statusResponseCall.body() == null) {
                        consecutivePollingFailures++
                        val errorBody = statusResponseCall.errorBody()?.string() ?: "Corpo do erro não disponível"
                        Log.w(TAG, "Falha ao verificar status da operação (HTTP ${statusResponseCall.code()}): ${errorBody.take(500)}. Tentativa $consecutivePollingFailures/$MAX_CONSECUTIVE_POLLING_FAILURES.")
                        
                        if (consecutivePollingFailures >= MAX_CONSECUTIVE_POLLING_FAILURES) {
                            Log.e(TAG, "Muitas falhas consecutivas ($consecutivePollingFailures) ao verificar status da operação $operationName. Abortando.")
                            return@withContext Result.failure(Exception("Falha ao verificar status da operação após $consecutivePollingFailures tentativas: ${errorBody.take(500)}"))
                        }
                        continue // Tenta novamente após o delay
                    } else { 
                        consecutivePollingFailures = 0 // Reseta contador de falhas se a chamada for bem-sucedida
                    }

                    finalOperationStatus = statusResponseCall.body()!!
                    operationEffectivelyDone = finalOperationStatus.done ?: false
                    Log.d(TAG, "Status da operação: done API = ${finalOperationStatus.done} (interpretado como $operationEffectivelyDone)")

                    if (operationEffectivelyDone) break
                }

                if (!operationEffectivelyDone) {
                    Log.e(TAG, "Timeout: Operação de geração de vídeo não concluída após $attempts tentativas.")
                    return@withContext Result.failure(Exception("Timeout: Geração de vídeo não concluída a tempo ($MAX_POLLING_ATTEMPTS tentativas)."))
                }

                val errorDetail = finalOperationStatus?.error
                if (errorDetail != null) {
                    val errorMsg = "API retornou erro na operação: ${errorDetail.message ?: "Sem mensagem"} (Código: ${errorDetail.code ?: "N/A"}, Status: ${errorDetail.status ?: "N/A"})"
                    Log.e(TAG, errorMsg)
                    return@withContext Result.failure(Exception(errorMsg))
                }

                val videoUris = finalOperationStatus?.response?.generateVideoResponse?.generatedSamples
                    ?.mapNotNull { it.video?.uri }
                    ?: emptyList()

                if (videoUris.isEmpty()) {
                    Log.e(TAG, "Operação concluída, mas nenhuma URI de vídeo encontrada na resposta.")
                    Log.d(TAG, "Resposta completa do status (sem URIs): $finalOperationStatus")
                    return@withContext Result.failure(Exception("Nenhuma URI de vídeo encontrada após a conclusão da operação."))
                }

                Log.i(TAG, "Vídeo(s) gerado(s) com sucesso. URIs: $videoUris")

                val savedVideoPaths = mutableListOf<String>()
                videoUris.forEachIndexed { index, videoUri ->
                    if (!coroutineContext.isActive) { // Verifica cancelamento
                        Log.w(TAG, "Geração de vídeo cancelada antes do download do vídeo $index.")
                        throw kotlinx.coroutines.CancellationException("Geração de vídeo cancelada antes do download.")
                    }
                    val videoUrlWithKey = if (videoUri.contains("?")) "$videoUri&key=$apiKey" else "$videoUri?key=$apiKey"
                    val videoFileName = "${cena}_video_${System.currentTimeMillis()}_$index.mp4"

                    Log.d(TAG, "Iniciando download do vídeo $index: $videoUrlWithKey")
                    try {
                        val downloadResponse = getService().downloadVideo(videoUrlWithKey)
                        if (downloadResponse.isSuccessful && downloadResponse.body() != null) {
                            val savedPath = saveVideoToFile(
                                context, downloadResponse.body()!!, projectDirName, videoFileName
                            )
                            if (savedPath != null) {
                                Log.i(TAG, "Vídeo $index salvo em: $savedPath")
                                savedVideoPaths.add(savedPath)
                            } else {
                                Log.e(TAG, "Falha ao salvar o vídeo $index localmente.")
                            }
                        } else {
                            val errorBody = downloadResponse.errorBody()?.string() ?: "N/A"
                            Log.e(TAG, "Falha no download do vídeo $index (HTTP ${downloadResponse.code()}): ${errorBody.take(500)}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Erro durante o download do vídeo $index: ${e.message}", e)
                    }
                }

                if (savedVideoPaths.isEmpty()) {
                    return@withContext Result.failure(Exception("Falha ao baixar ou salvar qualquer vídeo gerado."))
                }
                Log.i(TAG, "--- FIM SUCESSO: GeminiVideoApi.gerarVideo ---")
                Result.success(savedVideoPaths)

            } catch (e: kotlinx.serialization.SerializationException) {
                Log.e(TAG, "Erro de serialização/desserialização na API de vídeo: ${e.message}", e)
                Log.i(TAG, "--- FIM ERRO SERIALIZAÇÃO: GeminiVideoApi.gerarVideo ---")
                Result.failure(Exception("Erro ao processar dados da API (serialização): ${e.localizedMessage}", e))
            }
            catch (e: kotlinx.coroutines.CancellationException) {
                Log.w(TAG, "Geração de vídeo explicitamente cancelada: ${e.message}", e)
                 Log.i(TAG, "--- FIM CANCELAMENTO: GeminiVideoApi.gerarVideo ---")
                Result.failure(e) // Propaga o cancelamento
            }
            catch (e: Exception) {
                Log.e(TAG, "Exceção não tratada durante a geração do vídeo para cena '$cena': ${e.message}", e)
                Log.i(TAG, "--- FIM EXCEÇÃO: GeminiVideoApi.gerarVideo ---")
                Result.failure(e)
            }
        }
    }

    private suspend fun saveVideoToFile(
        context: Context,
        body: ResponseBody,
        projectDirName: String,
        fileName: String
    ): String? {
        return withContext(Dispatchers.IO) {
            val targetDir = if (projectDirName.isNotBlank()) {
                File(context.getExternalFilesDir(null), projectDirName)
            } else {
                File(context.getExternalFilesDir(null), "gemini_generated_videos")
            }

            if (!targetDir.exists()) {
                if (!targetDir.mkdirs()) {
                    Log.e(TAG, "Falha ao criar diretório de destino: ${targetDir.absolutePath}")
                    return@withContext null
                }
            }
            val videoFile = File(targetDir, fileName)

            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null
            try {
                inputStream = body.byteStream()
                outputStream = FileOutputStream(videoFile)
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (!coroutineContext.isActive) { // Verifica cancelamento durante a escrita
                        Log.w(TAG, "Download do vídeo cancelado durante a escrita do arquivo.")
                        videoFile.delete() // Limpa arquivo parcial
                        throw kotlinx.coroutines.CancellationException("Download do vídeo cancelado.")
                    }
                    outputStream.write(buffer, 0, bytesRead)
                }
                outputStream.flush()
                Log.i(TAG, "Vídeo salvo com sucesso em: ${videoFile.absolutePath}")
                videoFile.absolutePath
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.w(TAG, "Escrita do arquivo de vídeo cancelada: ${videoFile.absolutePath}", e)
                videoFile.delete()
                throw e // Relança para ser pego pelo bloco catch da chamada principal
            }
            catch (e: Exception) {
                Log.e(TAG, "Erro ao escrever vídeo no arquivo: ${videoFile.absolutePath}", e)
                videoFile.delete()
                null
            } finally {
                try {
                    inputStream?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Erro ao fechar inputStream do vídeo: ${e.message}")
                }
                try {
                    outputStream?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Erro ao fechar outputStream do vídeo: ${e.message}")
                }
            }
        }
    }
}