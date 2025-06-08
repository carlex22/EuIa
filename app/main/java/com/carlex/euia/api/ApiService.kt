package com.carlex.euia.api

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {
    // Para o método generate()
    @GET("tts")
    suspend fun generateAudio(
        @Query("t") text: String,
        @Query("r") r: Int = 14,
        @Query("v") voice: String,
        @Query("p") p: Int = 0,
        @Query("s") s: String = "cheerful"
    ): Response<ResponseBody>

    // Para getAvailableVoices()
    @GET("voices")
    suspend fun getVoices(
        @Query("locale") locale: String = "en-US",
        @Query("gender") gender: String
    ): Response<List<Voice>>

    // Para gerarLegendaSRT()
    @Multipart
    @POST("openai/v1/audio/transcriptions")
    suspend fun transcribeAudio(
        @Part file: MultipartBody.Part,
        @Part("model") model: MultipartBody.Part,
        @Part("prompt") prompt: MultipartBody.Part,
        @Part("language") language: MultipartBody.Part,
        @Part("response_format") responseFormat: MultipartBody.Part
    ): Response<TranscriptionResponse>
}

data class Voice(
    val short_name: String,
    val gender: String
)

data class TranscriptionResponse(
    val segments: List<Segment>
)

data class Segment(
    val start: Double,
    val end: Double,
    val text: String
)