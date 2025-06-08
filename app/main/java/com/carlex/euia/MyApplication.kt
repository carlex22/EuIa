package com.carlex.euia

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.carlex.euia.data.AudioDataStoreManager
import com.carlex.euia.data.VideoProjectDataStoreManager
import com.carlex.euia.data.SceneLinkData
import com.carlex.euia.utils.WorkerTags
// Importações para Firebase App Check
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory // Para DEBUG
// Importações Corrigidas:
import com.carlex.euia.utils.ProjectPersistenceManager // <<<<< ADICIONADO
import kotlinx.coroutines.Dispatchers // <<<<< ADICIONADO

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.d("MyApplication", "Application onCreate - configurando ProcessLifecycleObserver e Firebase App Check")

        // Inicializar Firebase (geralmente já acontece automaticamente se o google-services.json está configurado)
        // É uma boa prática garantir que está inicializado antes do App Check.
        FirebaseApp.initializeApp(this) // Garante a inicialização

        // Inicializar Firebase App Check
        val firebaseAppCheck = FirebaseAppCheck.getInstance()

        // Use o provedor de DEBUG para emuladores/desenvolvimento
        // Use o PlayIntegrity para release/testes em dispositivos reais
        val useDebugProvider = BuildConfig.DEBUG // Ou outra lógica para determinar se é build de debug

        if (useDebugProvider) {
            Log.i("MyApplication", "Usando DebugAppCheckProviderFactory para App Check.")
            firebaseAppCheck.installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            // Obter e logar o token de debug para registro no Firebase Console
            firebaseAppCheck.getAppCheckToken(false).addOnSuccessListener { tokenResponse ->
                val debugToken = tokenResponse.token
                Log.d("AppCheckDebug", "Debug token: $debugToken")
                // Copie este token e adicione em:
                // Firebase Console > App Check > Apps > (Seu App) > Menu de três pontos > Gerenciar tokens de depuração
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
        Log.d(TAG, "App moved to foreground (ON_START). Checking conditions to reset processing states.")

        owner.lifecycleScope.launch {
            val workManager = WorkManager.getInstance(context)
            val audioDataStoreManager = AudioDataStoreManager(context)
            val videoProjectDataStoreManager = VideoProjectDataStoreManager(context)

            try {
                // --- Verificação e Reset para Áudio ---
                val isAudioCurrentlyProcessing = audioDataStoreManager.isAudioProcessing.first()
                Log.d(TAG, "Audio: Current isAudioProcessing state: $isAudioCurrentlyProcessing")

                if (isAudioCurrentlyProcessing) {
                    val audioWorkQuery = WorkQuery.Builder
                        .fromTags(listOf(WorkerTags.AUDIO_NARRATIVE))
                        .build()
                    val audioWorkInfos = try { workManager.getWorkInfos(audioWorkQuery).get() } catch (e: Exception) { Log.e(TAG, "Audio: Error getting work infos", e); emptyList() }

                    val isAudioWorkActive = audioWorkInfos.any {
                        it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
                    }
                    Log.d(TAG, "Audio: Is ${WorkerTags.AUDIO_NARRATIVE} active? $isAudioWorkActive (Infos: ${audioWorkInfos.joinToString { it.state.name }})")

                    if (!isAudioWorkActive) {
                        Log.d(TAG, "Audio: State is active but work is not. Resetting audio processing state.")
                        audioDataStoreManager.setIsAudioProcessing(false)
                        audioDataStoreManager.setGenerationProgressText("")
                        audioDataStoreManager.setGenerationError(null)
                    } else {
                        Log.d(TAG, "Audio: State is active and work is active. Not resetting.")
                    }
                } else {
                    Log.d(TAG, "Audio: State is not active. No reset needed.")
                }

                // --- Verificação e Reset para Cenas de Vídeo ---
                val currentSceneList = try {
                    videoProjectDataStoreManager.sceneLinkDataList.first()
                } catch (e: IOException) {
                    Log.e(TAG, "VideoScenes: IO Error reading scene link data", e)
                    emptyList<SceneLinkData>()
                } catch (e: Exception) {
                    Log.e(TAG, "VideoScenes: Unexpected error reading scene link data", e)
                    emptyList<SceneLinkData>()
                }

                val scenesThatNeedReset = currentSceneList.filter { it.isGenerating || it.isChangingClothes || it.isGeneratingVideo } // Adicionado isGeneratingVideo

                if (scenesThatNeedReset.isNotEmpty()) {
                    Log.d(TAG, "VideoScenes: Found ${scenesThatNeedReset.size} scenes with active flags.")
                    val videoWorkQuery = WorkQuery.Builder
                        .fromTags(listOf(WorkerTags.VIDEO_PROCESSING)) // Tag genérica para todos os workers de vídeo
                        .build()
                    val videoWorkInfos = try { workManager.getWorkInfos(videoWorkQuery).get() } catch (e: Exception) { Log.e(TAG, "VideoScenes: Error getting work infos", e); emptyList() }

                    val isVideoWorkActive = videoWorkInfos.any {
                        it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING
                    }
                    Log.d(TAG, "VideoScenes: Is ${WorkerTags.VIDEO_PROCESSING} (or related) active? $isVideoWorkActive (Infos: ${videoWorkInfos.joinToString { it.state.name }})")

                    if (!isVideoWorkActive) {
                        Log.d(TAG, "VideoScenes: Active scene flags found and relevant work is not active. Resetting scene processing states.")
                        val updatedSceneList = currentSceneList.map { scene ->
                            if (scene.isGenerating || scene.isChangingClothes || scene.isGeneratingVideo) {
                                scene.copy(
                                    isGenerating = false,
                                    isChangingClothes = false,
                                    isGeneratingVideo = false, // Resetar flag de vídeo também
                                    generationAttempt = 0,
                                    clothesChangeAttempt = 0,
                                    generationErrorMessage = null // Limpar mensagem de erro também
                                )
                            } else {
                                scene
                            }
                        }
                        if (updatedSceneList.any { updatedScene ->
                                val original = currentSceneList.find { it.id == updatedScene.id }
                                original?.isGenerating == true || original?.isChangingClothes == true || original?.isGeneratingVideo == true
                            }) {
                            val success = videoProjectDataStoreManager.setSceneLinkDataList(updatedSceneList)
                            if (success) {
                                Log.d(TAG, "VideoScenes: Scene processing states reset and saved successfully.")
                            } else {
                                Log.e(TAG, "VideoScenes: Failed to save updated scene list after resetting states.")
                            }
                        } else {
                            Log.d(TAG, "VideoScenes: No actual changes to scene states were needed after check.")
                        }
                    } else {
                        Log.d(TAG, "VideoScenes: Active scene flags found, but relevant work is also active. Not resetting.")
                    }
                } else {
                    Log.d(TAG, "VideoScenes: No scene processing states active. No reset needed.")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error during state check or reset in onStart", e)
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        Log.d(TAG, "App moved to background (ON_STOP). Saving project state.")
        // Salvar o estado do projeto quando o app vai para o background
        owner.lifecycleScope.launch(Dispatchers.IO) { // <<<<< Dispatchers.IO aqui
            try {
                ProjectPersistenceManager.saveProjectState(context) // <<<<< ProjectPersistenceManager aqui
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