// File: euia/api/PixabayApiService.kt
package com.carlex.euia.api

import android.util.Log
import com.carlex.euia.BuildConfig
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.IOException
import java.util.concurrent.TimeUnit

// --- DATA CLASSES PARA IMAGENS (sem alterações) ---
data class PixabayImageResponse(
    @SerializedName("totalHits") val totalHits: Int,
    @SerializedName("hits") val images: List<PixabayImage>
)

data class PixabayImage(
    @SerializedName("id") val id: Long,
    @SerializedName("tags") val tags: String,
    @SerializedName("previewURL") val previewURL: String,
    @SerializedName("webformatURL") val webformatURL: String,
    @SerializedName("largeImageURL") val largeImageURL: String,
    @SerializedName("user") val user: String,
    @SerializedName("views") val views: Int,
    @SerializedName("downloads") val downloads: Int,
    @SerializedName("likes") val likes: Int
)

// <<< INÍCIO: NOVAS DATA CLASSES PARA VÍDEOS >>>
data class PixabayVideoResponse(
    @SerializedName("totalHits") val totalHits: Int,
    @SerializedName("hits") val videos: List<PixabayVideo>
)

data class PixabayVideo(
    @SerializedName("id") val id: Long,
    @SerializedName("tags") val tags: String,
    @SerializedName("duration") val duration: Int, // Duração em segundos
    @SerializedName("videos") val videoFiles: VideoFiles,
    @SerializedName("user") val user: String,
)

data class VideoFiles(
    // A API retorna vários tamanhos, vamos mapear os mais úteis
    @SerializedName("small") val small: VideoUrl,
    @SerializedName("medium") val medium: VideoUrl,
    @SerializedName("large") val large: VideoUrl,
    @SerializedName("tiny") val tiny: VideoUrl,
)

data class VideoUrl(
    @SerializedName("url") val url: String,
    @SerializedName("width") val width: Int,
    @SerializedName("height") val height: Int,
    @SerializedName("size") val size: Long,
    // Adicionamos a thumbnail, que está dentro de cada objeto de tamanho
    @SerializedName("thumbnail") val thumbnail: String
)
// <<< FIM: NOVAS DATA CLASSES PARA VÍDEOS >>>


/**
 * Interface Retrofit que define os endpoints da API Pixabay.
 */
interface PixabayApiService {
    @GET("api/")
    suspend fun searchImages(
        @Query("key") apiKey: String,
        @Query("q") query: String,
        @Query("lang") language: String = "pt",
        @Query("image_type") imageType: String = "photo",
        @Query("orientation") orientation: String = "vertical",
        @Query("safesearch") safeSearch: Boolean = true
    ): Response<PixabayImageResponse>

    // <<< NOVO ENDPOINT PARA VÍDEOS >>>
    @GET("api/videos/")
    suspend fun searchVideos(
        @Query("key") apiKey: String,
        @Query("q") query: String,
        //@Query("lang") language: String = "pt",
        //@Query("video_type") videoType: String = "film", // 'film' ou 'animation'
        //@Query("orientation") orientation: String = "vertical",
        //@Query("safesearch") safeSearch: Boolean = true
    ): Response<PixabayVideoResponse>
}

/**
 * Objeto singleton que gerencia a criação do cliente Retrofit.
 */
object PixabayApiClient {
    private const val BASE_URL = "https://pixabay.com/"
    private const val TAG = "PixabayApiClient"
    private val PIXABAY_API_KEY = "51209698-c37d8d8204bfd1fec1447c2a0" // Mantenha a mesma chave

    private val httpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor { message -> Log.v(TAG, message) }
        logging.setLevel(if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE)
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
    }

    private val apiService: PixabayApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(httpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PixabayApiService::class.java)
    }

    /**
     * Busca IMAGENS na API Pixabay.
     */
    suspend fun searchImages(query: String): Result<List<PixabayImage>> = withContext(Dispatchers.IO) {
        if (PIXABAY_API_KEY.isBlank() || PIXABAY_API_KEY == "SUA_CHAVE_API_AQUI") {
            return@withContext Result.failure(Exception("Chave da API Pixabay não configurada."))
        }
        try {
            val response = apiService.searchImages(apiKey = PIXABAY_API_KEY, query = query)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.images)
            } else {
                Result.failure(IOException("Erro na API de Imagens: ${response.code()} - ${response.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * <<< NOVA FUNÇÃO PARA BUSCAR VÍDEOS >>>
     * Função pública para buscar vídeos na API Pixabay.
     *
     * @param query O termo de busca para os vídeos.
     * @return Um [Result] que contém uma [List<PixabayVideo>] em caso de sucesso,
     * ou uma [Exception] em caso de falha.
     */
    suspend fun searchVideos(query: String): Result<List<PixabayVideo>> = withContext(Dispatchers.IO) {
        if (PIXABAY_API_KEY.isBlank() || PIXABAY_API_KEY == "SUA_CHAVE_API_AQUI") {
            Log.e(TAG, "A chave da API Pixabay não foi definida.")
            return@withContext Result.failure(Exception("Chave da API Pixabay não configurada."))
        }

        try {
            val response = apiService.searchVideos(apiKey = PIXABAY_API_KEY, query = query)
            if (response.isSuccessful && response.body() != null) {
                Log.i(TAG, "retornou ${response.body()!!.videos.toString()} .")
  
                Log.i(TAG, "Busca por vídeos sobre '$query' retornou ${response.body()!!.videos.size} resultados.")
                Result.success(response.body()!!.videos)
            } else {
                val errorMsg = "Erro na API de Vídeos: ${response.code()} - ${response.errorBody()?.string()}"
                Log.e(TAG, errorMsg)
                Result.failure(IOException(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exceção ao buscar vídeos na Pixabay: ${e.message}", e)
            Result.failure(e)
        }
    }
}