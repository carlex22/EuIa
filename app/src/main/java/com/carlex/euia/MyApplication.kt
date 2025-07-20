// File: euia/MyApplication.kt
package com.carlex.euia

import android.app.Application
import android.os.StrictMode
import android.widget.Toast
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.carlex.euia.data.*
import com.carlex.euia.utils.NotificationUtils
import com.carlex.euia.utils.ProjectPersistenceManager
import com.carlex.euia.utils.WorkerTags
import com.carlex.euia.viewmodel.PermissionViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MyApplication : Application() {
    val permissionViewModel: PermissionViewModel by lazy {
        PermissionViewModel(this)
    }

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

class AppLifecycleObserver(private val application: MyApplication) : DefaultLifecycleObserver {
    private val TAG = "AppLifecycleObserver"

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        Log.d(TAG, "App moved to foreground (ON_START). Checking for zombie workers and stale processing states.")
        
        owner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val workManager = WorkManager.getInstance(application)

                // >>>>> INÍCIO DA NOVA LÓGICA DE DETECÇÃO DE ZUMBIS <<<<<
                val criticalTags = listOf(
                    WorkerTags.AUDIO_NARRATIVE,
                    WorkerTags.VIDEO_PROCESSING,
                    WorkerTags.IMAGE_PROCESSING_WORK,
                    WorkerTags.VIDEO_RENDER,
                    WorkerTags.SCENE_PREVIEW_WORK,
                    WorkerTags.URL_IMPORT_WORK,
                    WorkerTags.REF_IMAGE_ANALYSIS,
                    WorkerTags.POST_PRODUCTION
                )
                val workQuery = WorkQuery.Builder.fromTags(criticalTags).build()
                val activeWorkInfos = workManager.getWorkInfos(workQuery).get()
                val hasZombieWorkers = activeWorkInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }

                
                try {
                    // A lógica de limpeza de estado continua sendo importante e executa na sequência
                    val videoPreferences = VideoPreferencesDataStoreManager(application)
                    val audioDataStoreManager = AudioDataStoreManager(application)
                    val videoDataStoreManager = VideoDataStoreManager(application)
                    val videoProjectDataStoreManager = VideoProjectDataStoreManager(application)
                    val videoGeneratorDataStoreManager = VideoGeneratorDataStoreManager(application)

                    checkImageProcessingState(videoDataStoreManager, workManager)
                    checkAudioProcessingState(audioDataStoreManager, workManager)
                    checkVideoScenesState(videoProjectDataStoreManager, workManager) 
                    checkVideoRenderingState(videoGeneratorDataStoreManager, workManager)
                    clearStalePreviewQueuePositions(videoProjectDataStoreManager, workManager)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error during state check/cleanup in onStart", e)
                }
            }
        }
    }
    
    // As funções auxiliares (check...State, onStop, etc.) permanecem as mesmas
    private suspend fun clearStalePreviewQueuePositions(
        videoProjectDataStoreManager: VideoProjectDataStoreManager,
        workManager: WorkManager
    ): Boolean {
        val previewWorkers = workManager.getWorkInfosByTag(WorkerTags.SCENE_PREVIEW_WORK).get()
        if (previewWorkers.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }) {
            val currentSceneList = videoProjectDataStoreManager.sceneLinkDataList.first()
            if (currentSceneList.any { it.previewQueuePosition != null }) {
                Log.w(TAG, "PreviewQueue: Stale queue positions found and no active preview workers. Resetting all to null.")
                val updatedSceneList = currentSceneList.map { scene ->
                    scene.copy(previewQueuePosition = -1)
                }
                videoProjectDataStoreManager.setSceneLinkDataList(updatedSceneList)
                return true
            }
        }
        return false
    }

    private suspend fun checkImageProcessingState(
        videoDataStoreManager: VideoDataStoreManager,
        workManager: WorkManager
    ): Boolean {
        if (videoDataStoreManager.isProcessingImages.first()) {
            val imageWorkQuery = WorkQuery.Builder.fromTags(listOf(WorkerTags.IMAGE_PROCESSING_WORK)).build()
            val imageWorkInfos = try { workManager.getWorkInfos(imageWorkQuery).get() } catch (e: Exception) { emptyList() }

            if (imageWorkInfos.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }) {
                Log.w(TAG, "Images: State was 'processing' but no active worker found. Resetting state.")
                videoDataStoreManager.setIsProcessingImages(false)
                return true
            }
        }
        return false
    }

    private suspend fun checkAudioProcessingState(
        audioDataStoreManager: AudioDataStoreManager,
        workManager: WorkManager
    ): Boolean {
        if (audioDataStoreManager.isAudioProcessing.first()) {
            val audioWorkQuery = WorkQuery.Builder.fromTags(listOf(WorkerTags.AUDIO_NARRATIVE)).build()
            val audioWorkInfos = try { workManager.getWorkInfos(audioWorkQuery).get() } catch (e: Exception) { emptyList() }

            if (audioWorkInfos.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }) {
                Log.w(TAG, "Audio: State was 'processing' but no active worker found. Resetting state.")
                audioDataStoreManager.setIsAudioProcessing(false)
                audioDataStoreManager.setGenerationProgressText("")
                audioDataStoreManager.setGenerationError(null)
                return true
            }
        }
        return false
    }

    private suspend fun checkVideoScenesState(
        videoProjectDataStoreManager: VideoProjectDataStoreManager,
        workManager: WorkManager
    ): Boolean {
        val currentSceneList = try {
            videoProjectDataStoreManager.sceneLinkDataList.first()
        } catch (e: Exception) { emptyList() }

        if (currentSceneList.any { it.isGenerating || it.isChangingClothes || it.isGeneratingVideo }) {
            val videoWorkQuery = WorkQuery.Builder.fromTags(listOf(WorkerTags.VIDEO_PROCESSING)).build()
            val videoWorkInfos = try { workManager.getWorkInfos(videoWorkQuery).get() } catch (e: Exception) { emptyList() }

            if (videoWorkInfos.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }) {
                Log.w(TAG, "VideoScenes: Active flags (isGenerating, etc) found but no active worker. Resetting all scene processing states.")
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
                return true
            }
        }
        return false
    }
    
    private suspend fun checkVideoRenderingState(
        videoGeneratorDataStoreManager: VideoGeneratorDataStoreManager,
        workManager: WorkManager
    ): Boolean {
        if (videoGeneratorDataStoreManager.isCurrentlyGeneratingVideo.first()) {
            val renderWorkQuery = WorkQuery.Builder.fromTags(listOf(WorkerTags.VIDEO_RENDER)).build()
            val renderWorkInfos = try { workManager.getWorkInfos(renderWorkQuery).get() } catch (e: Exception) { emptyList() }

            if (renderWorkInfos.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }) {
                Log.w(TAG, "VideoRender: State was 'generating' but no active worker found. Resetting lock to false.")
                videoGeneratorDataStoreManager.setCurrentlyGenerating(false)
                return true
            } else {
                Log.d(TAG, "VideoRender: State is 'generating' and an active worker was found. State is consistent.")
            }
        }
        return false
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        Log.d(TAG, "App moved to background (ON_STOP). Saving project state.")
        
        owner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                ProjectPersistenceManager.saveProjectState(application)
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