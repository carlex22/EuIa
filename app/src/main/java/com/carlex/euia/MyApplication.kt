// File: euia/MyApplication.kt
package com.carlex.euia

import android.app.Application
import android.os.StrictMode
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.carlex.euia.BuildConfig
import com.carlex.euia.data.AudioDataStoreManager
import com.carlex.euia.data.VideoDataStoreManager
import com.carlex.euia.data.VideoGeneratorDataStoreManager // <<< Certifique-se que está importado
import com.carlex.euia.data.VideoProjectDataStoreManager
import com.carlex.euia.utils.NotificationUtils
import com.carlex.euia.utils.ProjectPersistenceManager
import com.carlex.euia.utils.WorkerTags // <<< Certifique-se que está importado
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .penaltyLog()
                    .build()
            )

            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
        }

        NotificationUtils.createAllNotificationChannels(this)
        Log.d("MyApplication", "Todos os canais de notificação foram criados/verificados.")

        Log.d("MyApplication", "Application onCreate - Registrando AppLifecycleObserver.")
        ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifecycleObserver(this))
    }
}

class AppLifecycleObserver(private val context: Application) : DefaultLifecycleObserver {
    private val TAG = "AppLifecycleObserver"

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        Log.d(TAG, "App moved to foreground (ON_START). Checking for stale processing states.")
        
        owner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val workManager = WorkManager.getInstance(context)
                    val audioDataStoreManager = AudioDataStoreManager(context)
                    val videoDataStoreManager = VideoDataStoreManager(context)
                    val videoProjectDataStoreManager = VideoProjectDataStoreManager(context)
                    // <<< ADICIONADO: Instanciar o DataStore do Gerador de Vídeo >>>
                    val videoGeneratorDataStoreManager = VideoGeneratorDataStoreManager(context)

                    checkImageProcessingState(videoDataStoreManager, workManager)
                    checkAudioProcessingState(audioDataStoreManager, workManager)
                    checkVideoScenesState(videoProjectDataStoreManager, workManager)
                    
                    // <<< CORREÇÃO PRINCIPAL: Chamar a verificação do estado de renderização >>>
                    checkVideoRenderingState(videoGeneratorDataStoreManager, workManager)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error during state check/cleanup in onStart", e)
                }
            }
        }
    }

    private suspend fun checkImageProcessingState(
        videoDataStoreManager: VideoDataStoreManager,
        workManager: WorkManager
    ) {
        if (videoDataStoreManager.isProcessingImages.first()) {
            val imageWorkQuery = WorkQuery.Builder.fromTags(listOf(WorkerTags.IMAGE_PROCESSING_WORK)).build()
            val imageWorkInfos = try {
                workManager.getWorkInfos(imageWorkQuery).get()
            } catch (e: Exception) { emptyList() }

            if (imageWorkInfos.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }) {
                Log.w(TAG, "Images: State was 'processing' but no active worker found. Resetting state.")
                videoDataStoreManager.setIsProcessingImages(false)
            }
        }
    }

    private suspend fun checkAudioProcessingState(
        audioDataStoreManager: AudioDataStoreManager,
        workManager: WorkManager
    ) {
        if (audioDataStoreManager.isAudioProcessing.first()) {
            val audioWorkQuery = WorkQuery.Builder.fromTags(listOf(WorkerTags.AUDIO_NARRATIVE)).build()
            val audioWorkInfos = try {
                workManager.getWorkInfos(audioWorkQuery).get()
            } catch (e: Exception) { emptyList() }

            if (audioWorkInfos.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }) {
                Log.w(TAG, "Audio: State was 'processing' but no active worker found. Resetting state.")
                audioDataStoreManager.setIsAudioProcessing(false)
                audioDataStoreManager.setGenerationProgressText("")
                audioDataStoreManager.setGenerationError(null)
            }
        }
    }

    private suspend fun checkVideoScenesState(
        videoProjectDataStoreManager: VideoProjectDataStoreManager,
        workManager: WorkManager
    ) {
        val currentSceneList = try {
            videoProjectDataStoreManager.sceneLinkDataList.first()
        } catch (e: Exception) { emptyList() }

        if (currentSceneList.any { it.isGenerating || it.isChangingClothes || it.isGeneratingVideo }) {
            val videoWorkQuery = WorkQuery.Builder.fromTags(listOf(WorkerTags.VIDEO_PROCESSING)).build()
            val videoWorkInfos = try {
                workManager.getWorkInfos(videoWorkQuery).get()
            } catch (e: Exception) { emptyList() }

            if (videoWorkInfos.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }) {
                Log.w(TAG, "VideoScenes: Active flags found but no active worker. Resetting scene states.")
                val updatedSceneList = currentSceneList.map { scene ->
                    scene.copy(
                        isGenerating = false,
                        isChangingClothes = false,
                        isGeneratingVideo = false,
                        generationAttempt = 0,
                        clothesChangeAttempt = 0,
                        generationErrorMessage = null
                    )
                }
                videoProjectDataStoreManager.setSceneLinkDataList(updatedSceneList)
            }
        }
    }
    
    // <<< CORREÇÃO PRINCIPAL: Adicionar esta nova função >>>
    private suspend fun checkVideoRenderingState(
        videoGeneratorDataStoreManager: VideoGeneratorDataStoreManager,
        workManager: WorkManager
    ) {
        // Verifica se a flag de renderização (o "lock") ficou ligada
        if (videoGeneratorDataStoreManager.isCurrentlyGeneratingVideo.first()) {
            // Se a flag está ligada, vamos verificar se há um worker realmente em execução
            val renderWorkQuery = WorkQuery.Builder.fromTags(listOf(WorkerTags.VIDEO_RENDER)).build()
            val renderWorkInfos = try {
                workManager.getWorkInfos(renderWorkQuery).get()
            } catch (e: Exception) { emptyList() }

            // Se não houver NENHUM worker de renderização na fila ou rodando...
            if (renderWorkInfos.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }) {
                // ...significa que o app crashou e o lock ficou "preso".
                Log.w(TAG, "VideoRender: State was 'generating' but no active worker found. Resetting lock to false.")
                // Desligamos o lock para permitir que o usuário tente novamente.
                videoGeneratorDataStoreManager.setCurrentlyGenerating(false)
            } else {
                Log.d(TAG, "VideoRender: State is 'generating' and an active worker was found. State is consistent.")
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        Log.d(TAG, "App moved to background (ON_STOP). Saving project state.")
        
        owner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                ProjectPersistenceManager.saveProjectState(context)
                Log.i(TAG, "Estado do projeto salvo com sucesso no onStop.")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao salvar estado do projeto no onStop: ${e.message}", e)
            }
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        Log.d(TAG, "Process ON_CREATE - called once per process.")
    }
}