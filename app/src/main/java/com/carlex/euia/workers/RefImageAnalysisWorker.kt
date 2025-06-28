package com.carlex.euia.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.carlex.euia.MainActivity
import com.carlex.euia.R
import com.carlex.euia.api.GeminiTextAndVisionStandardApi
import com.carlex.euia.data.AudioDataStoreManager
import com.carlex.euia.data.ImagemReferencia
import com.carlex.euia.data.RefImageDataStoreManager
import com.carlex.euia.data.VideoDataStoreManager
import com.carlex.euia.prompts.DescriptionClottings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val TAG = "RefImageAnalysisWorker"

// <<< CORREÇÃO 1: Adicionar constantes de notificação específicas >>>
private const val NOTIFICATION_ID_REF_IMAGE = 5
private const val NOTIFICATION_CHANNEL_ID_REF_IMAGE = "RefImageAnalysisChannelEUIA"

@Serializable
data class JsonDetail(
    val key: String,
    var value: String
)

class RefImageAnalysisWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val refImageDataStoreManager = RefImageDataStoreManager(applicationContext)
    private val videoDataStoreManager = VideoDataStoreManager(applicationContext)
    private val audioDataStoreManager = AudioDataStoreManager(applicationContext)
    private val json = Json { ignoreUnknownKeys = true }
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val KEY_ERROR_MESSAGE = "error_message"
        const val TAG_REF_IMAGE_ANALYSIS_WORK = "ref_image_analysis_work"
    }
    
    // <<< CORREÇÃO 2: Implementar funções de notificação >>>

    private fun createNotification(message: String, isFinished: Boolean = false, isError: Boolean = false): Notification {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID_REF_IMAGE)
            .setContentTitle(applicationContext.getString(R.string.notification_title_ref_image_analysis))
            .setTicker(applicationContext.getString(R.string.notification_title_ref_image_analysis))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setAutoCancel(isFinished || isError)

        if (isFinished || isError) {
            builder.setProgress(0, 0, false).setOngoing(false)
        } else {
            builder.setProgress(0, 0, true).setOngoing(true) // Indeterminado
        }
        return builder.build()
    }
    
    private fun updateNotification(contentText: String, makeDismissible: Boolean = false, isError: Boolean = false) {
        val notification = createNotification(contentText, isFinished = makeDismissible, isError = isError)
        notificationManager.notify(NOTIFICATION_ID_REF_IMAGE, notification)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        // A criação do canal já é feita centralmente, não precisa mais chamar aqui.
        val notification = createNotification(applicationContext.getString(R.string.notification_content_ref_image_starting))
        return ForegroundInfo(NOTIFICATION_ID_REF_IMAGE, notification)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork started for RefImageAnalysisWorker.")
        updateNotification(applicationContext.getString(R.string.notification_content_ref_image_fetching_data))

        try {
            Log.d(TAG, "Buscando dados dos DataStores...")
            val tituloValue = audioDataStoreManager.videoTitulo.first()
            val extrasValue = "" // Esta variável parece não ser mais usada, mantendo vazia.
            
            val currentPrompt = DescriptionClottings(tituloValue, extrasValue).prompt
            val videoImagensReferenciaJsonString = videoDataStoreManager.imagensReferenciaJson.first()
            
            val imageRefs = try {
                 if (videoImagensReferenciaJsonString.isNotBlank() && videoImagensReferenciaJsonString != "[]") {
                     json.decodeFromString(ListSerializer(ImagemReferencia.serializer()), videoImagensReferenciaJsonString)
                 } else { emptyList() }
             } catch (e: Exception) {
                 val errorMsg = "Erro ao carregar referências de imagem salvas: ${e.message}"
                 throw IllegalStateException(errorMsg, e)
             }

            val imagePaths = imageRefs.map { it.path }
            if (imagePaths.isEmpty()) {
                val errorMsg = "Não há imagens de referência para analisar."
                updateNotification(errorMsg, makeDismissible = true, isError = true)
                return Result.failure(workDataOf(KEY_ERROR_MESSAGE to errorMsg))
            }

            updateNotification(applicationContext.getString(R.string.notification_content_ref_image_analyzing, imagePaths.size))
            val jsonArrayResult = gerarDetalhesVisuais(currentPrompt, imagePaths)
            
            if (jsonArrayResult.length() > 0) {
                updateNotification(applicationContext.getString(R.string.notification_content_ref_image_saving))
                val flattenedMap = processGeminiJsonResponse(jsonArrayResult)
                val jsonStringResult = json.encodeToString(flattenedMap)
                refImageDataStoreManager.setRefObjetoDetalhesJson(jsonStringResult)

                val successMsg = applicationContext.getString(R.string.notification_content_ref_image_success, flattenedMap.size)
                updateNotification(successMsg, makeDismissible = true)
                return Result.success()
            } else {
                val errorMsg = "Análise da IA não retornou detalhes visuais."
                updateNotification(errorMsg, makeDismissible = true, isError = true)
                return Result.failure(workDataOf(KEY_ERROR_MESSAGE to errorMsg))
            }
        } catch (e: Exception) {
            val errorMessage = when (e) {
                is CancellationException -> "Análise cancelada pelo usuário."
                else -> e.message ?: "Erro desconhecido na análise."
            }
            Log.e(TAG, "Erro fatal no RefImageAnalysisWorker: $errorMessage", e)
            updateNotification(applicationContext.getString(R.string.notification_content_ref_image_failed, errorMessage.take(50)), makeDismissible = true, isError = true)
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to errorMessage))
        }
    }

    // --- Funções auxiliares (gerarDetalhesVisuais, flattenObject, processGeminiJsonResponse) ---
    // ... O código dessas funções permanece o mesmo ...
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun gerarDetalhesVisuais(
        prompt: String, imagensPatch: List<String>
    ): JSONArray {
        Log.d(TAG, "Iniciando gerarDetalhesVisuais com prompt: '$prompt', imagensPatch count: ${imagensPatch.size}")
        val respostaResult = try {
            GeminiTextAndVisionStandardApi.perguntarAoGemini(prompt, imagensPatch, "")
        } catch (e: Exception) {
             Log.e(TAG, "Erro na chamada Gemini (no Worker): ${e.message}", e)
             return JSONArray()
        }

        if (respostaResult.isSuccess) {
            val resposta = respostaResult.getOrNull() ?: ""
            if (resposta.isBlank()) return JSONArray()

            var respostaLimpa = resposta.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            try {
                if (respostaLimpa.startsWith("[")) {
                    return JSONArray(respostaLimpa)
                } else if (respostaLimpa.startsWith("{")) {
                    val jsonObj = JSONObject(respostaLimpa)
                    return JSONArray().put(jsonObj)
                }
            } catch (e: JSONException) {
                 Log.e(TAG, "Falha ao parsear JSON da resposta Gemini (no Worker): ${e.message}", e)
            }
        } else {
            Log.e(TAG, "Falha na API Gemini (no Worker): ${respostaResult.exceptionOrNull()?.message}", respostaResult.exceptionOrNull())
        }
        return JSONArray()
    }

    private fun flattenObject(jsonObject: JSONObject, prefix: String, map: MutableMap<String, String>) {
       val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val fullKey = if (prefix.isEmpty()) key else "$prefix.$key"
            val value = jsonObject.get(key)
            when (value) {
                is JSONObject -> flattenObject(value, fullKey, map)
                is JSONArray -> {
                    val arrayString = (0 until value.length()).joinToString(", ") { value.opt(it)?.toString() ?: "null" }
                    map[fullKey] = arrayString
                }
                else -> map[fullKey] = value.toString()
            }
        }
    }

    private fun processGeminiJsonResponse(jsonArray: JSONArray): Map<String, String> {
        val flattenedMap = mutableMapOf<String, String>()
        for (i in 0 until jsonArray.length()) {
            try {
                flattenObject(jsonArray.getJSONObject(i), "", flattenedMap)
            } catch (e: JSONException) {
                Log.e(TAG, "Erro processando elemento $i do JSONArray da resposta Gemini", e)
            }
        }
        return flattenedMap
    }
}