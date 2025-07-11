// File: euia/workers/VideoRenderWorker.kt
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
import androidx.work.CoroutineWorker
import com.carlex.euia.utils.ProjectPersistenceManager
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.carlex.euia.MainActivity
import com.carlex.euia.R
import com.carlex.euia.data.VideoGeneratorDataStoreManager
import com.carlex.euia.data.VideoProjectDataStoreManager
import com.carlex.euia.data.SceneLinkData
import com.carlex.euia.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.util.UUID

private const val TAG = "VideoRenderWorker"

class VideoRenderWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val videoProjectDataStoreManager = VideoProjectDataStoreManager(appContext)
    private val videoGeneratorDataStoreManager = VideoGeneratorDataStoreManager(appContext)
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val TAG_VIDEO_RENDER = "video_render_work"
        const val KEY_AUDIO_PATH = "key_audio_path"
        const val KEY_MUSIC_PATH = "key_music_path"
        const val KEY_LEGEND_PATH = "key_legenda_path"
        const val KEY_OUTPUT_VIDEO_PATH = "key_output_video_path"
        const val KEY_ERROR_MESSAGE = "key_error_message"
        const val KEY_PROGRESS = "key_progress"
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = createNotification(0, appContext.getString(R.string.overlay_starting))
        return ForegroundInfo(NotificationUtils.CHANNEL_ID_VIDEO_RENDER.hashCode(), notification)
    }

    override suspend fun doWork(): Result {
        if (videoGeneratorDataStoreManager.isCurrentlyGeneratingVideo.first()) {
            val errorMsg = "Uma renderização já está em andamento ou falhou. Cancele a tarefa anterior ou reinicie o app."
            Log.e(TAG, errorMsg)
            updateNotification(100, "Erro: Renderização Duplicada", isError = true, isFinished = true)
            OverlayManager.hideOverlay(appContext)
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to errorMsg))
        }

        try {
            videoGeneratorDataStoreManager.setCurrentlyGenerating(true)

            val audioPath = inputData.getString(KEY_AUDIO_PATH)
            val musicPath = inputData.getString(KEY_MUSIC_PATH) ?: ""
            val legendPath = inputData.getString(KEY_LEGEND_PATH) ?: ""

            val scenesToInclude = videoProjectDataStoreManager.sceneLinkDataList.first().filter { scene ->
                scene.imagemGeradaPath?.isNotBlank() == true &&
                scene.tempoInicio != null &&
                scene.tempoFim != null &&
                scene.tempoFim > scene.tempoInicio
            }

            if (scenesToInclude.isEmpty() || audioPath.isNullOrBlank()) {
                val error = "Dados essenciais faltando (Cenas: ${scenesToInclude.size}, Áudio: ${!audioPath.isNullOrBlank()})"
                OverlayManager.hideOverlay(appContext)
                throw IllegalStateException(error)
            }
            
            OverlayManager.showOverlay(appContext, "🎥", -1) 

            var pro = -1

            val finalVideoPath = VideoEditorComTransicoes.gerarVideoComTransicoes(
                context = appContext,
                scenes = scenesToInclude,
                audioPath = audioPath,
                musicaPath = musicPath,
                legendaPath = legendPath,
                logCallback = { logMessage ->
                    val loteMatch = Regex("Lote (\\d+) de (\\d+), Duracao: ([\\d.]+), Concluido=([\\d.]+)").find(logMessage)
                    loteMatch?.let {
                        val loteAtual = it.groupValues[1].toInt()
                        val totalLotes = it.groupValues[2].toInt()
                        val duracaoLote = it.groupValues[3].toDouble()
                        val concluidoNoLote = it.groupValues[4].toDouble()

                        val progressoPorLote = (1.0 / totalLotes) * 100
                        val progressoAtualNoLote = (concluidoNoLote / duracaoLote) * progressoPorLote
                        val progressoAcumulado = progressoPorLote * (loteAtual - 1) + progressoAtualNoLote
                 
                        val progressoPercent = (progressoAcumulado.toInt()).coerceIn(0, 100)
                        
                        if (progressoPercent > pro) {
                            Log.w(TAG, "Progresso render video: $progressoPercent%")
                            OverlayManager.showOverlay(appContext, "$progressoPercent %", progressoPercent)
                            updateNotification(progressoPercent, "$progressoPercent%")
                            setProgressAsync(workDataOf(KEY_PROGRESS to (progressoPercent / 100f)))
                            pro = progressoPercent
                        }
                    }
                }
            )

            if (finalVideoPath.isNotBlank()) {
                videoGeneratorDataStoreManager.setFinalVideoPath(finalVideoPath)
                Log.i(TAG, "Vídeo gerado e salvo nas preferências: $finalVideoPath")
                updateNotification(100, "Concluído!", isFinished = true)
                return Result.success(workDataOf(KEY_OUTPUT_VIDEO_PATH to finalVideoPath))
            } else {
                OverlayManager.hideOverlay(appContext)
                throw Exception("Editor de vídeo retornou um caminho vazio.")
            }
        } catch (e: Exception) {
            val errorMessage = if (e is CancellationException) {
                appContext.getString(R.string.video_render_cancelled)
            } else {
                e.message ?: appContext.getString(R.string.video_render_unknown_error)
            }
            OverlayManager.hideOverlay(appContext)
            Log.e(TAG, "Falha na renderização do vídeo no worker.", e)
            updateNotification(100, "Falhou: ${errorMessage.take(30)}", isError = true, isFinished = true)
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to errorMessage))
        } finally {
            ProjectPersistenceManager.saveProjectState(appContext)
            Log.d(TAG, "Bloco finally do Worker: Garantindo que o lock de renderização seja desativado.")
            videoGeneratorDataStoreManager.setCurrentlyGenerating(false)
            OverlayManager.hideOverlay(appContext)
        }
    }

    private fun createNotification(progress: Int, message: String, isError: Boolean = false, isFinished: Boolean = false): Notification {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(appContext, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        
        val builder = NotificationCompat.Builder(appContext, NotificationUtils.CHANNEL_ID_VIDEO_RENDER)
            .setContentTitle(appContext.getString(R.string.video_render_notification_title))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)

        if (isFinished || isError) {
            builder.setProgress(0, 0, false).setOngoing(false).setAutoCancel(true)
        } else {
            builder.setProgress(100, progress, false).setOngoing(true).setAutoCancel(false)
        }
        return builder.build()
    }

    private fun updateNotification(progress: Int, message: String, isError: Boolean = false, isFinished: Boolean = false) {
        notificationManager.notify(NotificationUtils.CHANNEL_ID_VIDEO_RENDER.hashCode(), createNotification(progress, message, isError, isFinished))
    }
}