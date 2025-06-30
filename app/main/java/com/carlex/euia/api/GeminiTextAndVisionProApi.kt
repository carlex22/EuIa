// File: com/carlex/euia/api/GeminiTextAndVisionProApi.kt
package com.carlex.euia.api

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.carlex.euia.managers.GerenciadorDeChavesApi
import com.carlex.euia.managers.NenhumaChaveApiDisponivelException
import com.carlex.euia.viewmodel.AuthViewModel
import com.carlex.euia.viewmodel.TaskType
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

// --- DATA CLASSES PARA A REQUISIÇÃO E RESPOSTA MANUAL DA API REST ---

data class GeminiProApiRequest(
    val contents: List<ContentBlock>,
    val generationConfig: GenerationConfigBlock
)

data class ContentBlock(
    val parts: List<Part>
)

// Usamos uma classe selada para que 'parts' possa conter diferentes tipos de objetos
sealed class Part
data class TextPart(@SerializedName("text") val text: String) : Part()
data class InlineDataPart(@SerializedName("inline_data") val inlineData: InlineData) : Part()

data class InlineData(
    @SerializedName("mime_type") val mimeType: String,
    @SerializedName("data") val data: String // Base64 encoded string
)

data class GenerationConfigBlock(
    val temperature: Float = 1.0f,
    val topP: Float = 0.95f
)

data class GeminiProApiResponse(
    val candidates: List<Candidate>?,
    val error: ApiError?
)
data class Candidate(val content: ContentBlock?)
data class ApiError(val code: Int?, val message: String?, val status: String?)


// --- INTERFACE RETROFIT PARA A API ---
private interface GeminiProApiService {
    @POST("v1beta/models/{modelName}:generateContent")
    suspend fun generateContent(
        @Path("modelName") modelName: String,
        @Query("key") apiKey: String,
        @Body request: GeminiProApiRequest
    ): Response<GeminiProApiResponse>
}


object GeminiTextAndVisionProApi {
    private const val TAG = "GeminiApiProManual"
    private const val modelName = //"gemini-2.5-flash-lite-preview-06-17"
                                    "gemini-2.5-flash"
                                    //"gemini-2.5-flash-preview-04-17" // Usando um modelo que suporta multimodal
    private const val TIPO_DE_CHAVE = "text"

    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val gerenciadorDeChaves: GerenciadorDeChavesApi by lazy { GerenciadorDeChavesApi(firestore) }

    // --- CLIENTE HTTP/RETROFIT MANUAL, SIMILAR AO GeminiAudio ---
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS) // Aumentando o timeout de conexão
            .readTimeout(320, TimeUnit.SECONDS)    // Aumentando o timeout de leitura para 5+ minutos
            .writeTimeout(120, TimeUnit.SECONDS)   // Aumentando o timeout de escrita
            .build()
    }

    private val retrofit: Retrofit by lazy {
        val gson = GsonBuilder().registerTypeAdapter(Part::class.java, PartAdapter()).create()
        Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    private val apiService: GeminiProApiService by lazy {
        retrofit.create(GeminiProApiService::class.java)
    }

    private val errorConverter: Converter<ResponseBody, ApiError> by lazy {
        retrofit.responseBodyConverter(ApiError::class.java, arrayOfNulls<Annotation>(0))
    }


    /**
     * Pergunta ao Gemini usando uma chamada REST direta com Retrofit,
     * incorporando pool de chaves e lógica de retry.
     */
    suspend fun perguntarAoGemini(
        pergunta: String,
        imagens: List<String>,
        arquivoTexto: String? = null
    ): Result<String> {
        val applicationContext = getApplicationFromContext()
        if (applicationContext == null) {
            val errorMsg = "Contexto da aplicação não disponível. Impossível verificar créditos."
            Log.e(TAG, errorMsg)
            return Result.failure(IllegalStateException(errorMsg))
        }

        val authViewModel = AuthViewModel(applicationContext)
        var creditsDeducted = false

        return withContext(Dispatchers.IO) {
            try {
                val deductionResult = authViewModel.checkAndDeductCredits(TaskType.TEXT_PRO)
                if (deductionResult.isFailure) {
                    return@withContext Result.failure(deductionResult.exceptionOrNull()!!)
                }
                creditsDeducted = true
                Log.i(TAG, "Créditos (${TaskType.TEXT_PRO.cost}) deduzidos para API Pro. Prosseguindo.")

                val keyCount = try {
                    firestore.collection("chaves_api_pool").get().await().size()
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao obter contagem de chaves, usando fallback 10.", e)
                    10
                }
                val MAX_TENTATIVAS = if (keyCount > 0) keyCount else 10
                var tentativas = 0

                while (tentativas < MAX_TENTATIVAS) {
                    var chaveAtual: String? = null
                    try {
                        chaveAtual = gerenciadorDeChaves.getChave(TIPO_DE_CHAVE)
                        Log.d(TAG, "Tentativa ${tentativas + 1}/$MAX_TENTATIVAS: Usando chave que termina em '${chaveAtual.takeLast(4)}'")

                        // Montar o corpo da requisição
                        val parts = mutableListOf<Part>()
                        parts.add(TextPart(pergunta))
                        arquivoTexto?.let { parts.add(TextPart(it)) }

                        val adjustedImagePaths = ajustarCaminhosDeImagem(imagens)
                        val imageParts = processarImagensParaBase64(adjustedImagePaths)
                        parts.addAll(imageParts)

                        val requestBody = GeminiProApiRequest(
                            contents = listOf(ContentBlock(parts = parts)),
                            generationConfig = GenerationConfigBlock(temperature = 2.0f, topP = 0.95f)
                        )

                        Log.i(TAG, "Enviando requisição para a API REST do Gemini...")
                        val response = apiService.generateContent(modelName, chaveAtual, requestBody)

                        if (response.isSuccessful && response.body() != null) {
                            val responseBody = response.body()!!
                            val textResponse = responseBody.candidates?.firstOrNull()?.content?.parts?.firstOrNull { it is TextPart }?.let { (it as TextPart).text }
                            
                            if (!textResponse.isNullOrBlank()) {
                                Log.i(TAG, "SUCESSO na tentativa ${tentativas + 1} com a chave '${chaveAtual.takeLast(4)}'.")
                                gerenciadorDeChaves.setChaveEmUso(chaveAtual, TIPO_DE_CHAVE)
                                return@withContext Result.success(textResponse)
                            } else {
                                throw IOException("Resposta da API bem-sucedida, mas sem conteúdo de texto.")
                            }
                        } else if (response.code() == 429) {
                            Log.w(TAG, "Erro 429 (Rate Limit) na chave '${chaveAtual.takeLast(4)}'. Bloqueando e tentando novamente...")
                            response.errorBody()?.close()
                            gerenciadorDeChaves.setChaveBloqueada(chaveAtual, TIPO_DE_CHAVE)
                            tentativas++
                            if (tentativas < MAX_TENTATIVAS) {
                                delay(1000)
                                continue
                            } else {
                                throw Exception("Máximo de tentativas ($MAX_TENTATIVAS) atingido.")
                            }
                        } else {
                            val errorBodyString = response.errorBody()?.string() ?: "Erro desconhecido da API"
                            throw IOException("Erro da API (${response.code()}): $errorBodyString")
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "Erro na tentativa ${tentativas + 1} com a chave '${chaveAtual?.takeLast(4)}'", e)
                        if (chaveAtual != null) {
                            gerenciadorDeChaves.setChaveBloqueada(chaveAtual, TIPO_DE_CHAVE)
                        }
                        if (e is NenhumaChaveApiDisponivelException) throw e
                        if (tentativas >= MAX_TENTATIVAS - 1) throw e 
                        tentativas++
                        delay(1000)
                    }
                }
                throw Exception("Falha ao obter resposta do Gemini para o tipo '$TIPO_DE_CHAVE' após $MAX_TENTATIVAS tentativas.")
            } catch (e: Exception) {
                if (creditsDeducted) {
                    Log.w(TAG, "Ocorreu um erro na API Pro. Reembolsando ${TaskType.TEXT_PRO.cost} créditos.", e)
                    authViewModel.refundCredits(TaskType.TEXT_PRO)
                }
                return@withContext Result.failure(e)
            }
        }
    }

    private suspend fun processarImagensParaBase64(imagePaths: List<String>): List<InlineDataPart> {
        return withContext(Dispatchers.IO) {
            imagePaths.mapNotNull { path ->
                try {
                    val file = File(path)
                    if (!file.exists()) {
                        Log.e(TAG, "Arquivo de imagem não encontrado: $path")
                        return@mapNotNull null
                    }
                    val bitmap = BitmapFactory.decodeFile(path)
                    val outputStream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                    val base64String = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                    bitmap.recycle() // Liberar memória do bitmap
                    InlineDataPart(InlineData(mimeType = "image/jpeg", data = base64String))
                } catch (e: Exception) {
                    Log.e(TAG, "Erro ao processar imagem para Base64: $path", e)
                    null
                }
            }
        }
    }
    
    // --- Funções auxiliares (mantidas como antes) ---
    private object AppContextHolder {
        var application: Application? = null
    }

    private fun getApplicationFromContext(): Application? {
        return AppContextHolder.application
    }

    fun setApplicationContext(app: Application) {
        AppContextHolder.application = app
    }

    private fun ajustarCaminhosDeImagem(imagens: List<String>): List<String> {
        return imagens.map { originalPath ->
            val originalFile = File(originalPath)
            if (originalFile.name.startsWith("thumb_")) {
                val cleanImageFile = File(originalFile.parentFile, originalFile.name.replaceFirst("thumb_", "img_"))
                if (cleanImageFile.exists()) cleanImageFile.absolutePath else originalPath
            } else {
                originalPath
            }
        }
    }

    private fun lerArquivoTexto(caminhoArquivo: String): String? {
        if (AppContextHolder.application == null) {
            Log.w(TAG, "O contexto da aplicação não foi definido. A dedução de créditos pode falhar.")
        }
        return try {
            File(caminhoArquivo).readText()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao ler arquivo de texto para API: $caminhoArquivo", e)
            null
        }
    }
}

// Adaptador GSON para lidar com a classe selada 'Part'
class PartAdapter : com.google.gson.JsonSerializer<Part>, com.google.gson.JsonDeserializer<Part> {
    override fun serialize(src: Part, typeOfSrc: java.lang.reflect.Type, context: com.google.gson.JsonSerializationContext): com.google.gson.JsonElement {
        return context.serialize(src)
    }

    override fun deserialize(json: com.google.gson.JsonElement, typeOfT: java.lang.reflect.Type, context: com.google.gson.JsonDeserializationContext): Part {
        val jsonObject = json.asJsonObject
        return when {
            jsonObject.has("text") -> context.deserialize(jsonObject, TextPart::class.java)
            jsonObject.has("inline_data") -> context.deserialize(jsonObject, InlineDataPart::class.java)
            else -> throw com.google.gson.JsonParseException("Objeto Part desconhecido: $json")
        }
    }
}