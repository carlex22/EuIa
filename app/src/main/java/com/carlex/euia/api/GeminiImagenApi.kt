// File: euia/api/GeminiImagenApi.kt
package com.carlex.euia.api

import android.app.Application
import android.content.Context
import android.graphics.Bitmap // Mantido para outras funções, mas não para compressão direta
import android.net.Uri
import com.carlex.euia.data.*
import android.os.Build
import android.util.Base64
import android.util.Log
import com.carlex.euia.data.ImagemReferencia
import com.carlex.euia.data.RefImageDataStoreManager
import com.carlex.euia.data.VideoDataStoreManager
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import com.carlex.euia.managers.GerenciadorDeChavesApi
import com.carlex.euia.managers.NenhumaChaveApiDisponivelException
import com.carlex.euia.utils.* // Mantido para ajustarCaminhoThumbnail
import com.carlex.euia.viewmodel.*
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
     @POST("v1beta/models/gemini-2.0-flash-exp:generateContent")
     // @POST("v1beta/models/gemini-2.0-flash-preview-image-generation:generateContent")
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
    
    // DEFAULT_WIDTH/HEIGHT ainda são relevantes para `saveGeneratedBitmap`, mas não para a entrada da API.
    private const val DEFAULT_WIDTH = 720 
    private const val DEFAULT_HEIGHT = 1280

    private val API_TEMPERATURE: Float? = 2.0f
    private val API_TOP_K: Int? = 15
    private val API_TOP_P: Float? = 0.9f
    private val API_CANDIDATE_COUNT: Int? = 1

    private val kotlinJsonParser = Json { ignoreUnknownKeys = true; isLenient = true }
    
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val gerenciadorDeChaves: GerenciadorDeChavesApi by lazy { GerenciadorDeChavesApi(firestore) }

    
        
    suspend fun gerarImagem(
        cena: String,
        prompt: String,
        context: Context,
        imagensParaUpload: List<ImagemReferencia> // Continua recebendo ImagemReferencia
    ): Result<String> {
        val authViewModel = AuthViewModel(context.applicationContext as Application)
        var creditsDeducted = false
        
        var perguntafim = "Voce esta ajundando uma equipe multitarefa resposavel pela criacao dos videos de maiores sucesso na interner, em anexo voce recebeu um json com todas as informacoes sobre este projeto, estude, depois comprender o projeto sua tarefa sera fazer: ---> $prompt"
       
        return withContext(Dispatchers.IO) {
            try {
                // ETAPA 1: VERIFICAR E DEDUZIR CRÉDITOS ANTES DE QUALQUER COISA
                val deductionResult = authViewModel.checkAndDeductCredits(TaskType.IMAGE)
                if (deductionResult.isFailure) {
                    return@withContext Result.failure(deductionResult.exceptionOrNull()!!)
                }
                creditsDeducted = true
                Log.i(TAG, "Créditos (${TaskType.IMAGE.cost}) deduzidos para API Standard. Prosseguindo.")

                // ETAPA 2: LÓGICA DE GERAÇÃO COM TENTATIVAS DINÂMICAS
                val keyCount = try {
                    firestore.collection("chaves_api_pool").get().await().size()
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao obter contagem de chaves, usando fallback 10.", e)
                    10 // Fallback
                }
                val MAX_TENTATIVAS = if (keyCount > 0) keyCount else 10

                var tentativas = 0
                while (tentativas < MAX_TENTATIVAS) {
                    var chaveAtual: String? = null
                    try {
                        chaveAtual = gerenciadorDeChaves.getChave(TIPO_DE_CHAVE)
                        Log.d(TAG, "Tentativa ${tentativas + 1}/$MAX_TENTATIVAS ($TIPO_DE_CHAVE): Chave '${chaveAtual.takeLast(4)}'")

                        // <<<<< ALTERAÇÃO AQUI: Chamar prepararDadosParaApi com o `context` >>>>>
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

                            Log.i(TAG, "SUCESSO na tentativa ${tentativas + 1} ($TIPO_DE_CHAVE) com a chave '${chaveAtual.takeLast(4)}'.")
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

    // <<<<< ALTERAÇÃO AQUI: Mudança na lógica de preparação de dados >>>>>
    private suspend fun prepararDadosParaApi(
        context: Context, // `context` é necessário aqui
        basePrompt: String,
        imagensParaUpload: List<ImagemReferencia> // Recebe ImagemReferencia diretamente
    ): Pair<String, List<ImgPart>> {
        val videoPreferencesManager = VideoPreferencesDataStoreManager(context)
        // Largura/Altura preferidas não são mais usadas para *entrada* da imagem, apenas para a saída gerada
        // val larguraPreferida = videoPreferencesManager.videoLargura.first()
        // val alturaPreferida = videoPreferencesManager.videoAltura.first()

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

        // <<<<< ALTERAÇÃO AQUI: LER BYTES DIRETAMENTE DO ARQUIVO >>>>>
        for (imagemRef in imagensEfetivas) {
            val pathToLoad = ajustarCaminhoThumbnail(imagemRef.path)
            val imageFile = File(pathToLoad)
            if (imageFile.exists()) {
                try {
                    val imageBytes = withContext(Dispatchers.IO) { imageFile.readBytes() } // Lê os bytes brutos
                    val encodedImage = Base64.encodeToString(imageBytes, Base64.NO_WRAP) // Codifica para Base64 sem quebras de linha

                    val mimeType = when (imageFile.extension.lowercase()) { // Determina o MIME type pela extensão
                        "webp" -> "image/webp"
                        "jpg", "jpeg" -> "image/jpeg"
                        "png" -> "image/png"
                        else -> {
                            Log.w(TAG, "Formato de imagem desconhecido para ${imageFile.name}. Usando image/jpeg como fallback.")
                            "image/jpeg" 
                        }
                    }
                    partsList.add(ImgInlineDataPart(ImgInlineData(mimeType, encodedImage)))
                    Log.d(TAG, "Imagem de referência '${imageFile.name}' (${mimeType}) codificada para Base64 (tamanho Base64: ${encodedImage.length}).")
                } catch (e: IOException) {
                    Log.e(TAG, "Erro de I/O ao ler arquivo de imagem de referência: $pathToLoad", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Erro inesperado ao codificar imagem de referência para Base64: $pathToLoad", e)
                }
            } else {
                Log.w(TAG, "Arquivo de imagem de referência não encontrado: $pathToLoad. Pulando.")
            }
        }
        // <<<<< FIM DA ALTERAÇÃO >>>>>
        
        return Pair(promptFinal, partsList)
    }

    private suspend fun buildComplexPrompt(basePrompt: String, imagens: List<ImagemReferencia>, context: Context): String {
        var promptLimpo = basePrompt.replace("\"", "")
        var allImageContextText = StringBuilder()
        
        allImageContextText.append(promptLimpo!!)
        
        val refImageDataStoreManager = RefImageDataStoreManager(context)
        val refObjetoDetalhesJson = refImageDataStoreManager.refObjetoDetalhesJson.first()


        allImageContextText.appendLine()
                .appendLine()
                .append("--- INFORMAÇÕES MUITO IMPORTANTE DETALHES Sobre o projeto ---") // Comentei esta linha em outra revisão, mantenha o que você preferir
                .appendLine()
                .append(refObjetoDetalhesJson)
                .appendLine()
                .append("--- FIM DAS INFORMAÇÕES ADICIONAIS ---") // Comentei esta linha em outra revisão, mantenha o que você preferir
        return allImageContextText.toString()
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
            
            // Lógica para manter proporção que você já tinha:
            if (finalWidth > finalHeight)
                finalWidth = (finalWidth).toInt()
            else
                finalHeight = (finalHeight).toInt()
            
            // Aqui ainda é necessário redimensionar, pois a API retorna um bitmap que pode não ter as dimensões desejadas
            resizedBitmap = BitmapUtils.resizeWithTransparentBackground(apiBitmap, finalWidth, finalHeight)
                ?: return null

            val saveFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSLESS else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            
            return BitmapUtils.saveBitmapToFile(context, resizedBitmap, projectDirName, "gemini_generated_images", cena, saveFormat, 65)
        } finally {
            BitmapUtils.safeRecycle(resizedBitmap, "saveGeneratedBitmap (resized)")
        }
    }
}