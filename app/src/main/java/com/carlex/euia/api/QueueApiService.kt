// File: euia/api/QueueApiService.kt (Versão Final Robusta)
package com.carlex.euia.api

import android.util.Log
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

private const val TAG_QUEUE_API_CLIENT = "QueueApiClient"

// --- DATA CLASSES FLEXÍVEIS PARA A API ---

@Serializable
data class EnqueueResponse(
    // Tornados opcionais para não quebrar a desserialização em caso de erro
    @SerialName("status") val status: String? = null,
    @SerialName("req_id") val requestId: String? = null,
    @SerialName("posicao_atual") val posicaoAtual: Int? = null,
    // Campo para capturar a mensagem de erro padrão do FastAPI (ex: {"detail": "..."})
    @SerialName("detail") val detail: String? = null
)

@Serializable
data class QueueStatusResponse(
    @SerialName("status") val status: String,
    @SerialName("posicao_fila") val posicaoFila: Int? = null,
    // Adicionado para receber a mensagem de status formatada do backend
    @SerialName("mensagem") val mensagem: String? = null
)




// --- INTERFACE RETROFIT ATUALIZADA ---
interface QueueApiService {

    @GET("fila/processando")
    suspend fun markAsProcessing(
        @Query("req_id") requestId: String,
        @Query("tipo_da_fila") queueType: String
    ): Response<Unit>


    @GET("fila/enfileirar")
    suspend fun enqueueRequest(
        @Query("req_id") requestId: String,
        @Query("tipo_da_fila") queueType: String 
    ): Response<EnqueueResponse>

    @GET("fila/status")
    suspend fun checkRequestStatus(
        @Query("req_id") requestId: String,
        @Query("tipo_da_fila") queueType: String 
    ): Response<QueueStatusResponse>

    @GET("fila/confirmar")
    suspend fun confirmExecution(
        @Query("req_id") requestId: String,
        @Query("tipo_da_fila") queueType: String 
    ): Response<Unit>
}

// --- CLIENTE API ---
object QueueApiClient {
    // !!! AJUSTE O IP CONFORME NECESSÁRIO !!!
    private const val BASE_URL = "http://127.0.0.1:8000/"

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val okHttpClient: OkHttpClient by lazy {
        Log.d(TAG_QUEUE_API_CLIENT, "Criando instância do OkHttpClient para a API de Fila.")
        val loggingInterceptor = HttpLoggingInterceptor { message ->
            Log.v(TAG_QUEUE_API_CLIENT, message)
        }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(165, TimeUnit.SECONDS)
            .readTimeout(125, TimeUnit.SECONDS)
            .writeTimeout(130, TimeUnit.SECONDS)
            .build()
    }

    val instance: QueueApiService by lazy {
        Log.d(TAG_QUEUE_API_CLIENT, "Criando instância do serviço Retrofit (QueueApiService) com base URL: $BASE_URL")
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(jsonParser.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(QueueApiService::class.java)
    }
}