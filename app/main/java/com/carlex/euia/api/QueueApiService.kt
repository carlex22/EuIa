// File: euia/api/QueueApiService.kt
package com.carlex.euia.api

import android.util.Log // Import para Log do Android
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

// TAG para os logs manuais neste arquivo
private const val TAG_QUEUE_API_CLIENT = "QueueApiClient"

// Data class para a resposta do endpoint /status_requisicao
@Serializable
data class QueueStatusResponse(
    @SerialName("status") val status: String,
    @SerialName("posicao_fila_global") val posicaoFilaGlobal: Int? = null,
    @SerialName("total_na_fila_global") val totalNaFilaGlobal: Int? = null,
    @SerialName("posicao_fila_usuario") val posicaoFilaUsuario: Int? = null,
    @SerialName("total_na_fila_usuario") val totalNaFilaUsuario: Int? = null,
    @SerialName("mensagem") val mensagem: String? = null
)

// Interface Retrofit que define os endpoints da sua API de fila
interface QueueApiService {

    @GET("enfileirar")
    suspend fun enqueueRequest(
        @Query("user_id") userId: String,
        @Query("req_id") requestId: String
    ): Response<Unit>

    @GET("status_requisicao")
    suspend fun checkRequestStatus(
        @Query("user_id") userId: String,
        @Query("req_id") requestId: String
    ): Response<QueueStatusResponse>

    @GET("confirmar_execucao")
    suspend fun confirmExecution(
        @Query("user_id") userId: String,
        @Query("req_id") requestId: String
    ): Response<Unit>
}


// Objeto Singleton para criar e fornecer a instância do serviço Retrofit para a API de fila
object QueueApiClient {
    // ===================================================================================
    // !!! ATENÇÃO: AJUSTE O IP PARA O ENDEREÇO LOCAL DA SUA MÁQUINA !!!
    // Exemplo: "http://192.168.0.15:8000/"
    // Use `ifconfig` (no Termux) ou `ipconfig` (no Windows) para encontrar seu IP.
    // ===================================================================================
    private const val BASE_URL = "http://127.0.0.1:8000/"

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // --- LOG DE DEPURAÇÃO PRINCIPAL VIA OKHTTP INTERCEPTOR ---
    private val okHttpClient: OkHttpClient by lazy {
        Log.d(TAG_QUEUE_API_CLIENT, "Criando instância do OkHttpClient para a API de Fila.")
        
        // O Interceptor é a melhor forma de logar as chamadas de rede.
        // Ele mostrará a URL, método, cabeçalhos e corpos de requisição/resposta.
        val loggingInterceptor = HttpLoggingInterceptor { message ->
            // Direciona a saída do interceptor para o Logcat do Android com a nossa tag.
            Log.v(TAG_QUEUE_API_CLIENT, message)
        }.apply {
            // Level.BODY é o mais detalhado, perfeito para depuração.
            // Para produção, considere mudar para Level.BASIC ou Level.NONE.
            level = HttpLoggingInterceptor.Level.BODY
        }

        OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor) // Adiciona o interceptor de log
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    // --- FIM DO LOG DE DEPURAÇÃO ---

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