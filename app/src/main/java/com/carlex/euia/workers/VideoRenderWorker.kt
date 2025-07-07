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
import com.carlex.euia.data.VideoGeneratorDataStoreManager // <<< IMPORTAÇÃO ADICIONADA
import com.carlex.euia.data.VideoProjectDataStoreManager
import com.carlex.euia.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

private const val TAG = "VideoRenderWorker"

class VideoRenderWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val videoProjectDataStoreManager = VideoProjectDataStoreManager(appContext)
    // CORREÇÃO 1: Adicionar instância do DataStore do Gerador
    private val videoGeneratorDataStoreManager = VideoGeneratorDataStoreManager(appContext)
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val TAG_VIDEO_RENDER = "video_render_work"
        const val KEY_AUDIO_PATH = "key_audio_path"
        const val KEY_MUSIC_PATH = "key_music_path"
        const val KEY_LEGEND_PATH = "key_legend_path"
        const val KEY_OUTPUT_VIDEO_PATH = "key_output_video_path"
        const val KEY_ERROR_MESSAGE = "key_error_message"
        const val KEY_PROGRESS = "key_progress"
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        // A criação do canal já é feita centralmente no MyApplication.kt
        val notification = createNotification(0, appContext.getString(R.string.overlay_starting))
        return ForegroundInfo(NotificationUtils.CHANNEL_ID_VIDEO_RENDER.hashCode(), notification) // Usando ID do canal como ID da notificação para simplicidade
    }

    override suspend fun doWork(): Result {
        // CORREÇÃO 2: Lógica de Lock no início e no fim

        // 1. VERIFICAR O LOCK ANTES DE TUDO
        if (videoGeneratorDataStoreManager.isCurrentlyGeneratingVideo.first()) {
            val errorMsg = "Uma renderização já está em andamento ou falhou. Cancele a tarefa anterior ou reinicie o app."
            Log.e(TAG, errorMsg)
            updateNotification(100, "Erro: Renderização Duplicada", isError = true, isFinished = true)
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to errorMsg))
        }

        // 2. ATIVAR O LOCK E ENVOLVER TUDO EM TRY-FINALLY
        try {
            videoGeneratorDataStoreManager.setCurrentlyGenerating(true)

            val audioPath = inputData.getString(KEY_AUDIO_PATH)
            val musicPath = inputData.getString(KEY_MUSIC_PATH) ?: ""
            val legendPath = inputData.getString(KEY_LEGEND_PATH) ?: ""

            val scenesToInclude = try {
                videoProjectDataStoreManager.sceneLinkDataList.first().filter { scene ->
                    scene.imagemGeradaPath?.isNotBlank() == true &&
                    scene.tempoInicio != null &&
                    scene.tempoFim != null &&
                    scene.tempoFim > scene.tempoInicio
                }
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao ler dados das cenas do DataStore no worker.", e)
                throw IllegalStateException("Erro ao ler dados das cenas.", e) // Lança para o catch principal
            }

            if (scenesToInclude.isEmpty() || audioPath.isNullOrBlank()) {
                val error = "Dados essenciais faltando (Cenas: ${scenesToInclude.size}, Áudio: ${!audioPath.isNullOrBlank()})"
                throw IllegalStateException(error)
            }
            
            OverlayManager.showOverlay(appContext, "🎥", -1) 

            val totalDurationForProgress = scenesToInclude.sumOf { it.tempoFim!! - it.tempoInicio!! }
            var pro = -1
            val finalVideoPath = VideoEditorComTransicoes.gerarVideoComTransicoes(
                context = appContext,
                scenes = scenesToInclude,
                audioPath = audioPath,
                musicaPath = musicPath,
                legendaPath = legendPath,
                logCallback = { logMessage ->
                
                    Log.w(TAG, "logMessage: $logMessage")
                                  
                    /*val timeMatch = Regex("time=(\\d{2}:\\d{2}:\\d{2}\\.\\d{2})").find(logMessage)
                    timeMatch?.let {
                        val timeString = it.groupValues[1]
                        val parts = timeString.split(":", ".")
                        if (parts.size == 4) {
                            try {
                                val hours = parts[0].toDouble(); val minutes = parts[1].toDouble(); val seconds = parts[2].toDouble(); val centiseconds = parts[3].toDouble()
                                val timeInSeconds = hours * 3600 + minutes * 60 + seconds + centiseconds / 100
                                if (totalDurationForProgress > 0) {
                                    val progressFloat = (timeInSeconds / totalDurationForProgress).toFloat().coerceIn(0f, 1f)
                                    val progressPercent = (progressFloat * 100).toInt()
                                    if (progressPercent > pro){
                                        Log.w(TAG, "progressPercent: $progressPercent")
                                        OverlayManager.showOverlay(appContext, appContext.getString(R.string.notification_title_video_render), progressPercent) 
                                        pro = progressPercent
                                    }
                                    
                                    updateNotification(progressPercent, "$progressPercent%")
                                    setProgressAsync(workDataOf(KEY_PROGRESS to progressFloat))
                                }
                            } catch (e: NumberFormatException) {
                                Log.w(TAG, "Falha ao parsear tempo do log FFmpeg: '$timeString'", e)
                            }
                        }
                    }*/
                    
                    val loteMatch = Regex("Lote (\\d+) de (\\d+), Duracao: ([\\d.]+), Concluido=([\\d.]+)").find(logMessage)
                    loteMatch?.let {
                        val loteAtual = it.groupValues[1].toInt()
                        val totalLotes = it.groupValues[2].toInt()
                        val duracao = it.groupValues[3].toDouble()
                        val concluido = it.groupValues[4].toDouble()
                    
                        val progressoGlobal = (concluido / (duracao * totalLotes)) * 100
                        
                        val progressoPorLote = (1.0 / totalLotes) * 100
                        val progressoAtualNoLote = (concluido / duracao) * progressoPorLote
                        val progressoAcumulado = progressoPorLote * (loteAtual - 1) + progressoAtualNoLote
                 
                        val progressoPercent = (progressoAcumulado.toInt()).coerceIn(0, 100)
                        
                        if (progressoPercent > pro) {
                            Log.w(TAG, "Progresso global: $progressoPercent%")
                            OverlayManager.showOverlay(appContext, "$progressoPercent %", progressoPercent)
                            updateNotification(progressoPercent, "$progressoPercent%")
                            setProgressAsync(workDataOf(KEY_PROGRESS to (progressoPercent / 100f)))
                            pro = progressoPercent
                        }
                        
                        Log.w(TAG, "loteAtual=$loteAtual, totalLotes=$totalLotes, duracao=$duracao, concluido=$concluido, progressoPercent=$progressoPercent, pro=$pro")
                    
           
                    }
                    
                }
            )

            if (finalVideoPath.isNotBlank()) {
                OverlayManager.hideOverlay(appContext) 
                        
                updateNotification(100, "Concluído!", isFinished = true)
                return Result.success(workDataOf(KEY_OUTPUT_VIDEO_PATH to finalVideoPath))
            } else {
                throw Exception("Editor de vídeo retornou um caminho vazio.")
            }
        } catch (e: Exception) {
            val errorMessage = if (e is CancellationException) "Renderização cancelada." else e.message ?: "Erro desconhecido."
            Log.e(TAG, "Falha na renderização do vídeo no worker.", e)
            updateNotification(100, "Falhou!", isError = true, isFinished = true)
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to errorMessage))
        } finally {
            // 3. DESLIGAR O LOCK, ACONTEÇA O QUE ACONTECER
            ProjectPersistenceManager.saveProjectState(appContext)
            Log.d(TAG, "Bloco finally do Worker: Garantindo que o lock de renderização seja desativado.")
            videoGeneratorDataStoreManager.setCurrentlyGenerating(false)
        }
    }

    private fun createNotification(progress: Int, message: String, isError: Boolean = false, isFinished: Boolean = false): Notification {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        /*val pendingIntent = PendingIntent.getActivity(
            appContext,
            0,
            //intent,
            //PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )*/

        val builder = NotificationCompat.Builder(appContext, NotificationUtils.CHANNEL_ID_VIDEO_RENDER)
            .setContentTitle(appContext.getString(R.string.video_render_notification_title))
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            //.setContentIntent(pendingIntent)
           // .setAutoCancel(true)

        if (isFinished || isError) {
            builder.setProgress(0, 0, false).setOngoing(false)
            //OverlayManager.hideOverlay(applicationContext)
        } else {
            //OverlayManager.hideOverlay(applicationContext)
            builder.setProgress(100, progress, false).setOngoing(true)
        }
        return builder.build()
    }

    private fun updateNotification(progress: Int, message: String, isError: Boolean = false, isFinished: Boolean = false) {
        notificationManager.notify(NotificationUtils.CHANNEL_ID_VIDEO_RENDER.hashCode(), createNotification(progress, message, isError, isFinished))
    }
}