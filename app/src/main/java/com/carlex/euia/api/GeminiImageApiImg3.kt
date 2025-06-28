// File: api/GeminiImageApiImg3.kt
package com.carlex.euia.api

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.carlex.euia.data.ImagemReferencia
import android.util.Log
import com.carlex.euia.data.AudioDataStoreManager
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import com.carlex.euia.managers.GerenciadorDeChavesApi
import com.carlex.euia.managers.NenhumaChaveApiDisponivelException
import com.carlex.euia.utils.BitmapUtils
import com.google.firebase.firestore.FirebaseFirestore
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.text.SimpleDateFormat
import java.util.*
import java.io.*
import retrofit2.http.POST
import retrofit2.http.Query
import java.io.IOException
import java.util.concurrent.TimeUnit

// --- Data Classes (Modelos para Requisição e Resposta API Imagen 3) --- (INALTEIRADOS)
@Serializable data class GeminiImageRequest( val instances: List<InstanceBody>, val parameters: ImageParametersBody )
@Serializable data class InstanceBody( val prompt: String )
@Serializable data class ImageParametersBody( val sampleCount: Int = 1, val aspectRatio: String = "9:16" )
@Serializable data class GeminiImageResponse( val predictions: List<PredictionBody> )
@Serializable data class PredictionBody( @SerialName("bytesBase64Encoded") val bytesBase64Encoded: String? = null )
@Serializable data class GeminiApiErrorResponse( val error: ApiErrorDetail )
@Serializable data class ApiErrorDetail( val code: Int, val message: String, val status: String )

// --- Interface do Serviço Retrofit --- (MANTIDA COMO A ORIGINAL)
internal interface GeminiApiServiceImg3 {
    // Usando o endpoint correto da API generativelanguage
    @POST("v1beta/models/imagen-3.0-generate-002:predict")
    suspend fun generateImage(
        @Query("key") apiKey: String, // A chave é um parâmetro de query
        @Body requestBody: GeminiImageRequest
    ): Response<GeminiImageResponse>
}

object GeminiImageApiImg3 {
    private const val TAG = "GeminiImageApiImg3"
    // URL base correta para o endpoint acima
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"
    private const val TIPO_DE_CHAVE = "img"
    private const val MAX_TENTATIVAS = 10
    
    private const val DEFAULT_SAVE_WIDTH = 720
    private const val DEFAULT_SAVE_HEIGHT = 1280
    private const val API_ASPECT_RATIO_INTERNAL: String = "9:16"

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    // <<<<< INSTANCIAÇÃO INTERNA DAS DEPENDÊNCIAS >>>>>
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val gerenciadorDeChaves: GerenciadorDeChavesApi by lazy { GerenciadorDeChavesApi(firestore) }

    // Cliente Retrofit (única instância, como no seu original)
    @Volatile
    private var apiServiceInstance: GeminiApiServiceImg3? = null

    private fun getService(): GeminiApiServiceImg3 {
        return apiServiceInstance ?: synchronized(this) {
            apiServiceInstance ?: buildRetrofitService().also { apiServiceInstance = it }
        }
    }

    private fun buildRetrofitService(): GeminiApiServiceImg3 {
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

    suspend fun gerarImagem(
        cena: String,
        prompt: String,
        context: Context,
        imagensParaUpload: List<ImagemReferencia>
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            var tentativas = 0
            var apiBitmap: Bitmap? = null

            while(tentativas < MAX_TENTATIVAS) {
                var chaveAtual: String? = null
                try {
                    // 1. OBTER CHAVE
                    chaveAtual = gerenciadorDeChaves.getChave(TIPO_DE_CHAVE)
                    Log.d(TAG, "Tentativa ${tentativas + 1}/$MAX_TENTATIVAS ($TIPO_DE_CHAVE): Chave '${chaveAtual.takeLast(4)}'")

                    // 2. PREPARAR DADOS
                    val (_, request) = prepararDadosParaApi(context, prompt)

                    // 3. CHAMAR API
                    Log.i(TAG, "Enviando request à API Imagen 3 (Retrofit)...")
                    val response = getService().generateImage(chaveAtual, request)
                    Log.i(TAG, "Resposta da API recebida. Código: ${response.code()}, Sucesso: ${response.isSuccessful}")

                    // 4. TRATAR RESPOSTA
                    if (response.isSuccessful) {
                        val responseBody = response.body()
                        val base64Image = responseBody?.predictions?.firstOrNull()?.bytesBase64Encoded

                        if (base64Image.isNullOrEmpty()) {
                            throw Exception("API retornou sucesso, mas sem dados de imagem. Predictions: ${responseBody?.predictions}")
                        }
                        
                        // SUCESSO!
                        Log.i(TAG, "SUCESSO na tentativa ${tentativas + 1} com a chave '${chaveAtual.takeLast(4)}'.")
                        gerenciadorDeChaves.setChaveEmUso(chaveAtual, TIPO_DE_CHAVE)
                        
                        apiBitmap = BitmapUtils.decodeBitmapFromBase64(base64Image, DEFAULT_SAVE_WIDTH, DEFAULT_SAVE_HEIGHT)
                            ?: throw Exception("Falha ao decodificar bitmap da resposta da API.")

                        //val cena = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(Date()).toString()
                          
                        val caminhoImagem = saveGeneratedBitmap(cena, apiBitmap, context)
                            ?: throw Exception("Falha ao salvar a imagem gerada localmente.")
                        
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
                             return@withContext Result.failure(Exception("Máx. de tentativas ($MAX_TENTATIVAS) atingido."))
                        }
                    } else {
                        val errorBody = response.errorBody()?.string() ?: "Erro desconhecido"
                        response.errorBody()?.close()
                        throw IOException("Erro da API (${response.code()}): $errorBody")
                    }

                } catch (e: NenhumaChaveApiDisponivelException) {
                    Log.e(TAG, "Não há chaves disponíveis para o tipo '$TIPO_DE_CHAVE'.", e)
                    return@withContext Result.failure(e)
                } catch (e: Exception) {
                    Log.e(TAG, "Erro inesperado ($TIPO_DE_CHAVE) na tentativa ${tentativas + 1}", e)
                    if (chaveAtual != null) {
                       gerenciadorDeChaves.setChaveBloqueada(chaveAtual, TIPO_DE_CHAVE)
                    }
                    return@withContext Result.failure(e)
                } finally {
                    BitmapUtils.safeRecycle(apiBitmap, "gerarImagem_Imagen3 (apiBitmap final)")
                }
            } // Fim do while

            Result.failure(Exception("Falha ao gerar imagem para o tipo '$TIPO_DE_CHAVE' após $MAX_TENTATIVAS tentativas."))
        }
    }

    private suspend fun prepararDadosParaApi(context: Context, prompt: String): Pair<String, GeminiImageRequest> {
        val audioDataStoreManager = AudioDataStoreManager(context)
        val videoExtrasFromDataStore = audioDataStoreManager.videoExtrasAudio.first()

        val promptFinalParaApi = buildString {
            append(prompt.replace("\"", ""))
            if (videoExtrasFromDataStore.isNotBlank()) {
                appendLine()
                appendLine("--- INFORMAÇÕES MUITO IMPORTANTE DETALHES DE OBJETOS OU ROUPAS DA IMAGEN ---")
                appendLine()
                append(videoExtrasFromDataStore)
                appendLine()
                append("--- FIM DAS INFORMAÇÕES ADICIONAIS ---")
            }
        }

        val request = GeminiImageRequest(
            instances = listOf(InstanceBody(prompt = promptFinalParaApi)),
            parameters = ImageParametersBody(
                sampleCount = 1,
                aspectRatio = API_ASPECT_RATIO_INTERNAL
            )
        )
        
        return Pair(promptFinalParaApi, request)
    }

    private suspend fun saveGeneratedBitmap(prefixo: String, apiBitmap: Bitmap, context: Context): String? {
        val videoPreferencesManager = VideoPreferencesDataStoreManager(context)
        val projectDirName = videoPreferencesManager.videoProjectDir.first()
        val targetSaveWidth = videoPreferencesManager.videoLargura.first()
        val targetSaveHeight = videoPreferencesManager.videoAltura.first()
        
        var resizedBitmap: Bitmap? = null
        try {
            val finalSaveWidth = targetSaveWidth ?: DEFAULT_SAVE_WIDTH
            val finalSaveHeight = targetSaveHeight ?: DEFAULT_SAVE_HEIGHT

            resizedBitmap = BitmapUtils.resizeWithTransparentBackground(apiBitmap, finalSaveWidth, finalSaveHeight)
                ?: return null

            val saveFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            val quality = if (saveFormat == Bitmap.CompressFormat.WEBP_LOSSLESS) 100 else 95

            return BitmapUtils.saveBitmapToFile(
                context = context, bitmap = resizedBitmap, projectDirName = projectDirName,
                subDir = "gemini_img3_generated_images", baseName = prefixo,
                format = saveFormat, quality = quality
            )
        } finally {
            BitmapUtils.safeRecycle(resizedBitmap, "saveGeneratedBitmap_Imagen3 (resized for $prefixo)")
        }
    }
}