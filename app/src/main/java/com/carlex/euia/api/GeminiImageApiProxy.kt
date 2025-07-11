// File: api/GeminiImageApiProxy.kt (Versão Final Autossuficiente e Corrigida)
package com.carlex.euia.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import retrofit2.http.Query
import android.util.Log
import com.carlex.euia.data.RefImageDataStoreManager
import com.carlex.euia.data.ImagemReferencia
import com.carlex.euia.data.VideoDataStoreManager
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import com.carlex.euia.utils.BitmapUtils
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

// --- DATA CLASSES PARA A REQUISIÇÃO/RESPOSTA DO PROXY ---
@Serializable
data class GerarImagemProxyRequest(
    val prompt: String,
    val imagens_base64: List<String>
)

@Serializable
data class GerarImagemProxyResponse(
    val status: String,
    val imagem_gerada_base64: String? = null,
    val detail: String? = null
)

// --- INTERFACE RETROFIT PARA O NOSSO SERVIÇO DE PROXY ---
private interface GeminiProxyApiService {
    @POST("proxy/gerar_imagem")
    suspend fun gerarImagem(
        @Query("cena") cena: String,
        @Body request: GerarImagemProxyRequest
    ): Response<GerarImagemProxyResponse>
}



object GeminiImageApiProxy {

    private const val TAG = "GeminiImageApi"
    private const val DEFAULT_WIDTH = 720
    private const val DEFAULT_HEIGHT = 1280

    private val kotlinJsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    // --- INSTÂNCIA PRIVADA DO RETROFIT PARA O PROXY ---
    private val proxyService: GeminiProxyApiService by lazy {
        // !!! AJUSTE O IP CONFORME NECESSÁRIO !!!
        val baseUrl = "http://127.0.0.1:8000/"

        val loggingInterceptor = HttpLoggingInterceptor { message ->
            Log.v(TAG, "[ProxyHttp] $message")
        }.apply {
            level = HttpLoggingInterceptor.Level.NONE
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
            
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(Json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GeminiProxyApiService::class.java)
    }

    suspend fun gerarImagem(
        cena: String,
        prompt: String,
        context: Context,
        imagensParaUpload: List<ImagemReferencia>
    ): Result<String> {
        Log.i(TAG, "--- INÍCIO: GeminiImageApi.gerarImagem (Modo Proxy Autossuficiente) ---")
        
        return withContext(Dispatchers.IO) {
            var apiBitmap: Bitmap? = null
            try {
                // ETAPA 1: PREPARAÇÃO DE DADOS
                val videoPreferencesManager = VideoPreferencesDataStoreManager(context)
                val projectDirName = videoPreferencesManager.videoProjectDir.first()
                val larguraPreferida = videoPreferencesManager.videoLargura.first()
                val alturaPreferida = videoPreferencesManager.videoAltura.first()

                var imagensEfetivasParaApi = imagensParaUpload
                if (imagensParaUpload.isEmpty()) {
                    val videoDataStoreManager = VideoDataStoreManager(context)
                    val globalImagesJson = videoDataStoreManager.imagensReferenciaJson.first()
                    if (globalImagesJson.isNotBlank() && globalImagesJson != "[]") {
                        try {
                            imagensEfetivasParaApi = kotlinJsonParser.decodeFromString(ListSerializer(ImagemReferencia.serializer()), globalImagesJson)
                        } catch (e: Exception) { Log.e(TAG, "Falha ao desserializar lista global de imagens: ${e.message}", e) }
                    }
                }

                // --- CORREÇÃO AQUI: Passando o 'context' para as funções auxiliares ---
                val promptFinalParaApi = buildComplexPrompt(context, prompt, imagensEfetivasParaApi)
                val imagensBase64 = processImagesToBase64(context, imagensEfetivasParaApi, larguraPreferida, alturaPreferida)
                // --- FIM DA CORREÇÃO ---
                Log.d(TAG, "Dados preparados. Prompt final e ${imagensBase64.size} imagens em Base64.")

                // ETAPA 2: CHAMADA AO PROXY
                Log.i(TAG, "Enviando requisição ao nosso servidor proxy...")
                val proxyRequest = GerarImagemProxyRequest(
                    prompt = promptFinalParaApi,
                    imagens_base64 = imagensBase64
                )
                
                val response = proxyService.gerarImagem(cena, proxyRequest)
                Log.i(TAG, "Resposta do servidor proxy recebida. Código: ${response.code()}, Sucesso: ${response.isSuccessful}")

                // ETAPA 3: PROCESSAMENTO DA RESPOSTA DO PROXY
                if (response.isSuccessful && response.body()?.status == "sucesso") {
                    val base64ImageString = response.body()?.imagem_gerada_base64
                    
                    if (base64ImageString.isNullOrBlank()) {
                        return@withContext Result.failure(Exception("Proxy retornou sucesso, mas sem dados de imagem."))
                    }
                    
                    apiBitmap = BitmapUtils.decodeBitmapFromBase64(base64ImageString, larguraPreferida, alturaPreferida)
                    
                    if (apiBitmap == null) {
                        return@withContext Result.failure(Exception("Falha ao decodificar bitmap da resposta do proxy."))
                    }

                    val caminhoImagem = saveGeneratedBitmap(
                        prefixo = cena, apiBitmap = apiBitmap, context = context,
                        projectDirName = projectDirName, targetWidth = larguraPreferida, targetHeight = alturaPreferida
                    )
                    
                    if (caminhoImagem.isNullOrEmpty()) {
                        return@withContext Result.failure(Exception("Falha ao salvar a imagem gerada localmente."))
                    }
                    
                    Result.success(caminhoImagem)

                } else {
                    val errorBody = response.body()?.detail ?: response.errorBody()?.string() ?: "Erro desconhecido"
                    val errorMessage = "Erro do servidor proxy (Código ${response.code()}): ${errorBody.take(500)}"
                    Result.failure(Exception(errorMessage))
                }

            } catch (e: Exception) {
                Log.e(TAG, "Exceção não tratada durante a geração da imagem via proxy para cena '$cena'", e)
                Result.failure(e)
            } finally {
                BitmapUtils.safeRecycle(apiBitmap, "gerarImagem_proxy (apiBitmap final)")
            }
        }
    }

    // --- FUNÇÕES AUXILIARES CORRIGIDAS ---

    private suspend fun buildComplexPrompt(context: Context, basePrompt: String, imagens: List<ImagemReferencia>): String {
        val promptLimpo = basePrompt.replace("\"", "")
        val allImageContextText = StringBuilder()
        
        if (imagens.isNotEmpty()) {
            allImageContextText.appendLine("\n\n--- Contexto Adicional das Imagens de Referência ---")
            imagens.forEachIndexed { index, imagemRef ->
                var pathToLoadDesc = imagemRef.path
                val originalFileNameDesc = File(imagemRef.path).name
                if (originalFileNameDesc.startsWith("thumb_")) {
                    val cleanImageNameDesc = originalFileNameDesc.replaceFirst("thumb_", "img_")
                    val parentDirDesc = File(imagemRef.path).parentFile
                    if (parentDirDesc != null) {
                        val cleanImageFileDesc = File(parentDirDesc, cleanImageNameDesc)
                        if (cleanImageFileDesc.exists()) pathToLoadDesc = cleanImageFileDesc.absolutePath
                    }
                }
                allImageContextText.appendLine("Imagem de Referência ${index + 1} (Arquivo base: ${File(pathToLoadDesc).name}):")
                if (imagemRef.descricao.isNotBlank()) {
                    allImageContextText.appendLine("  Descrição: \"${imagemRef.descricao}\"")
                }
                allImageContextText.appendLine("  Tipo Original: ${if(imagemRef.pathVideo != null) "Vídeo" else "Imagem Estática"}")
                allImageContextText.appendLine()
            }
            allImageContextText.appendLine("--- Fim do Contexto Adicional ---")
        }
        
        val finalPromptComContextoDasImagens = promptLimpo + allImageContextText.toString()
        
        // --- CORREÇÃO AQUI: Instancia o DataStoreManager usando o context recebido ---
        val refImageDataStoreManager = RefImageDataStoreManager(context)
        val refObjetoDetalhesJson = refImageDataStoreManager.refObjetoDetalhesJson.first()

        return buildString {
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
    }

    private suspend fun processImagesToBase64(context: Context, imagens: List<ImagemReferencia>, reqWidth: Int?, reqHeight: Int?): List<String> {
        val base64List = mutableListOf<String>()
        val finalWidth = reqWidth ?: DEFAULT_WIDTH
        val finalHeight = reqHeight ?: DEFAULT_HEIGHT

        for (imagemRef in imagens) {
            var pathToLoad = imagemRef.path
            val originalFileName = File(imagemRef.path).name
            if (originalFileName.startsWith("thumb_")) {
                val cleanImageName = originalFileName.replaceFirst("thumb_", "img_")
                val parentDir = File(imagemRef.path).parentFile
                if (parentDir != null && File(parentDir, cleanImageName).exists()) {
                    pathToLoad = File(parentDir, cleanImageName).absolutePath
                }
            }

            val imageFile = File(pathToLoad)
            if (imageFile.exists()) {
                var imageBitmap: Bitmap? = null
                try {
                    imageBitmap = BitmapUtils.decodeSampledBitmapFromUri(context, Uri.fromFile(imageFile), finalWidth, finalHeight)
                    if (imageBitmap != null) {
                        val outputStream = ByteArrayOutputStream()
                        imageBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 85, outputStream)
                        val imageBytes = outputStream.toByteArray()
                        base64List.add(Base64.encodeToString(imageBytes, Base64.NO_WRAP))
                    }
                } catch(e: Exception) {
                    Log.e(TAG, "Erro ao processar imagem para Base64: ${imageFile.absolutePath}", e)
                }
                finally {
                    BitmapUtils.safeRecycle(imageBitmap, "processImagesToBase64 loop")
                }
            }
        }
        return base64List
    }

    private suspend fun saveGeneratedBitmap(
        prefixo: String, apiBitmap: Bitmap, context: Context, projectDirName: String,
        targetWidth: Int?, targetHeight: Int?
    ): String? {
        var resizedBitmap: Bitmap? = null
        try {
            val finalWidth = targetWidth ?: DEFAULT_WIDTH
            val finalHeight = targetHeight ?: DEFAULT_HEIGHT
            
            resizedBitmap = BitmapUtils.resizeWithTransparentBackground(apiBitmap, finalWidth, finalHeight)
            if (resizedBitmap == null) {
                Log.e(TAG, "Falha ao redimensionar bitmap da API para prefixo '$prefixo'.")
                return null
            }
            
            val saveFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            val quality = if (saveFormat == Bitmap.CompressFormat.WEBP_LOSSLESS) 100 else 90

            return BitmapUtils.saveBitmapToFile(
                context = context,
                bitmap = resizedBitmap,
                projectDirName = projectDirName,
                subDir = "gemini_generated_images",
                baseName = prefixo,
                format = saveFormat,
                quality = 65
            )
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao salvar bitmap gerado (prefixo '$prefixo'): ${e.message}", e)
            return null
        } finally {
            BitmapUtils.safeRecycle(resizedBitmap, "saveGeneratedBitmap (resized for $prefixo)")
        }
    }
}