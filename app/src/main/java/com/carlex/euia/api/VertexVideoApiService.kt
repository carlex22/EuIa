// File: euia/api/VertexVideoApi.kt
package com.carlex.euia.api

import android.content.Context
import android.util.Base64
import android.util.Log
import com.carlex.euia.BuildConfig
import com.carlex.euia.data.*
import com.google.firebase.auth.FirebaseAuth
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
import retrofit2.http.Url
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

// --- Data Classes para comunicação App Android <-> SEU Backend (Cloud Functions) ---
@Serializable
data class VideoGenerationBackendRequest(
    val prompt: String,
    val imageBase64: String?,
    val userId: String,
    val aspectRatio: String,
    val durationSeconds: Int
)

@Serializable
data class BackendOperationResponse(
    val operationId: String? = null, // Este DEVE ser o operationName COMPLETO da Vertex AI
    val status: String? = null,
    val videoUrl: String? = null,
    val error: String? = null
)

@Serializable
data class BackendOperationStatus(
    val operationId: String, // O operationName completo da Vertex AI
    val status: String,
    val videoUrl: String? = null,
    val progress: Float? = null,
    val error: String? = null
)

// --- Interface Retrofit para SUAS Cloud Functions ---
interface VertexVideoApiService {
    @POST("https://us-central1-euia-55398.cloudfunctions.net/startVeoGeneration") // Substitua pelo nome real
    suspend fun startVideoGenerationViaBackend(
        @Body request: VideoGenerationBackendRequest
    ): Response<BackendOperationResponse>

    // <<< --- AJUSTE AQUI: O endpoint da CF pode receber o operationName completo como path parameter
    // Ou, se preferir, a CF pode receber apenas o UUID e reconstruir o operationName.
    // Para este exemplo, assumo que a CF espera o operationName completo ou sabe como lidar com ele.
    // Se a CF espera apenas o UUID, o Path seria apenas {operationUUID} e a CF adicionaria o prefixo.
    // Vamos supor que passamos o operationName completo, mas a CF extrai o UUID se necessário.
    // Uma forma mais limpa seria a CF ter um endpoint que espera apenas o UUID.
    // Por simplicidade aqui, vamos manter a chamada com o operationId (que será o operationName completo)
    // e a CF no backend que decide como usar isso para chamar a Vertex AI.
    // Se a CF espera apenas o UUID no path: @GET("YOUR_GET_STATUS_FUNCTION_NAME/{operationUUID}")
    // e você extrairia o UUID do operationName antes de chamar.
    // Para manter simples por agora, passamos o operationId (que será o name completo)
    // e a CF no backend é responsável por formar a URL correta para a Vertex AI.
    @GET("https://us-central1-euia-55398.cloudfunctions.net/getVeoGenerationStatus") // Removido {operationId} do path
    suspend fun checkVideoStatusViaBackend(
        @retrofit2.http.Query("operationName") operationName: String // Passa o operationName como Query Parameter
    ): Response<BackendOperationStatus>

    @GET
    suspend fun downloadVideoFile(@Url videoUrl: String): Response<ResponseBody>
}

// --- Objeto Principal da API ---
object VertexVideoApi {
    private const val TAG = "VertexVideoApi"
    private const val CLOUD_FUNCTIONS_BASE_URL = "https://us-central1-euia-55398.cloudfunctions.net/"

    private const val POLLING_INTERVAL_MS = 15000L
    private const val MAX_POLLING_ATTEMPTS = 60
    private const val MAX_CONSECUTIVE_POLLING_FAILURES = 5

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val backendService: VertexVideoApiService by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        }
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(CLOUD_FUNCTIONS_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(jsonParser.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(VertexVideoApiService::class.java)
    }
    private val firebaseAuth = FirebaseAuth.getInstance()

    suspend fun gerarVideo(
        cena: String,
        prompt: String,
        context: Context,
        imagemReferenciaPath: String? = null
    ): Result<List<String>> {
        Log.i(TAG, "--- INÍCIO: VertexVideoApi.gerarVideo para cena '$cena' (via Backend) ---")
        return withContext(Dispatchers.IO) {
            try {
                val videoPreferencesManager = VideoPreferencesDataStoreManager(context)
                val projectDirName = videoPreferencesManager.videoProjectDir.first()
                val videoAspectRatio = videoPreferencesManager.videoAspectRatio.first()
                val videoDurationSeconds = videoPreferencesManager.defaultSceneDurationSeconds.first().toInt().coerceAtLeast(1)

                val userId = firebaseAuth.currentUser?.uid
                    ?: return@withContext Result.failure(Exception("Usuário não autenticado para gerar vídeo."))

                var imageBase64: String? = null
                if (imagemReferenciaPath != null) {
                    val imageFile = File(imagemReferenciaPath)
                    if (imageFile.exists()) {
                        try {
                            val imageBytes = imageFile.readBytes()
                            imageBase64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                        } catch (e: Exception) {
                            Log.e(TAG, "Erro ao converter imagem de referência para Base64: $imagemReferenciaPath", e)
                        }
                    } else {
                        Log.w(TAG, "Imagem de referência local não encontrada: $imagemReferenciaPath")
                    }
                }

                val backendRequest = VideoGenerationBackendRequest(
                    prompt = prompt,
                    imageBase64 = imageBase64,
                    userId = userId,
                    aspectRatio = videoAspectRatio,
                    durationSeconds = videoDurationSeconds
                )

                Log.i(TAG, "Enviando requisição para backend (Cloud Function) para iniciar geração para cena '$cena'...")
                val startResponseCall = backendService.startVideoGenerationViaBackend(backendRequest)

                if (!startResponseCall.isSuccessful || startResponseCall.body() == null) {
                    val errorBody = startResponseCall.errorBody()?.string() ?: "Erro desconhecido do backend ao iniciar"
                    Log.e(TAG, "Falha ao iniciar geração no backend. Código: ${startResponseCall.code()}. Erro: $errorBody")
                    return@withContext Result.failure(Exception("Erro do Backend (${startResponseCall.code()}): ${errorBody.take(500)}"))
                }

                val operationResponse = startResponseCall.body()!!
                if (operationResponse.error != null) {
                    Log.e(TAG, "Backend retornou erro ao iniciar: ${operationResponse.error}")
                    return@withContext Result.failure(Exception("Erro do Backend: ${operationResponse.error}"))
                }

                if (operationResponse.status?.equals("COMPLETED", ignoreCase = true) == true && !operationResponse.videoUrl.isNullOrBlank()) {
                    Log.i(TAG, "Backend retornou vídeo diretamente. URL: ${operationResponse.videoUrl}")
                    val videoFileName = "${cena}_video_vertex_${System.currentTimeMillis()}.mp4"
                    val downloadResult = downloadAndSaveVideo(operationResponse.videoUrl!!, context, projectDirName, videoFileName)
                    return@withContext if (downloadResult != null) Result.success(listOf(downloadResult))
                                     else Result.failure(Exception("Falha ao baixar vídeo retornado diretamente pelo backend."))
                }

                val operationNameFromBackend = operationResponse.operationId // Este deve ser o nome completo da operação Vertex AI
                if (operationNameFromBackend.isNullOrBlank()) {
                    Log.e(TAG, "Backend não retornou ID de operação válido (operationName).")
                    return@withContext Result.failure(Exception("Backend não retornou ID de operação (operationName)."))
                }

                Log.i(TAG, "Operação de vídeo iniciada no backend: $operationNameFromBackend. Status inicial: ${operationResponse.status}. Iniciando polling...")

                var attempts = 0
                var consecutiveFailures = 0
                var operationDone = false
                var finalVideoUrl: String? = null

                while (!operationDone && attempts < MAX_POLLING_ATTEMPTS && coroutineContext.isActive) {
                    attempts++
                    delay(POLLING_INTERVAL_MS)
                    if (!coroutineContext.isActive) throw CancellationException("Geração de vídeo cancelada durante polling.")

                    Log.d(TAG, "Verificando status da operação no backend ($operationNameFromBackend), tentativa $attempts / $MAX_POLLING_ATTEMPTS")

                    // <<< --- AJUSTE AQUI: Passa o operationName completo como Query Parameter --- >>>
                    val statusResponseCall = backendService.checkVideoStatusViaBackend(operationName = operationNameFromBackend)
                    if (!statusResponseCall.isSuccessful || statusResponseCall.body() == null) {
                        consecutiveFailures++
                        val errorMsg = "Falha ao verificar status no backend (HTTP ${statusResponseCall.code()}). Tentativa $consecutiveFailures/$MAX_CONSECUTIVE_POLLING_FAILURES."
                        Log.w(TAG, errorMsg)
                        if (consecutiveFailures >= MAX_CONSECUTIVE_POLLING_FAILURES) {
                            Log.e(TAG, "Muitas falhas consecutivas no polling. Abortando.")
                            return@withContext Result.failure(Exception(errorMsg))
                        }
                        continue
                    }
                    consecutiveFailures = 0

                    val statusBody = statusResponseCall.body()!!
                    Log.d(TAG, "Status recebido do backend: ${statusBody.status}, Progresso: ${statusBody.progress}, URL: ${statusBody.videoUrl}")

                    when (statusBody.status.uppercase()) {
                        "COMPLETED", "SUCCEEDED" -> {
                            operationDone = true
                            finalVideoUrl = statusBody.videoUrl
                            if (finalVideoUrl.isNullOrBlank()) {
                                Log.e(TAG, "Backend completou operação mas não retornou URL do vídeo.")
                                return@withContext Result.failure(Exception("Operação concluída pelo backend sem URL de vídeo."))
                            }
                            Log.i(TAG, "Operação CONCLUÍDA no backend. URL do vídeo: $finalVideoUrl")
                        }
                        "FAILED", "FAILED_ON_VERTEX", "FAILED_ON_BACKEND" -> {
                            val backendError = statusBody.error ?: "Erro desconhecido no backend."
                            Log.e(TAG, "Geração de vídeo FALHOU no backend: $backendError")
                            return@withContext Result.failure(Exception("Falha no Backend: $backendError"))
                        }
                        "PROCESSING", "RUNNING", "PENDING", "PROCESSING_ON_VERTEX", "VERTEX_OPERATION_STARTED" -> {
                            Log.d(TAG, "Vídeo ainda processando no backend (status: ${statusBody.status})...")
                        }
                        else -> Log.w(TAG, "Status desconhecido do backend: ${statusBody.status}")
                    }
                }

                if (!operationDone && coroutineContext.isActive) {
                    Log.e(TAG, "Timeout ou máximo de tentativas atingido: Operação não concluída após $attempts tentativas.")
                    return@withContext Result.failure(Exception("Timeout/Máximo de tentativas: Geração de vídeo não concluída."))
                }
                if (!coroutineContext.isActive) {
                     throw CancellationException("Geração de vídeo cancelada durante o polling.")
                }


                if (finalVideoUrl != null) {
                    val videoFileName = "${cena}_video_vertex_${System.currentTimeMillis()}.mp4"
                    Log.d(TAG, "Iniciando download do vídeo: $finalVideoUrl para $videoFileName")
                    val downloadResult = downloadAndSaveVideo(finalVideoUrl, context, projectDirName, videoFileName)
                    if (downloadResult != null) {
                        Log.i(TAG, "--- FIM SUCESSO: VertexVideoApi.gerarVideo para cena '$cena' ---")
                        return@withContext Result.success(listOf(downloadResult))
                    } else {
                        Log.e(TAG, "Falha ao baixar e salvar o vídeo final de $finalVideoUrl")
                        return@withContext Result.failure(Exception("Falha ao baixar o vídeo da URL fornecida pelo backend."))
                    }
                } else {
                    Log.e(TAG, "Operação concluída (ou loop terminado) mas finalVideoUrl é nulo.")
                    return@withContext Result.failure(Exception("Operação finalizada sem URL de vídeo válida."))
                }

            } catch (e: CancellationException) {
                Log.w(TAG, "Geração de vídeo para cena '$cena' cancelada: ${e.message}")
                return@withContext Result.failure(e)
            }
            catch (e: Exception) {
                Log.e(TAG, "Erro em VertexVideoApi.gerarVideo para cena '$cena': ${e.message}", e)
                return@withContext Result.failure(e)
            }
        }
    }

    private suspend fun downloadAndSaveVideo(
        videoUrl: String,
        context: Context,
        projectDirName: String,
        fileName: String
    ): String? = withContext(Dispatchers.IO) {
        Log.d(TAG, "Tentando baixar vídeo de: $videoUrl")
        try {
            val response = backendService.downloadVideoFile(videoUrl)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val targetDir = if (projectDirName.isNotBlank()) {
                    File(context.getExternalFilesDir(null), projectDirName)
                } else {
                    File(context.getExternalFilesDir(null), "vertex_generated_videos_default")
                }
                if (!targetDir.exists() && !targetDir.mkdirs()) {
                    Log.e(TAG, "Falha ao criar diretório de destino para vídeo: ${targetDir.absolutePath}")
                    return@withContext null
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
                        if (!coroutineContext.isActive) {
                            Log.w(TAG, "Download do vídeo cancelado durante a escrita para $fileName.")
                            videoFile.delete()
                            throw CancellationException("Download do vídeo cancelado.")
                        }
                        outputStream.write(buffer, 0, bytesRead)
                    }
                    outputStream.flush()
                    Log.i(TAG, "Vídeo salvo com sucesso em: ${videoFile.absolutePath}")
                    return@withContext videoFile.absolutePath
                } catch (e: CancellationException){
                    Log.w(TAG, "Download cancelado para $fileName.")
                    videoFile.delete()
                    throw e
                }
                catch (e: IOException) {
                    Log.e(TAG, "Erro de IO ao salvar vídeo $fileName: ${e.message}", e)
                    videoFile.delete()
                    return@withContext null
                } finally {
                    inputStream?.close()
                    outputStream?.close()
                }
            } else {
                val errorBody = response.errorBody()?.string() ?: "Erro desconhecido no download"
                Log.e(TAG, "Falha no download do vídeo. Código: ${response.code()}. Erro: ${errorBody.take(200)}")
                return@withContext null
            }
        } catch (e: CancellationException){
             Log.w(TAG, "Download do vídeo (chamada externa) cancelado: $videoUrl")
             throw e
        }
        catch (e: Exception) {
            Log.e(TAG, "Exceção durante o download do vídeo de $videoUrl: ${e.message}", e)
            return@withContext null
        }
    }
}