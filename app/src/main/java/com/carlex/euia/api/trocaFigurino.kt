// File: euia/api/ProvadorVirtual.kt
package com.carlex.euia.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

object ProvadorVirtual {
    private const val TAG = "ProvadorVirtual"
    private const val BASE_URL = "https://kwai-kolors-kolors-virtual-try-on.hf.space"

    private var triggerId = 25
    private val sessionHash = UUID.randomUUID().toString().replace("-", "").substring(0, 12)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private fun log(message: String) {
        Log.d(TAG, message)
    }

    private suspend fun uploadImage(imagePath: String): FileData? = withContext(Dispatchers.IO) {
        val file = File(imagePath)
        if (!file.exists()) {
            log("❌ Arquivo não encontrado: $imagePath")
            return@withContext null
        }
        log("Iniciando upload de ${file.name}...")

        val uploadId = UUID.randomUUID().toString().replace("-", "").substring(0, 10)
        val uploadUrl = "$BASE_URL/upload?upload_id=$uploadId"

        val mimeType = "image/png"

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("files", file.name, file.asRequestBody(mimeType.toMediaType()))
            .build()

        val request = Request.Builder()
            .url(uploadUrl)
            .post(requestBody)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Erro HTTP no upload: ${response.code} - ${response.message}")
                }
                val responseBody = response.body?.string()
                if (responseBody.isNullOrEmpty()) {
                    throw IOException("Resposta do upload vazia")
                }
                val pathList: List<String> = json.decodeFromString(responseBody)
                if (pathList.isEmpty()) {
                    throw IOException("Resposta do upload (lista de paths) está vazia")
                }
                val path: String = pathList[0]
                return@withContext FileData(
                    path = path,
                    url = "$BASE_URL/file=$path",
                    orig_name = file.name,
                    size = file.length(),
                    mime_type = mimeType
                )
            }
        } catch (e: Exception) {
            log("❌ Falha no upload de ${file.name}: ${e.message}")
            e.printStackTrace()
            return@withContext null
        }
    }

    private suspend fun generatePayload(humanImgPath: String, clothImgPath: String): RequestPayload? {
        log("Gerando payload...")
        triggerId++

        val humanData = uploadImage(humanImgPath)
        val clothData = uploadImage(clothImgPath)

        if (humanData == null || clothData == null) {
            log("❌ Falha ao fazer upload de uma ou ambas as imagens.")
            return null
        }

        val humanJsonElement = json.encodeToJsonElement(humanData)
        val clothJsonElement = json.encodeToJsonElement(clothData)

        val dataList = listOf(
            humanJsonElement,
            clothJsonElement,
            JsonPrimitive(0),
            JsonPrimitive(true)
        )

        return RequestPayload(
            data = dataList,
            eventData = null,
            fnIndex = 2,
            triggerId = triggerId,
            sessionHash = sessionHash
        )
    }

    private suspend fun submitRequest(payload: RequestPayload): Boolean = withContext(Dispatchers.IO) {
        log("Enviando requisição para /queue/join...")
        val url = "$BASE_URL/queue/join"

        return@withContext try {
            val payloadJsonString = json.encodeToString(payload)
            val requestBody = payloadJsonString.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .header("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    log("Erro na submissão: ${response.code} - ${response.message}")
                    false
                } else {
                    log("✅ Submissão bem-sucedida!")
                    true
                }
            }
        } catch (e: Exception) {
            log("❌ Erro na conexão/submissão: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private suspend fun pollResult(timeoutMillis: Long = 300_000): Result<String> = withContext(Dispatchers.IO) {
        log("Iniciando polling SSE...")
        val url = "$BASE_URL/queue/data?session_hash=$sessionHash"
        val request = Request.Builder().url(url).get()
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()

        val resultDeferred = CompletableDeferred<Result<String>>()

        val eventSourceListener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                log("Conexão SSE aberta.")
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                log("Evento SSE recebido: $data")
                try {
                    val eventData = json.decodeFromString<SseEvent>(data)
                    when (eventData.msg) {
                        "process_completed" -> {
                            log("Processamento finalizado pelo servidor.")
                            
                            if (eventData.success == true && eventData.output?.data?.isNotEmpty() == true) {
                                val firstElement = eventData.output.data.getOrNull(0)
                                
                                if (firstElement is JsonObject && firstElement["url"] is JsonPrimitive) {
                                    val resultUrl = firstElement["url"]!!.jsonPrimitive.content
                                    val fullResultUrl = if (resultUrl.startsWith("http")) resultUrl else "$BASE_URL/file=$resultUrl"
                                    log("✅ Sucesso! URL do resultado extraída: $fullResultUrl")
                                    resultDeferred.complete(Result.success(fullResultUrl))
                                } else {
                                    val errorMessageFromServer = eventData.output.data
                                        .filterIsInstance<JsonPrimitive>()
                                        .firstOrNull { it.isString }?.content
                                        ?: "Formato de resposta inesperado (sucesso, mas sem dados válidos)."
                                    log("❌ $errorMessageFromServer")
                                    resultDeferred.complete(Result.failure(IOException(errorMessageFromServer)))
                                }
                            } else {
                                val errorMsg = "Servidor indicou falha no processamento ou não retornou dados de saída."
                                log("❌ $errorMsg")
                                resultDeferred.complete(Result.failure(IOException(errorMsg)))
                            }
                            eventSource.cancel()
                        }
                        "estimation", "process_starts", "process_generating", "heartbeat" -> {
                            log("Evento SSE de progresso: ${eventData.msg}")
                        }
                        else -> {
                             log("Tipo de mensagem SSE não reconhecido: ${eventData.msg}")
                        }
                    }
                } catch (e: Exception) {
                    log("⚠️ Erro ao processar evento SSE: ${e.message} - Data: $data")
                    if (!resultDeferred.isCompleted) {
                        resultDeferred.complete(Result.failure(e))
                    }
                    eventSource.cancel()
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val errorMsg = "Falha na conexão SSE: ${t?.message ?: "Erro desconhecido"}"
                log("❌ $errorMsg")
                if (!resultDeferred.isCompleted) {
                    resultDeferred.complete(Result.failure(IOException(errorMsg, t)))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                log("Conexão SSE fechada.")
                if (!resultDeferred.isCompleted) {
                    resultDeferred.complete(Result.failure(IOException("Conexão SSE fechada inesperadamente.")))
                }
            }
        }

        val factory = EventSources.createFactory(client)
        val eventSource = factory.newEventSource(request, eventSourceListener)

        try {
            return@withContext withTimeout(timeoutMillis) {
                resultDeferred.await()
            }
        } catch (e: TimeoutCancellationException) {
            log("❌ Timeout (${timeoutMillis}ms) excedido durante o polling.")
            eventSource.cancel()
            return@withContext Result.failure(e)
        } catch (e: Exception) {
             log("❌ Erro inesperado durante o polling: ${e.message}")
             eventSource.cancel()
             return@withContext Result.failure(e)
        }
    }

    private fun redimensionarComFundoTransparente(bitmap: Bitmap): Bitmap {
        val finalWidth = 800
        val finalHeight = 1600
        val newBitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(newBitmap)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val scaleRatio = minOf(finalWidth.toFloat() / bitmap.width, finalHeight.toFloat() / bitmap.height)
        val newWidth = (bitmap.width * scaleRatio).toInt()
        val newHeight = (bitmap.height * scaleRatio).toInt()
        val xOffset = (finalWidth - newWidth) / 2
        val yOffset = (finalHeight - newHeight) / 2
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        canvas.drawBitmap(resizedBitmap, xOffset.toFloat(), yOffset.toFloat(), null)
        if (resizedBitmap != bitmap && !resizedBitmap.isRecycled) {
            // resizedBitmap.recycle() // Use com cautela
        }
        return newBitmap
    }

    private fun salvarImagemNoCaminhoEspecifico(bitmap: Bitmap, targetPath: String, context: Context): String? {
        var resizedBitmap: Bitmap? = null
        try {
            resizedBitmap = redimensionarComFundoTransparente(bitmap)
            val file = File(targetPath)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                resizedBitmap.compress(Bitmap.CompressFormat.WEBP, 60, out)
                out.flush()
            }
            return file.absolutePath
        } catch (e: Exception) {
            log("❌ Erro ao salvar imagem WEBP em $targetPath: ${e.message}")
            e.printStackTrace()
            return null
        } finally {
            resizedBitmap?.recycle()
        }
    }

    private suspend fun downloadAndSaveImage(imageUrl: String, targetSavePath: String, context: Context): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(imageUrl).get().build()
        var downloadedBitmap: Bitmap? = null
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                body.byteStream().use { inputStream ->
                    downloadedBitmap = BitmapFactory.decodeStream(inputStream)
                }
                if (downloadedBitmap == null) return@withContext null
                return@withContext salvarImagemNoCaminhoEspecifico(downloadedBitmap!!, targetSavePath, context)
            }
        } catch (e: Exception) {
            log("❌ Erro no download ou processamento: ${e.message}")
            e.printStackTrace()
            return@withContext null
        } finally {
             downloadedBitmap?.recycle()
        }
    }

    suspend fun generate(fotoPath: String, figurinoPath: String, context: Context): Result<String> {
        log("🚀 Iniciando processo completo de TryOn Virtual...")
        log("fotoPath (será sobrescrito): $fotoPath")
        log("figurinoPath: $figurinoPath")

        val payload = generatePayload(fotoPath, figurinoPath)
        if (payload == null) {
            val errorMsg = "Falha na criação do payload (provavelmente o upload de uma das imagens falhou)."
            log("❌ $errorMsg")
            return Result.failure(IOException(errorMsg))
        }
        log("✅ Payload gerado com sucesso!")

        val submitSuccess = submitRequest(payload)
        if (!submitSuccess) {
            val errorMsg = "Falha na submissão do pedido para a fila do servidor."
            log("❌ $errorMsg")
            return Result.failure(IOException(errorMsg))
        }
        log("✅ Pedido submetido com sucesso! Aguardando resultado via SSE...")

        return try {
            val pollResultOutcome = pollResult()

            if (pollResultOutcome.isSuccess) {
                val resultadoUrl = pollResultOutcome.getOrThrow()
                log("🎉 URL do resultado obtida via SSE: $resultadoUrl")
                val caminhoSalvo = downloadAndSaveImage(
                    imageUrl = resultadoUrl,
                    targetSavePath = fotoPath,
                    context = context
                )
                if (caminhoSalvo != null) {
                    log("✅ Imagem final salva (sobrescrita) em: $caminhoSalvo")
                    Result.success(caminhoSalvo)
                } else {
                    val errorMsg = "Falha ao baixar ou salvar a imagem final da URL: $resultadoUrl"
                    log("❌ $errorMsg")
                    Result.failure(IOException(errorMsg))
                }
            } else {
                val exception = pollResultOutcome.exceptionOrNull() ?: IOException("Polling SSE falhou com erro desconhecido.")
                log("❌ Polling SSE falhou: ${exception.message}")
                Result.failure(exception)
            }
        } catch (e: Exception) {
            log("🔥 Erro crítico durante o processo de geração: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        } finally {
            log("🏁 Processo TryOn (geração de URL) finalizado.")
        }
    }

    @Serializable
    data class FileData(
        val path: String,
        val url: String,
        val orig_name: String,
        val size: Long,
        val mime_type: String,
        val meta: MetaData = MetaData()
    )

    @Serializable
    data class MetaData(val _type: String = "gradio.FileData")

    @Serializable
    data class RequestPayload(
        val data: List<JsonElement>,
        @SerialName("event_data") val eventData: String? = null,
        @SerialName("fn_index") val fnIndex: Int,
        @SerialName("trigger_id") val triggerId: Int,
        @SerialName("session_hash") val sessionHash: String
    )

    @Serializable
    data class SseEvent(
        val msg: String,
        @SerialName("rank_eta") val rankEta: Float? = null,
        val success: Boolean? = null,
        val output: SseOutput? = null
    )

    @Serializable
    data class SseOutput(
        val data: List<JsonElement>
    )
}