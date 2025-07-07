// File: euia/api/YouTubeUploadService.kt
package com.carlex.euia.api

import android.util.Log
import com.carlex.euia.BuildConfig
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import com.carlex.euia.managers.AppConfigManager
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Converter
import retrofit2.Response
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.io.File
import java.io.IOException
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit

// --- DATA CLASSES para a API do YouTube (sem alterações) ---

data class YouTubeVideoInsertRequest(
    @SerializedName("snippet") val snippet: YouTubeVideoSnippet,
    @SerializedName("status") val status: YouTubeVideoStatus
)

data class YouTubeVideoSnippet(
    @SerializedName("categoryId") val categoryId: String,
    @SerializedName("description") val description: String,
    @SerializedName("title") val title: String
)

data class YouTubeVideoStatus(
    @SerializedName("privacyStatus") val privacyStatus: String
)

data class YouTubeVideoInsertResponse(
    @SerializedName("id") val id: String,
    @SerializedName("snippet") val snippet: YouTubeVideoSnippet?,
    @SerializedName("status") val status: YouTubeVideoStatus?
)

data class YouTubeApiErrorResponse(
    @SerializedName("error") val error: YouTubeApiError
)

data class YouTubeApiError(
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String,
    @SerializedName("errors") val errors: List<YouTubeErrorDetail>?
)

data class YouTubeErrorDetail(
    @SerializedName("domain") val domain: String?,
    @SerializedName("reason") val reason: String?,
    @SerializedName("message") val message: String?
)

// --- INTERFACE RETROFIT ATUALIZADA ---
interface YouTubeApiService {
    @POST("upload/youtube/v3/videos?part=snippet%2Cstatus&uploadType=resumable")
    suspend fun initiateResumableUpload(
        @Query("key") apiKey: String,
        @Header("Authorization") authorization: String,
        @Header("Content-Type") contentType: String = "application/json; charset=UTF-8",
        @Body requestBody: YouTubeVideoInsertRequest
    ): Response<Void>

    @PUT
    suspend fun uploadVideoFile(
        @Url uploadUrl: String,
        @Header("Authorization") authorization: String,
        @Body videoFile: RequestBody
    ): Response<YouTubeVideoInsertResponse>
    
    // Função para enviar a thumbnail
    @POST("https://www.googleapis.com/upload/youtube/v3/thumbnails/set")
    suspend fun setThumbnail(
        @Query("key") apiKey: String,
        @Query("videoId") videoId: String,
        @Header("Authorization") authorization: String,
        @Body imageFile: RequestBody
    ): Response<Void>
}

// --- CLASSE UTILIÁRIA ATUALIZADA ---
object YouTubeUploadService {

    private const val TAG = "YouTubeUploadService"
    private const val BASE_URL = "https://www.googleapis.com/"
    private val YOUR_GOOGLE_API_KEY =  AppConfigManager.getString("gemini_API_KEY") ?: ""

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
            })
            .build()
    }

    private val gsonConverterFactory: GsonConverterFactory by lazy { GsonConverterFactory.create() }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(httpClient)
            .addConverterFactory(gsonConverterFactory)
            .build()
    }

    private val youtubeApiService: YouTubeApiService by lazy {
        retrofit.create(YouTubeApiService::class.java)
    }

    private val errorConverter: Converter<ResponseBody, YouTubeApiErrorResponse> by lazy {
        retrofit.responseBodyConverter(
            YouTubeApiErrorResponse::class.java,
            arrayOfNulls<Annotation>(0)
        )
    }

    /**
     * Orquestra o upload do ARQUIVO DE VÍDEO em duas etapas.
     * Retorna o ID do vídeo em caso de sucesso para ser usado no upload da thumbnail.
     */
    suspend fun uploadVideo(
        oauthAccessToken: String, videoFile: File, title: String, description: String, privacyStatus: String = "public"
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val initiationResponse = youtubeApiService.initiateResumableUpload(
                apiKey = YOUR_GOOGLE_API_KEY,
                authorization = "Bearer $oauthAccessToken",
                requestBody = YouTubeVideoInsertRequest(YouTubeVideoSnippet("22", description, title), YouTubeVideoStatus(privacyStatus))
            )

            if (!initiationResponse.isSuccessful) {
                val errorBody = initiationResponse.errorBody()?.string() ?: "Erro desconhecido"
                return@withContext Result.failure(IOException("Falha ao iniciar upload: HTTP ${initiationResponse.code()} - $errorBody"))
            }

            val uploadUrl = initiationResponse.headers()["Location"] ?: return@withContext Result.failure(IOException("Location header ausente na resposta de iniciação."))
            
            val videoUploadResult = uploadFileToGoogle(oauthAccessToken, uploadUrl, videoFile)
            if (videoUploadResult.isFailure) {
                return@withContext Result.failure(videoUploadResult.exceptionOrNull()!!)
            }
            
            return@withContext Result.success(videoUploadResult.getOrThrow().id)

        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    private suspend fun uploadFileToGoogle(
        oauthAccessToken: String, uploadUrl: String, videoFile: File
    ): Result<YouTubeVideoInsertResponse> = withContext(Dispatchers.IO) {
        try {
            val requestBody = videoFile.asRequestBody("video/*".toMediaType())
            val response = youtubeApiService.uploadVideoFile(uploadUrl, "Bearer $oauthAccessToken", requestBody)
            if (response.isSuccessful && response.body() != null) {
                return@withContext Result.success(response.body()!!)
            } else {
                 val errorBody = response.errorBody()?.string()
                 return@withContext Result.failure(IOException("Falha no upload do arquivo: HTTP ${response.code()} - $errorBody"))
            }
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }

    /**
     * Envia um arquivo de imagem como thumbnail para um vídeo já existente.
     */
    suspend fun setThumbnail(
    oauthAccessToken: String,
    videoId: String,
    thumbnailFile: File
): Result<Unit> = withContext(Dispatchers.IO) {
    if (!thumbnailFile.exists()) {
        return@withContext Result.failure(IOException("Arquivo da thumbnail não encontrado: ${thumbnailFile.absolutePath}"))
    }
    Log.d(TAG, "Iniciando upload da thumbnail para o videoId: $videoId")
    try {
        // Decodifica o arquivo WEBP para um Bitmap
        val bitmap = BitmapFactory.decodeFile(thumbnailFile.absolutePath)
        if (bitmap == null) {
            Log.e(TAG, "Falha ao decodificar o arquivo WEBP para Bitmap.")
            return@withContext Result.failure(IOException("Falha ao decodificar thumbnail WEBP."))
        }

        // Converte o bitmap para JPEG
        val outputStream = ByteArrayOutputStream()
        val compressSuccess = bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        if (!compressSuccess) {
            Log.e(TAG, "Falha ao comprimir bitmap para JPEG.")
            return@withContext Result.failure(IOException("Falha ao converter thumbnail para JPEG."))
        }
        val jpegByteArray = outputStream.toByteArray()
        val requestBody = jpegByteArray.toRequestBody("image/jpeg".toMediaType())

        // Envia para a API
        val response = youtubeApiService.setThumbnail(
            apiKey = YOUR_GOOGLE_API_KEY,
            videoId = videoId,
            authorization = "Bearer $oauthAccessToken",
            imageFile = requestBody
        )

        if (response.isSuccessful) {
            Log.i(TAG, "Thumbnail enviada com sucesso para o videoId: $videoId")
            return@withContext Result.success(Unit)
        } else {
            val errorBody = response.errorBody()?.string() ?: "Erro desconhecido"
            Log.e(TAG, "Falha ao enviar thumbnail: HTTP ${response.code()} - $errorBody")
            return@withContext Result.failure(IOException("Falha ao enviar thumbnail: ${response.code()} - $errorBody"))
        }
    } catch (e: Exception) {
        Log.e(TAG, "Exceção durante upload da thumbnail: ${e.message}", e)
        return@withContext Result.failure(e)
    }
}

}