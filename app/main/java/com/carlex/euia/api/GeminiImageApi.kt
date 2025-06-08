// File: api/GeminiImageApi.kt
package com.carlex.euia.api

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri // Necessário para Uri.fromFile
import android.os.Build
import android.util.Base64
import android.util.Log
import com.carlex.euia.data.RefImageDataStoreManager
import com.carlex.euia.BuildConfig
import com.carlex.euia.data.ImagemReferencia
import com.carlex.euia.data.VideoDataStoreManager
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import com.carlex.euia.utils.BitmapUtils
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.TimeUnit

// --- Modelos de Dados (Data Classes) ---
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

// --- Interface de Serviço Retrofit ---
internal interface ImgGeminiApiService {
    @POST("v1beta/models/gemini-2.0-flash-preview-image-generation:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body requestBody: ImgGeminiRequest
    ): Response<ImgGeminiApiResponse>
}

// --- Cliente Retrofit ---
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

object GeminiImageApi {

    private const val TAG = "GeminiImageApi"
    private val apiKey = BuildConfig.GEMINI_API_KEY
    private const val DEFAULT_WIDTH = 720
    private const val DEFAULT_HEIGHT = 1280

    private val API_TEMPERATURE: Float? = 0.2f
    private val API_TOP_K: Int? = 40
    private val API_TOP_P: Float? = 0.2f
    private val API_CANDIDATE_COUNT: Int? = 1

    private val kotlinJsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun gerarImagem(
        cena: String,
        prompt: String,
        context: Context,
        imagensParaUpload: List<ImagemReferencia>
    ): Result<String> {
        Log.i(TAG, "--- INÍCIO: GeminiImageApi.gerarImagem ---")
        Log.d(TAG, "Parâmetros de entrada:")
        Log.d(TAG, "  Cena (prefixo arquivo): $cena")
        Log.d(TAG, "  Prompt original (primeiros 100 chars): ${prompt.take(100)}...")
        Log.d(TAG, "  imagensParaUpload (contagem inicial recebida): ${imagensParaUpload.size}")

        return withContext(Dispatchers.IO) {
            var apiBitmap: Bitmap? = null
            try {
                val videoPreferencesManager = VideoPreferencesDataStoreManager(context)
                val projectDirName = videoPreferencesManager.videoProjectDir.first()
                val larguraPreferida = videoPreferencesManager.videoLargura.first()
                val alturaPreferida = videoPreferencesManager.videoAltura.first()
                Log.d(TAG, "Preferências carregadas: projectDirName='$projectDirName', larguraPreferida=${larguraPreferida ?: "Padrão ($DEFAULT_WIDTH)"}, alturaPreferida=${alturaPreferida ?: "Padrão ($DEFAULT_HEIGHT)"}")

                var imagensEfetivasParaApi = imagensParaUpload
                if (imagensParaUpload.isEmpty()) {
                    Log.d(TAG, "Lista de imagens recebida está vazia. Tentando carregar lista global do VideoDataStoreManager.")
                    val videoDataStoreManager = VideoDataStoreManager(context)
                    val globalImagesJson = videoDataStoreManager.imagensReferenciaJson.first()
                    if (globalImagesJson.isNotBlank() && globalImagesJson != "[]") {
                        try {
                            imagensEfetivasParaApi = kotlinJsonParser.decodeFromString(ListSerializer(ImagemReferencia.serializer()), globalImagesJson)
                            Log.d(TAG, "Carregada lista global com ${imagensEfetivasParaApi.size} imagens.")
                        } catch (e: Exception) {
                            Log.e(TAG, "Falha ao desserializar lista global de imagens: ${e.message}", e)
                        }
                    } else {
                        Log.d(TAG, "Lista global de imagens do VideoDataStoreManager também está vazia ou é inválida.")
                    }
                } else {
                    Log.d(TAG, "Usando a lista de imagens de referência fornecida com ${imagensEfetivasParaApi.size} itens.")
                }

                if (imagensEfetivasParaApi.isNotEmpty()) {
                     imagensEfetivasParaApi.forEachIndexed { index, imgRef ->
                        Log.d(TAG, "    ImgRef (a ser usada na API) [$index]: Path='${File(imgRef.path).name}', Desc='${imgRef.descricao.take(50)}...', pathVideo=${imgRef.pathVideo != null}, containsPeople=${imgRef.containsPeople}")
                    }
                } else {
                    Log.d(TAG, "    Nenhuma imagem de referência será enviada para a API Gemini (nem específica, nem global).")
                }


                var promptLimpo = prompt.replace("\"", "")
                Log.d(TAG, "Prompt limpo (sem aspas, primeiros 100 chars): ${promptLimpo.take(100)}...")
                val partsList = mutableListOf<ImgPart>()

                val allImageContextText = StringBuilder()
                if (imagensEfetivasParaApi.isNotEmpty()) {
                    allImageContextText.appendLine("\n\n--- Contexto Adicional das Imagens de Referência ---")
                    imagensEfetivasParaApi.forEachIndexed { index, imagemRef ->
                        // <<< --- AJUSTE AQUI: Determinar o caminho da imagem a ser realmente carregada --- >>>
                        var pathToLoad = imagemRef.path
                        val originalFileName = File(imagemRef.path).name
                        if (originalFileName.startsWith("thumb_")) {
                            // Se o path atual é de uma thumbnail (thumb_...), tenta encontrar a imagem limpa (img_...)
                            val cleanImageName = originalFileName.replaceFirst("thumb_", "img_")
                            val parentDir = File(imagemRef.path).parentFile
                            if (parentDir != null) {
                                val cleanImageFile = File(parentDir, cleanImageName)
                                if (cleanImageFile.exists()) {
                                    pathToLoad = cleanImageFile.absolutePath
                                    Log.d(TAG, "Referência é thumbnail, usando imagem limpa: $cleanImageName")
                                } else {
                                    Log.w(TAG, "Referência é thumbnail ($originalFileName), mas imagem limpa ($cleanImageName) não encontrada. Usando a thumbnail como fallback.")
                                }
                            }
                        }
                        // <<< --- FIM DO AJUSTE --- >>>

                        allImageContextText.appendLine("Imagem de Referência ${index + 1} (Arquivo base para API: ${File(pathToLoad).name}):")
                        if (imagemRef.descricao.isNotBlank() && imagemRef.descricao != "(Pessoas: Não)" && imagemRef.descricao != "(Pessoas: Sim)") {
                            allImageContextText.appendLine("  Descrição: \"${imagemRef.descricao}\"")
                        } else {
                            allImageContextText.appendLine("  Descrição: null")
                        }
                        if (imagemRef.pathVideo != null) { // Verifica se a ImagemReferencia original era um vídeo
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
                Log.d(TAG, "Contexto adicional das imagens de referência construído (tamanho: ${allImageContextText.length}):\n${allImageContextText.toString().take(500)}...")


                val finalPromptComContextoDasImagens = if (allImageContextText.toString().lines().count { it.isNotBlank() } > 2) {
                    promptLimpo + allImageContextText.toString()
                } else {
                    promptLimpo
                }
                
                Log.d(TAG, "Prompt com contexto de imagens (primeiros 1000 chars): ${finalPromptComContextoDasImagens.take(1000)}...")
             
                
                val refImageDataStoreManager = RefImageDataStoreManager(context)
                val refObjetoDetalhesJson = refImageDataStoreManager.refObjetoDetalhesJson.first()
                Log.d(TAG, "Detalhes do objeto de referência (refObjetoDetalhesJson) carregados (tamanho: ${refObjetoDetalhesJson.length}): ${refObjetoDetalhesJson.take(500)}...")


                val promptFinalParaApi = buildString {
                    append(finalPromptComContextoDasImagens)
                    if (refObjetoDetalhesJson.isNotBlank() && refObjetoDetalhesJson != "{}") {
                        appendLine()
                        appendLine()
                        append("--- INFORMAÇÕES MUITO IMPORTANTE DETALHES DE OBJETOS OU ROUPAS DA IMAGEN ---")
                        appendLine()
                        append(refObjetoDetalhesJson)
                        appendLine()
                        append("--- FIM DAS INFORMAÇÕES ADICIONAIS ---")
                    }
                }
                partsList.add(ImgTextPart("prompt_da_imagem: $promptFinalParaApi"))
                Log.i(TAG, "Prompt FINAL para API (primeiros 300 chars): ${promptFinalParaApi.take(300)}...")
                Log.d(TAG, "Prompt FINAL para API (COMPLETO):\n$promptFinalParaApi")


                for (imagemRef in imagensEfetivasParaApi) {
                    // <<< --- AJUSTE AQUI: Determinar o caminho da imagem a ser realmente carregada para a API --- >>>
                    var pathToLoadForApi = imagemRef.path
                    val originalFileNameForApi = File(imagemRef.path).name
                    if (originalFileNameForApi.startsWith("thumb_")) {
                        val cleanImageNameForApi = originalFileNameForApi.replaceFirst("thumb_", "img_")
                        val parentDirForApi = File(imagemRef.path).parentFile
                        if (parentDirForApi != null) {
                            val cleanImageFileForApi = File(parentDirForApi, cleanImageNameForApi)
                            if (cleanImageFileForApi.exists()) {
                                pathToLoadForApi = cleanImageFileForApi.absolutePath
                                Log.d(TAG, "API Upload: Usando imagem limpa '$cleanImageNameForApi' em vez de thumbnail '$originalFileNameForApi'.")
                            } else {
                                Log.w(TAG, "API Upload: Imagem limpa '$cleanImageNameForApi' não encontrada, usando thumbnail '$originalFileNameForApi' como fallback.")
                            }
                        }
                    }
                    // <<< --- FIM DO AJUSTE --- >>>

                    val imageFile = File(pathToLoadForApi) // Usa o pathToLoadForApi
                    if (imageFile.exists()) {
                        var imageBitmap: Bitmap? = null
                        try {
                            Log.d(TAG, "Processando imagem de referência para upload para API: ${imageFile.name}")
                            imageBitmap = BitmapUtils.decodeSampledBitmapFromUri(
                                context,
                                Uri.fromFile(imageFile),
                                larguraPreferida ?: DEFAULT_WIDTH,
                                alturaPreferida ?: DEFAULT_HEIGHT
                            )
                            if (imageBitmap != null) {
                                val outputStream = ByteArrayOutputStream()
                                val successCompress = imageBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
                                if (successCompress) {
                                    val imageBytes = outputStream.toByteArray()
                                    val encodedImage = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                                    val mimeType = "image/jpeg"
                                    partsList.add(ImgInlineDataPart(ImgInlineData(mimeType, encodedImage)))
                                    Log.d(TAG, "Imagem de referência '${imageFile.name}' adicionada à requisição (MIME: $mimeType, Tamanho Base64: ${encodedImage.length}).")
                                } else {
                                    Log.w(TAG, "Falha ao comprimir imagem de referência: $pathToLoadForApi")
                                }
                            } else {
                                Log.w(TAG, "Falha ao decodificar imagem de referência: $pathToLoadForApi")
                            }
                        } catch (e: OutOfMemoryError) {
                            Log.e(TAG, "OutOfMemoryError ao processar imagem de referência: $pathToLoadForApi.", e)
                        } catch (e: Exception) {
                            Log.e(TAG, "Erro ao processar arquivo de imagem de referência: $pathToLoadForApi", e)
                        } finally {
                            BitmapUtils.safeRecycle(imageBitmap, "gerarImagem (ref loop: $pathToLoadForApi)")
                        }
                    } else {
                        Log.w(TAG, "Arquivo de imagem de referência (após ajuste de prefixo) não encontrado: $pathToLoadForApi")
                    }
                }
                Log.d(TAG, "Total de partes na requisição: ${partsList.size} (1 texto + ${partsList.count { it is ImgInlineDataPart }} imagens)")


                if (partsList.none { it is ImgInlineDataPart } && imagensEfetivasParaApi.isNotEmpty()) {
                    Log.w(TAG, "Nenhuma imagem de referência pôde ser processada para upload, embora tenham sido fornecidas (ou carregadas globalmente). Isso pode acontecer se os arquivos não existirem ou não puderem ser lidos.")
                }

                val generationConfig = ImgGenerationConfig(
                    responseModalities = listOf("TEXT", "IMAGE"),
                    temperature = API_TEMPERATURE,
                    topK = API_TOP_K,
                    topP = API_TOP_P,
                    candidateCount = API_CANDIDATE_COUNT
                )
                Log.d(TAG, "Configuração de geração para API: $generationConfig")


                val request = ImgGeminiRequest(listOf(ImgContent(partsList)), generationConfig)
                Log.i(TAG, "Enviando request à API Gemini...")
                val response = ImgRetrofitClient.instance.generateContent(apiKey, request)
                Log.i(TAG, "Resposta da API recebida. Código: ${response.code()}, Sucesso: ${response.isSuccessful}")


                if (response.isSuccessful) {
                    val apiResponse = response.body()
                    val firstValidCandidate = apiResponse?.candidates?.firstOrNull {
                        it.content?.parts?.any { part -> part.inlineData?.data != null && part.inlineData.mimeType?.startsWith("image/") == true } == true
                    }
                    val base64ImageString = firstValidCandidate?.content?.parts?.firstOrNull { it.inlineData != null }?.inlineData?.data

                    if (base64ImageString.isNullOrEmpty()) {
                        val errorPart = apiResponse?.candidates?.firstOrNull()?.content?.parts?.firstOrNull { it.text != null }?.text
                        val apiErrorMsg = apiResponse?.error?.message
                        val detailedError = listOfNotNull(errorPart, apiErrorMsg).joinToString(" - ")
                        var errorMessage = "Imagem não foi gerada pela API. ${detailedError.ifEmpty { "Resposta vazia ou sem imagem." }}"
                        if (apiResponse == null && response.isSuccessful) {
                            errorMessage = "Imagem não foi gerada pela API. Resposta HTTP OK, mas corpo vazio."
                        }
                        Log.e(TAG, errorMessage)
                        apiResponse?.error?.let { Log.e(TAG, "Detalhes erro API: Code: ${it.code}, Status: ${it.status}, Message: ${it.message}") }
                        return@withContext Result.failure(Exception(errorMessage))
                    }
                    Log.d(TAG, "Imagem Base64 recebida da API (primeiros 50 chars): ${base64ImageString.take(50)}...")


                    apiBitmap = BitmapUtils.decodeBitmapFromBase64(
                        base64ImageString,
                        larguraPreferida ?: DEFAULT_WIDTH,
                        alturaPreferida ?: DEFAULT_HEIGHT
                    )
                    if (apiBitmap == null) {
                        Log.e(TAG, "Falha ao decodificar bitmap da resposta da API.")
                        return@withContext Result.failure(Exception("Falha ao decodificar bitmap da resposta da API."))
                    }
                    Log.d(TAG, "Bitmap decodificado da API. Dimensões: ${apiBitmap?.width}x${apiBitmap?.height}")


                    val caminhoImagem = saveGeneratedBitmap(
                        prefixo = cena,
                        apiBitmap = apiBitmap,
                        context = context,
                        projectDirName = projectDirName,
                        targetWidth = larguraPreferida,
                        targetHeight = alturaPreferida
                    )

                    if (caminhoImagem.isNullOrEmpty()) {
                        Log.e(TAG, "Falha ao salvar a imagem gerada localmente.")
                        return@withContext Result.failure(Exception("Falha ao salvar a imagem gerada."))
                    }
                    Log.i(TAG, "Imagem gerada e salva com sucesso em: $caminhoImagem")
                    Log.i(TAG, "--- FIM SUCESSO: GeminiImageApi.gerarImagem ---")
                    Result.success(caminhoImagem)
                } else {
                    var errorBodyString: String? = null
                    try {
                        errorBodyString = response.errorBody()?.string()
                    } catch (e: Exception) {
                        Log.w(TAG, "Falha ao ler o corpo do erro: ${e.message}")
                    }
                    var errorMessage = "Erro da API Gemini (Imagem): Código ${response.code()} - ${response.message()}"
                    if (!errorBodyString.isNullOrBlank()) {
                        errorMessage += ". Detalhes: ${errorBodyString.take(500)}"
                    }
                    Log.e(TAG, errorMessage)
                    Log.i(TAG, "--- FIM ERRO API: GeminiImageApi.gerarImagem ---")
                    Result.failure(Exception(errorMessage))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exceção não tratada durante a geração da imagem para cena '$cena'", e)
                Log.i(TAG, "--- FIM EXCEÇÃO: GeminiImageApi.gerarImagem ---")
                Result.failure(e)
            } finally {
                BitmapUtils.safeRecycle(apiBitmap, "gerarImagem (apiBitmap final)")
            }
        }
    }

    private suspend fun saveGeneratedBitmap(
        prefixo: String,
        apiBitmap: Bitmap,
        context: Context,
        projectDirName: String,
        targetWidth: Int?,
        targetHeight: Int?
    ): String? {
        var resizedBitmap: Bitmap? = null
        try {
            val finalWidth = targetWidth ?: DEFAULT_WIDTH
            val finalHeight = targetHeight ?: DEFAULT_HEIGHT
            Log.d(TAG, "saveGeneratedBitmap: Redimensionando bitmap da API de ${apiBitmap.width}x${apiBitmap.height} para ${finalWidth}x${finalHeight} (prefixo: $prefixo)")


            resizedBitmap = BitmapUtils.resizeWithTransparentBackground(apiBitmap, finalWidth, finalHeight)
            if (resizedBitmap == null) {
                Log.e(TAG, "Falha ao redimensionar bitmap da API para prefixo '$prefixo'.")
                return null
            }
            Log.d(TAG, "Bitmap redimensionado. Dimensões: ${resizedBitmap.width}x${resizedBitmap.height}")


            val saveFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            val quality = if (saveFormat == Bitmap.CompressFormat.WEBP_LOSSLESS) 100 else 90
            Log.d(TAG, "Salvando com formato: $saveFormat, qualidade: $quality")


            return BitmapUtils.saveBitmapToFile(
                context = context,
                bitmap = resizedBitmap,
                projectDirName = projectDirName,
                subDir = "gemini_generated_images",
                baseName = prefixo,
                format = saveFormat,
                quality = quality
            )
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar bitmap gerado (prefixo '$prefixo'): ${e.message}", e)
            return null
        } finally {
            BitmapUtils.safeRecycle(resizedBitmap, "saveGeneratedBitmap (resized for $prefixo)")
        }
    }
}