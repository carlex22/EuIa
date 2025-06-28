package com.carlex.euia

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.carlex.euia.data.AudioDataStoreManager
import com.carlex.euia.data.SceneLinkData
import com.carlex.euia.data.VideoProjectDataStoreManager
import com.carlex.euia.services.VideoProgressOverlayService
import com.carlex.euia.utils.ProjectPersistenceManager
import com.carlex.euia.utils.WorkerTags
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.d("MyApplication", "Application onCreate - configurando ProcessLifecycleObserver e Firebase App Check")

        FirebaseApp.initializeApp(this)

        val firebaseAppCheck = FirebaseAppCheck.getInstance()
        val useDebugProvider = BuildConfig.DEBUG

        if (useDebugProvider) {
            Log.i("MyApplication", "Usando DebugAppCheckProviderFactory para App Check.")
            firebaseAppCheck.installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            firebaseAppCheck.getAppCheckToken(false).addOnSuccessListener { tokenResponse ->
                val debugToken = tokenResponse.token
                Log.d("AppCheckDebug", "Debug token: $debugToken")
            }.addOnFailureListener { e ->
                Log.e("AppCheckDebug", "Falha ao obter token de debug.", e)
            }
        } else {
            Log.i("MyApplication", "Usando PlayIntegrityAppCheckProviderFactory para App Check.")
            firebaseAppCheck.installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance()
            )
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(AppLifecycleObserver(this))
    }
}

class AppLifecycleObserver(private val context: Application) : DefaultLifecycleObserver {

    private val TAG = "AppLifecycleObserver"

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        Log.d(TAG, "App em primeiro plano (ON_START). Verificando workers e escondendo overlay.")

        // <<<<< LÓGICA DO OVERLAY: Esconde o ícone quando o app volta para o primeiro plano >>>>>
        val hideIntent = Intent(context, VideoProgressOverlayService::class.java).apply {
            action = VideoProgressOverlayService.ACTION_HIDE
        }
        context.startService(hideIntent)
        // <<<<< FIM DA LÓGICA DO OVERLAY >>>>>

        owner.lifecycleScope.launch {
            val workManager = WorkManager.getInstance(context)
            val audioDataStoreManager = AudioDataStoreManager(context)
            val videoProjectDataStoreManager = VideoProjectDataStoreManager(context)

            try {
                // --- Verificação e Reset para Áudio ---
                val isAudioCurrentlyProcessing = audioDataStoreManager.isAudioProcessing.first()
                if (isAudioCurrentlyProcessing) {
                    val audioWorkInfos = workManager.getWorkInfosByTag(WorkerTags.AUDIO_NARRATIVE).get()
                    val isAudioWorkActive = audioWorkInfos.any {
                        it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
                    }
                    if (!isAudioWorkActive) {
                        Log.d(TAG, "Audio: State is active but work is not. Resetting audio processing state.")
                        audioDataStoreManager.setIsAudioProcessing(false)
                        audioDataStoreManager.setGenerationProgressText("")
                        audioDataStoreManager.setGenerationError(null)
                    }
                }

                // --- Verificação e Reset para Cenas de Vídeo ---
                val currentSceneList = try {
                    videoProjectDataStoreManager.sceneLinkDataList.first()
                } catch (e: IOException) {
                    Log.e(TAG, "VideoScenes: IO Error reading scene link data", e)
                    emptyList()
                }

                val scenesThatNeedReset = currentSceneList.filter { it.isGenerating || it.isChangingClothes || it.isGeneratingVideo }

                if (scenesThatNeedReset.isNotEmpty()) {
                    val videoWorkInfos = workManager.getWorkInfosByTag(WorkerTags.VIDEO_PROCESSING).get()
                    val isVideoWorkActive = videoWorkInfos.any {
                        it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
                    }

                    if (!isVideoWorkActive) {
                        Log.d(TAG, "VideoScenes: Active scene flags found and relevant work is not active. Resetting scene processing states.")
                        val updatedSceneList = currentSceneList.map { scene ->
                            if (scene.isGenerating || scene.isChangingClothes || scene.isGeneratingVideo) {
                                scene.copy(
                                    isGenerating = false,
                                    isChangingClothes = false,
                                    isGeneratingVideo = false,
                                    generationAttempt = 0,
                                    clothesChangeAttempt = 0,
                                    generationErrorMessage = null
                                )
                            } else {
                                scene
                            }
                        }
                        videoProjectDataStoreManager.setSceneLinkDataList(updatedSceneList)
                        Log.d(TAG, "VideoScenes: Scene processing states reset and saved successfully.")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error during state check or reset in onStart", e)
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        Log.d(TAG, "App em segundo plano (ON_STOP). Salvando estado e mostrando overlay se necessário.")

        // <<<<< LÓGICA DO OVERLAY: Mostra o ícone se o serviço estiver ativo >>>>>
        // O serviço só mostrará algo se estiver rodando (iniciado pelo ViewModel)
        val showIntent = Intent(context, VideoProgressOverlayService::class.java).apply {
            action = VideoProgressOverlayService.ACTION_SHOW
        }
        context.startService(showIntent)
        // <<<<< FIM DA LÓGICA DO OVERLAY >>>>>

        // Salvar o estado do projeto quando o app vai para o background
        owner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                ProjectPersistenceManager.saveProjectState(context)
                Log.i(TAG, "Estado do projeto salvo com sucesso no onStop do AppLifecycleObserver.")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao salvar estado do projeto no onStop do AppLifecycleObserver: ${e.message}", e)
            }
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
       super.onCreate(owner)
       Log.d(TAG, "Process ON_CREATE - called once per process. No state reset performed here.")
    }
}