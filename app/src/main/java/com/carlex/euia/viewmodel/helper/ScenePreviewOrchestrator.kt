// File: euia/viewmodel/helper/ScenePreviewOrchestrator.kt
package com.carlex.euia.viewmodel.helper

import android.content.Context
import android.util.Log
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery // <<< CORREÇÃO: Import necessário
import com.carlex.euia.data.SceneLinkData
import com.carlex.euia.utils.OverlayManager
import com.carlex.euia.utils.WorkerTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentLinkedQueue

private const val TAG = "ScenePreviewOrchestrator"

class ScenePreviewOrchestrator(
    private val context: Context,
    private val sceneRepository: SceneRepository,
    private val sceneWorkerManager: SceneWorkerManager,
    private val scope: CoroutineScope
) {
    private var sceneObserverJob: Job? = null
    private var previousSceneList: List<SceneLinkData>? = null

    private val workManager = WorkManager.getInstance(context)
    private val previewGenerationQueue = ConcurrentLinkedQueue<String>()
    private val queueMutex = Mutex()
    private val PREVIEW_WORKER_CONCURRENCY_LIMIT = 5

    private val allWorkersObserver = Observer<List<WorkInfo>> { workInfos ->
        val activeWorkers = workInfos.filter { !it.state.isFinished }
        if (activeWorkers.isEmpty()) {
            OverlayManager.hideOverlay(context)
        } else {
            val totalActiveCount = activeWorkers.size
            val currentScenes = sceneRepository.sceneLinkDataList.value
            val totalScenes = currentScenes.size.takeIf { it > 0 } ?: 1
            val completedScenes = currentScenes.count { !it.imagemGeradaPath.isNullOrBlank() }
            val progress = ((completedScenes.toFloat() / totalScenes.toFloat()) * 100).toInt()
            OverlayManager.showOverlay(context, "$totalActiveCount/$totalScenes", progress)
        }
        scope.launch { processPreviewQueue() }
    }

    fun startObserving() {
        if (sceneObserverJob?.isActive == true) return
        Log.d(TAG, "Iniciando observador de cenas e workers.")

        sceneObserverJob = sceneRepository.sceneLinkDataList
            .onEach { newList ->
                val oldList = previousSceneList
                if (oldList == null) {
                    previousSceneList = newList
                    return@onEach
                }

                if (oldList != newList) {
                    val oldMap = oldList.associateBy { it.id }
                    for (newScene in newList) {
                        if (shouldGeneratePreview(oldMap[newScene.id], newScene)) {
                            requestPreviewForScene(newScene.id)
                        }
                    }
                }
                previousSceneList = newList
            }
            .launchIn(scope)

        // <<< INÍCIO DA CORREÇÃO >>>
        val allRelevantTags = listOf(WorkerTags.VIDEO_PROCESSING, WorkerTags.SCENE_PREVIEW_WORK)
        val workQuery = WorkQuery.fromTags(allRelevantTags)
        workManager.getWorkInfosLiveData(workQuery)
            .observeForever(allWorkersObserver)
        // <<< FIM DA CORREÇÃO >>>
    }

    fun requestPreviewForScene(sceneId: String) {
        scope.launch {
            queueMutex.withLock {
                if (!previewGenerationQueue.contains(sceneId)) {
                    previewGenerationQueue.add(sceneId)
                }
            }
            processPreviewQueue()
        }
    }

    private suspend fun processPreviewQueue() {
        queueMutex.withLock {
            if (previewGenerationQueue.isEmpty()) return

            val activePreviewWorkers = workManager
                .getWorkInfosByTag(WorkerTags.SCENE_PREVIEW_WORK)
                .get()
                .count { !it.state.isFinished }

            val slotsAvailable = PREVIEW_WORKER_CONCURRENCY_LIMIT - activePreviewWorkers
            
            if (slotsAvailable > 0) {
                for (i in 0 until slotsAvailable) {
                    val sceneIdToProcess = previewGenerationQueue.poll() ?: break
                    sceneWorkerManager.enqueuePreviewGeneration(sceneIdToProcess)
                }
            }
        }
    }
    
    private fun shouldGeneratePreview(oldScene: SceneLinkData?, newScene: SceneLinkData): Boolean {
        if (newScene.imagemGeradaPath.isNullOrBlank()) return false
        if (oldScene == null) return newScene.videoPreviewPath.isNullOrBlank()
        if (oldScene.imagemGeradaPath.isNullOrBlank() && !newScene.imagemGeradaPath.isNullOrBlank()) return true
        if (oldScene.imagemGeradaPath != newScene.imagemGeradaPath) return true
        if (oldScene.videoPreviewPath != null && newScene.videoPreviewPath == null) return true
        return false
    }

    fun stopObserving() {
        sceneObserverJob?.cancel()
        sceneObserverJob = null
        previousSceneList = null
        
        // <<< INÍCIO DA CORREÇÃO >>>
        // A remoção do observador precisa ser feita do mesmo LiveData
        val allRelevantTags = listOf(WorkerTags.VIDEO_PROCESSING, WorkerTags.SCENE_PREVIEW_WORK)
        val workQuery = WorkQuery.fromTags(allRelevantTags)
        workManager.getWorkInfosLiveData(workQuery)
            .removeObserver(allWorkersObserver)
        // <<< FIM DA CORREÇÃO >>>
        Log.d(TAG, "Observador de cenas para prévias foi parado.")
    }
}