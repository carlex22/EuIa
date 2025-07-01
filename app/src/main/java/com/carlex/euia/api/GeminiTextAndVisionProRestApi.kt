// File: euia/api/GeminiTextAndVisionProRestApi.kt
// >>>>> VERSÃO FINAL CORRIGIDA (AGORA VAI!) <<<<<
package com.carlex.euia.api

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.carlex.euia.BuildConfig
import com.carlex.euia.managers.GerenciadorDeChavesApi
import com.carlex.euia.managers.NenhumaChaveApiDisponivelException
import com.carlex.euia.viewmodel.AuthViewModel
import com.carlex.euia.viewmodel.TaskType
import com.google.firebase.firestore.FirebaseFirestore
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromJsonElement

private const val TAG = "GeminiProRestApi"

// --- Data Classes para a Requisição REST ---
@Serializable
data class RestGeminiRequest(
    val contents: List<RestContent>,
    @SerialName("generationConfig") val generationConfig: RestGenerationConfig
)

@Serializable
data class RestContent(
    val role: String = "user",
    val parts: List<RestPart>
)

@Serializable
data class RestPart(
    @SerialName("text") val text: String? = null,
    @SerialName("inlineData") val inlineData: RestInlineData? = null
)

@Serializable
data class RestInlineData(
    @SerialName("mime_type") val mimeType: String,
    val data: String // Base64 encoded
)

@Serializable
data class RestGenerationConfig(
    val temperature: Float,
    val topP: Float,
    val responseMimeType: String = "text/plain"
)

// --- Data Classes para a Resposta do Stream ---
@Serializable
data class RestStreamResponse(
    val candidates: List<RestCandidate>? = null
)

@Serializable
data class RestCandidate(
    val content: RestContent? = null
)

// --- Interface Retrofit ---
interface GeminiProRestApiService {
    @Streaming
    @POST("v1beta/models/{modelId}:streamGenerateContent")
    suspend fun generateContentStream(
        @retrofit2.http.Path("modelId") modelId: String,
        @Query("key") apiKey: String,
        @Body requestBody: RestGeminiRequest
    ): Response<ResponseBody>
}

// --- Objeto Cliente da API (Autossuficiente) ---
object GeminiTextAndVisionProRestApi {

    
    private const val modelName = "gemini-2.5-pro"
    private const val TIPO_DE_CHAVE = "text"

    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val gerenciadorDeChaves: GerenciadorDeChavesApi by lazy { GerenciadorDeChavesApi(firestore) }

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val retrofitService: GeminiProRestApiService by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        }
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(okHttpClient)
            .addConverterFactory(jsonParser.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GeminiProRestApiService::class.java)
    }

    suspend fun perguntarAoGemini(
        pergunta: String,
        imagens: List<String>,
        arquivoTexto: String? = null,
        youtubeUrl: String? = null // Mantido para compatibilidade, embora não seja usado na requisição atual
    ): Result<String> {
        val applicationContext = getApplicationFromContext()
            ?: return Result.failure(IllegalStateException("Contexto da aplicação não disponível."))

        val authViewModel = AuthViewModel(applicationContext)
        var creditsDeducted = false

        return withContext(Dispatchers.IO) {
            try {
                authViewModel.checkAndDeductCredits(TaskType.TEXT_PRO).getOrThrow()
                creditsDeducted = true
                Log.i(TAG, "Créditos (${TaskType.TEXT_PRO.cost}) deduzidos. Prosseguindo.")

                val keyCount = try {
                    val count = firestore.collection("chaves_api_pool").get().await().size()
                    if (count > 0) count else 10
                } catch (e: Exception) { 10 }
                val MAX_TENTATIVAS = if (keyCount > 0) keyCount else 10
                
                var tentativas = 0
                while (tentativas < MAX_TENTATIVAS) {
                    var chaveAtual: String? = null
                    try {
                        chaveAtual = gerenciadorDeChaves.getChave(TIPO_DE_CHAVE)
                        
                        val bitmaps = processarImagens(ajustarCaminhosDeImagem(imagens))
                        val textoArquivoLido = arquivoTexto?.let { lerArquivoTexto(it) }

                        val result = performRestCall(
                            modelName = modelName,
                            apiKey = chaveAtual,
                            prompt = pergunta,
                            bitmaps = bitmaps,
                            additionalText = textoArquivoLido
                        )

                        if (result.isSuccess) {
                            gerenciadorDeChaves.setChaveEmUso(chaveAtual, TIPO_DE_CHAVE)
                            return@withContext result
                        } else {
                            throw result.exceptionOrNull()!!
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Tentativa ${tentativas + 1} falhou com a chave '${chaveAtual?.takeLast(4)}'. Erro: ${e.message}")
                        if (chaveAtual != null) gerenciadorDeChaves.setChaveBloqueada(chaveAtual, TIPO_DE_CHAVE)
                        tentativas++
                        if (tentativas >= MAX_TENTATIVAS) throw Exception("Máximo de tentativas atingido. Último erro: ${e.message}", e)
                        delay(1000)
                    }
                }
                throw Exception("Falha ao obter resposta do Gemini após $MAX_TENTATIVAS tentativas.")
            
            } catch (e: Exception) {
                if (creditsDeducted) {
                    Log.w(TAG, "Ocorreu um erro na API Pro. Reembolsando.", e)
                    authViewModel.refundCredits(TaskType.TEXT_PRO)
                }
                return@withContext Result.failure(e)
            }
        }
    }

    private suspend fun performRestCall(
        modelName: String,
        apiKey: String,
        prompt: String,
        bitmaps: List<Bitmap>,
        additionalText: String?
    ): Result<String> {
        val parts = mutableListOf<RestPart>()
        parts.add(RestPart(text = prompt))
        if (additionalText != null) parts.add(RestPart(text = additionalText))
        bitmaps.forEach { bitmap ->
            val baos = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            val encodedImage = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)
            parts.add(RestPart(inlineData = RestInlineData("image/jpeg", encodedImage)))
        }

        val request = RestGeminiRequest(
            contents = listOf(RestContent(parts = parts)),
            generationConfig = RestGenerationConfig(temperature = 2.0f, topP = 0.95f)
        )

        val response = retrofitService.generateContentStream(modelName, apiKey, request)
        if (!response.isSuccessful) throw IOException("Erro HTTP ${response.code()}: ${response.errorBody()?.string()}")
        
        val responseBody = response.body() ?: throw IOException("Corpo da resposta vazio")
        val fullResponseText = processStream(responseBody)
        if (fullResponseText.isBlank()) throw IOException("Stream da API não retornou texto.")
        
        return Result.success(fullResponseText)
    }

    // File: euia/api/GeminiTextAndVisionProRestApi.kt

// ... (todo o resto da classe permanece igual)

    private fun processStream(body: ResponseBody): String {
        val fullJsonResponse = StringBuilder()
        BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
            // Simplesmente junta cada linha em uma única string.
            // A API do Gemini em stream às vezes envia os JSONs quebrados em várias linhas.
            reader.forEachLine { line ->
                fullJsonResponse.append(line)
            }
        }
        
        val rawJsonStreamString = fullJsonResponse.toString()
        Log.d(TAG, "String bruta completa recebida do stream: ${rawJsonStreamString.take(500)}...")

        val finalConcatenatedText = StringBuilder()
        try {
            // Decodifica a string completa como um Array de elementos JSON.
            // Não adicionamos mais colchetes, pois a resposta já é um array.
            val jsonArray = jsonParser.decodeFromString<JsonArray>(rawJsonStreamString)

            for (jsonElement in jsonArray) {
                try {
                    // Decodifica cada elemento do array na nossa data class.
                    val streamResponse = jsonParser.decodeFromJsonElement<RestStreamResponse>(jsonElement)
                    streamResponse.candidates?.firstOrNull()?.content?.parts?.forEach { part ->
                        part.text?.let { textChunk ->
                            // Remove os marcadores de código ```json e ``` da resposta da IA.
                            val cleanedChunk = textChunk
                                .replace("```json", "")
                                .replace("```", "")
                            
                            Log.d(TAG, "CHUNK (REST): --> $cleanedChunk")
                            finalConcatenatedText.append(cleanedChunk)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Elemento do stream não pôde ser parseado como RestStreamResponse: '$jsonElement'", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar o JSON Array completo do stream. Retornando resposta bruta como fallback. Erro: ${e.message}", e)
            // Fallback: Se o parse do array inteiro falhar, retorna o que foi recebido.
            return rawJsonStreamString
        }

        // Limpeza final de caracteres escapados que a IA pode ter enviado
        return finalConcatenatedText.toString()
           /* .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\u0026", "&")*/
    }

// ... (o resto do arquivo permanece igual)




    private object AppContextHolder { var application: Application? = null }
    fun setApplicationContext(app: Application) { AppContextHolder.application = app }
    private fun getApplicationFromContext(): Application? = AppContextHolder.application

    private fun ajustarCaminhosDeImagem(imagens: List<String>): List<String> =
        imagens.map { path ->
            val file = File(path)
            if (file.name.startsWith("thumb_")) {
                File(file.parentFile, file.name.replaceFirst("thumb_", "img_")).takeIf { it.exists() }?.absolutePath ?: path
            } else { path }
        }

    private fun processarImagens(imagePaths: List<String>): List<Bitmap> =
        imagePaths.mapNotNull { path ->
            try { BitmapFactory.decodeFile(path) } 
            catch (e: Exception) {
                Log.e(TAG, "Erro ao processar imagem para API: $path", e)
                null
            }
        }

    private fun lerArquivoTexto(caminhoArquivo: String): String? =
        try { File(caminhoArquivo).readText() } 
        catch (e: Exception) {
            Log.e(TAG, "Erro ao ler arquivo de texto para API: $caminhoArquivo", e)
            null
        }
}