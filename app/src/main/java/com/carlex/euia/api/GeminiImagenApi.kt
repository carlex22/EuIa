// File: api/GeminiImageApi.kt
package com.carlex.euia.api

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import com.carlex.euia.data.ImagemReferencia
import com.carlex.euia.data.RefImageDataStoreManager
import com.carlex.euia.data.VideoDataStoreManager
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import com.carlex.euia.managers.GerenciadorDeChavesApi
import com.carlex.euia.managers.NenhumaChaveApiDisponivelException
import com.carlex.euia.utils.BitmapUtils
import com.carlex.euia.viewmodel.AuthViewModel
import com.carlex.euia.viewmodel.TaskType
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

// --- Modelos de Dados (Data Classes) --- (INALTEIRADOS)
data class ImgGeminiRequest(
    @SerializedName("contents") val contents: List<ImgContent>,
    @SerializedName("generationConfig") val generationConfig: ImgGenerationConfig
)
data class ImgContent(
    @SerializedName("parts") val parts: List<ImgPart>
)
sealed class ImgPart
data class ImgTextPart(
    @SerializedName("text") val text: String
) : ImgPart()
data class ImgInlineDataPart(
    @SerializedName("inline_data") val inlineData: ImgInlineData
) : ImgPart()
data class ImgInlineData(
    @SerializedName("mime_type") val mimeType: String,
    @SerializedName("data") val data: String
)
data class ImgGenerationConfig(
    @SerializedName("responseModalities") val responseModalities: List<String> = listOf("TEXT", "IMAGE"),
    @SerializedName("temperature") val temperature: Float? = null,
    @SerializedName("topK") val topK: Int? = null,
    @SerializedName("topP") val topP: Float? = null,
    @SerializedName("candidateCount") val candidateCount: Int? = null
)
data class ImgGeminiApiResponse(
    @SerializedName("candidates") val candidates: List<ImgCandidate>?,
    @SerializedName("error") val error: ImgApiError?
)
data class ImgCandidate(
    @SerializedName("content") val content: ImgModelContent?
)
data class ImgModelContent(
    @SerializedName("parts") val parts: List<ImgResponsePart>?,
    @SerializedName("role") val role: String?
)
data class ImgResponsePart(
    @SerializedName("inlineData") val inlineData: ImgInlineDataResponse?,
    @SerializedName("text") val text: String?
)
data class ImgInlineDataResponse(
    @SerializedName("mimeType") val mimeType: String?,
    @SerializedName("data") val data: String?
)
data class ImgApiError(
    @SerializedName("code") val code: Int?,
    @SerializedName("message") val message: String?,
    @SerializedName("status") val status: String?
)

// --- Interface de Serviço Retrofit --- (INALTEIRADA)
internal interface ImgGeminiApiService {
    //@POST("v1beta/models/gemini-2.0-flash-exp-image-generation:generateContent")
    @POST("v1beta/models/gemini-2.0-flash-preview-image-generation:generateContent")

        suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body requestBody: ImgGeminiRequest
    ): Response<ImgGeminiApiResponse>
}

// --- Cliente Retrofit --- (INALTEIRADO)
internal object ImgRetrofitClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"
    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
    val instance: ImgGeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ImgGeminiApiService::class.java)
    }
}

object GeminiImageApi{
    private const val TAG = "GeminiImageApiDirect"
    private const val TIPO_DE_CHAVE = "img"
    
    private const val DEFAULT_WIDTH = 720
    private const val DEFAULT_HEIGHT = 1280

    private val API_TEMPERATURE: Float? = 0.6f
    private val API_TOP_K: Int? = 40
    private val API_TOP_P: Float? = 0.6f
    private val API_CANDIDATE_COUNT: Int? = 1

    private val kotlinJsonParser = Json { ignoreUnknownKeys = true; isLenient = true }
    
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val gerenciadorDeChaves: GerenciadorDeChavesApi by lazy { GerenciadorDeChavesApi(firestore) }

    suspend fun gerarImagem(
        cena: String,
        prompt: String,
        context: Context,
        imagensParaUpload: List<ImagemReferencia>
    ): Result<String> {
        val authViewModel = AuthViewModel(context.applicationContext as Application)
        var creditsDeducted = false

        return withContext(Dispatchers.IO) {
            try {
                // ETAPA 1: VERIFICAR E DEDUZIR CRÉDITOS ANTES DE QUALQUER COISA
                val deductionResult = authViewModel.checkAndDeductCredits(TaskType.IMAGE)
                if (deductionResult.isFailure) {
                    return@withContext Result.failure(deductionResult.exceptionOrNull()!!)
                }
                creditsDeducted = true
                Log.i(TAG, "Créditos (${TaskType.IMAGE.cost}) deduzidos. Prosseguindo.")

                // ETAPA 2: LÓGICA DE GERAÇÃO COM TENTATIVAS
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
                        Log.d(TAG, "Tentativa ${tentativas + 1}/$MAX_TENTATIVAS ($TIPO_DE_CHAVE): Chave '${chaveAtual.takeLast(4)}'")

                        val (_, partsList) = prepararDadosParaApi(context, prompt, imagensParaUpload)
                        val generationConfig = ImgGenerationConfig(temperature = API_TEMPERATURE, topP = API_TOP_P, topK = API_TOP_K, candidateCount = API_CANDIDATE_COUNT)
                        val request = ImgGeminiRequest(listOf(ImgContent(partsList)), generationConfig)

                        Log.i(TAG, "Enviando request à API Gemini (Retrofit)...")
                        val response = ImgRetrofitClient.instance.generateContent(chaveAtual, request)
                        Log.i(TAG, "Resposta da API recebida. Código: ${response.code()}, Sucesso: ${response.isSuccessful}")

                        if (response.isSuccessful) {
                            val apiResponse = response.body()
                            val base64ImageString = apiResponse?.candidates?.firstOrNull()?.content?.parts?.firstOrNull { it.inlineData?.data != null }?.inlineData?.data
                            if (base64ImageString.isNullOrEmpty()) {
                                throw Exception("API retornou sucesso, mas sem dados de imagem. Detalhe: cod.${response.code()} ${apiResponse?.error?.message ?: response.message()}")
                            }

                            Log.i(TAG, "SUCESSO na tentativa ${tentativas + 1} com a chave '${chaveAtual.takeLast(4)}'.")
                            gerenciadorDeChaves.setChaveEmUso(chaveAtual, TIPO_DE_CHAVE)

                            val apiBitmap = BitmapUtils.decodeBitmapFromBase64(base64ImageString, DEFAULT_WIDTH, DEFAULT_HEIGHT) ?: throw Exception("Falha ao decodificar bitmap da resposta da API.")
                            val caminhoImagem = saveGeneratedBitmap(cena, apiBitmap, context) ?: throw Exception("Falha ao salvar a imagem gerada localmente.")
                            
                            BitmapUtils.safeRecycle(apiBitmap, "gerarImagem (apiBitmap final)")
                            return@withContext Result.success(caminhoImagem)
                        } else if (response.code() == 429) {
                            Log.w(TAG, "Erro 429 ($TIPO_DE_CHAVE) na chave '${chaveAtual.takeLast(4)}'. Bloqueando...")
                            response.errorBody()?.close()
                            gerenciadorDeChaves.setChaveBloqueada(chaveAtual, TIPO_DE_CHAVE)
                            tentativas++
                            if (tentativas < MAX_TENTATIVAS) {
                                delay(1000)
                                continue
                            } else {
                                throw Exception("429")
                            }
                        } else {
                            val errorBody = response.errorBody()?.string() ?: "Erro desconhecido"
                            response.errorBody()?.close()
                            throw IOException("Erro da API (${response.code()}): $errorBody")
                        }
                    } catch (e: NenhumaChaveApiDisponivelException) {
                        Log.e(TAG, "Não há chaves disponíveis para o tipo '$TIPO_DE_CHAVE'.", e)
                        throw e 
                    }
                } // Fim do while

                throw Exception("Falha ao gerar imagem para o tipo '$TIPO_DE_CHAVE' após $MAX_TENTATIVAS tentativas.")

            } catch (e: Exception) {
                if (creditsDeducted) {
                    Log.w(TAG, "Ocorreu um erro após a dedução de créditos. Reembolsando ${TaskType.IMAGE.cost} créditos.", e)
                    authViewModel.refundCredits(TaskType.IMAGE)
                }
                return@withContext Result.failure(e)
            }
        }
    }

    private suspend fun prepararDadosParaApi(
        context: Context,
        basePrompt: String,
        imagensParaUpload: List<ImagemReferencia>
    ): Pair<String, List<ImgPart>> {
        val videoPreferencesManager = VideoPreferencesDataStoreManager(context)
        val larguraPreferida = videoPreferencesManager.videoLargura.first()
        val alturaPreferida = videoPreferencesManager.videoAltura.first()

        var imagensEfetivas = imagensParaUpload
        if (imagensParaUpload.isEmpty()) {
           /* val videoDataStoreManager = VideoDataStoreManager(context)
            val globalImagesJson = videoDataStoreManager.imagensReferenciaJson.first()
            if (globalImagesJson.isNotBlank() && globalImagesJson != "[]") {
                try {
                    imagensEfetivas = kotlinJsonParser.decodeFromString(ListSerializer(ImagemReferencia.serializer()), globalImagesJson)
                } catch (e: Exception) { Log.e(TAG, "Falha ao desserializar lista global.", e) }
            }*/
        }
        
        val partsList = mutableListOf<ImgPart>()
        val promptFinal = buildComplexPrompt(basePrompt, imagensEfetivas, context)
        partsList.add(ImgTextPart("prompt_da_imagem: $promptFinal"))

        for (imagemRef in imagensEfetivas) {
            val pathToLoad = ajustarCaminhoThumbnail(imagemRef.path)
            val imageFile = File(pathToLoad)
            if (imageFile.exists()) {
                val bitmap = BitmapUtils.decodeSampledBitmapFromUri(context, Uri.fromFile(imageFile), larguraPreferida ?: DEFAULT_WIDTH, alturaPreferida ?: DEFAULT_HEIGHT)
                if (bitmap != null) {
                    val outputStream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                    val encodedImage = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                    partsList.add(ImgInlineDataPart(ImgInlineData("image/jpeg", encodedImage)))
                    BitmapUtils.safeRecycle(bitmap, "prepararDadosParaApi loop")
                }
            }
        }
        
        return Pair(promptFinal, partsList)
    }

    private suspend fun buildComplexPrompt(basePrompt: String, imagens: List<ImagemReferencia>, context: Context): String {
        val promptLimpo = basePrompt.replace("\"", "")
        val allImageContextText = StringBuilder()
        if (imagens.isNotEmpty()) {
            allImageContextText.appendLine("\n\n--- Contexto Adicional das Imagens de Referência ---")
            imagens.forEachIndexed { index, imagemRef ->
                val pathToLoadDesc = ajustarCaminhoThumbnail(imagemRef.path)
                allImageContextText.appendLine("Imagem de Referência ${index + 1} (Arquivo base: ${File(pathToLoadDesc).name}):")
                if (imagemRef.descricao.isNotBlank() && imagemRef.descricao != "(Pessoas: Não)" && imagemRef.descricao != "(Pessoas: Sim)") {
                    allImageContextText.appendLine("  Descrição: \"${imagemRef.descricao}\"")
                } else {
                    allImageContextText.appendLine("  Descrição: null")
                }
                if (imagemRef.pathVideo != null) {
                    allImageContextText.appendLine("  Tipo Original: Vídeo (usando frame como referência visual)")
                    imagemRef.videoDurationSeconds?.let { duration ->
                        allImageContextText.appendLine("  Duração do Vídeo Original: $duration segundos")
                    }
                } else {
                    allImageContextText.appendLine("  Tipo Original: Imagem Estática")
                }
                allImageContextText.appendLine("  Contém Pessoas (na referência original): ${if (imagemRef.containsPeople) "Sim" else "Não"}")
                allImageContextText.appendLine()
            }
            allImageContextText.appendLine("--- Fim do Contexto Adicional ---")
        }

        val finalPromptComContextoDasImagens = if (allImageContextText.toString().lines().count { it.isNotBlank() } > 2) {
            promptLimpo //+ allImageContextText.toString()
        } else {
            promptLimpo
        }

        val refImageDataStoreManager = RefImageDataStoreManager(context)
        val refObjetoDetalhesJson = refImageDataStoreManager.refObjetoDetalhesJson.first()
        
        return buildString {
            append(finalPromptComContextoDasImagens)
           /* if (refObjetoDetalhesJson.isNotBlank() && refObjetoDetalhesJson != "{}") {
                appendLine()
                appendLine()
                append("--- INFORMAÇÕES MUITO IMPORTANTE DETALHES DE OBJETOS OU ROUPAS DA IMAGEN ---")
                appendLine()
                append(refObjetoDetalhesJson)
                appendLine()
                append("--- FIM DAS INFORMAÇÕES ADICIONAIS ---")
            }*/
        }
    }
    
    private fun ajustarCaminhoThumbnail(path: String): String {
        val file = File(path)
        if (file.name.startsWith("thumb_")) {
            val cleanFile = File(file.parentFile, file.name.replaceFirst("thumb_", "img_"))
            if (cleanFile.exists()) return cleanFile.absolutePath
        }
        return path
    }

    private suspend fun saveGeneratedBitmap(cena: String, apiBitmap: Bitmap, context: Context): String? {
        val videoPrefs = VideoPreferencesDataStoreManager(context)
        val projectDirName = videoPrefs.videoProjectDir.first()
        val (targetWidth, targetHeight) = videoPrefs.videoLargura.first() to videoPrefs.videoAltura.first()
        
        var resizedBitmap: Bitmap? = null
        try {
            var finalWidth = targetWidth ?: DEFAULT_WIDTH
            var finalHeight = targetHeight ?: DEFAULT_HEIGHT
            
            if (finalWidth > finalHeight)
                finalWidth = (finalWidth *1.1).toInt()
            else
                finalHeight = (finalHeight*1.1).toInt()
            
            resizedBitmap = //apiBitmap
            BitmapUtils.resizeWithTransparentBackground(apiBitmap, finalWidth, finalHeight)
                ?: return null

            val saveFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSLESS else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            
            return BitmapUtils.saveBitmapToFile(context, resizedBitmap, projectDirName, "gemini_generated_images", cena, saveFormat, 95)
        } finally {
            BitmapUtils.safeRecycle(resizedBitmap, "saveGeneratedBitmap (resized)")
        }
    }
}