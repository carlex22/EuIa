// File: app/src/main/java/com/carlex/euia/data/VideoPreferencesDataStoreManager.kt
package com.carlex.euia.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
// Removido kotlinx.coroutines.flow.flowOf e emitAll, pois emit() será usado diretamente no catch
import java.io.File
import java.io.IOException

private const val VIDEO_PREFERENCES_NAME = "video_general_preferences"

internal val Context.videoPreferencesDataStoreInstance: DataStore<Preferences> by preferencesDataStore(
    name = VIDEO_PREFERENCES_NAME
)

object VideoPrefKeys {
    val PREFERRED_MALE_VOICE = stringPreferencesKey("preferred_male_voice")
    val PREFERRED_FEMALE_VOICE = stringPreferencesKey("preferred_female_voice")
    val VOICE_PITCH = floatPreferencesKey("voice_pitch")
    val VOICE_RATE = floatPreferencesKey("voice_rate")
    val VIDEO_ASPECT_RATIO = stringPreferencesKey("video_aspect_ratio")
    val VIDEO_LARGURA = intPreferencesKey("video_largura")
    val VIDEO_ALTURA = intPreferencesKey("video_altura")
    val ENABLE_SUBTITLES = booleanPreferencesKey("enable_subtitles")
    val ENABLE_SCENE_TRANSITIONS = booleanPreferencesKey("enable_scene_transitions")
    val ENABLE_ZOOM_PAN = booleanPreferencesKey("enable_zoom_pan")
    val DEFAULT_SCENE_DURATION_SECONDS = floatPreferencesKey("default_scene_duration_seconds")
    val VIDEO_PROJECT_DIR = stringPreferencesKey("video_project_dir")
}

class VideoPreferencesDataStoreManager(context: Context) {
    private val appContext = context.applicationContext
    private val TAG = "VideoPrefsDSM"

    internal val dataStore: DataStore<Preferences> = appContext.videoPreferencesDataStoreInstance

    // Função auxiliar para tratamento de exceções de IO no DataStore
    // Agora o catch usa emit(emptyPreferences()) diretamente
    val preferredMaleVoice: Flow<String> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Error reading preference ${VideoPrefKeys.PREFERRED_MALE_VOICE.name}", exception)
                emit(emptyPreferences()) // Emitir emptyPreferences diretamente
            } else {
                throw exception
            }
        }
        .map { preferences -> preferences[VideoPrefKeys.PREFERRED_MALE_VOICE] ?: "" }

    suspend fun setPreferredMaleVoice(voice: String) {
        dataStore.edit { preferences ->
            preferences[VideoPrefKeys.PREFERRED_MALE_VOICE] = voice
        }
        Log.d(TAG, "Voz Masculina Preferencial definida: $voice")
    }

    val preferredFemaleVoice: Flow<String> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Error reading preference ${VideoPrefKeys.PREFERRED_FEMALE_VOICE.name}", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences -> preferences[VideoPrefKeys.PREFERRED_FEMALE_VOICE] ?: "" }

    suspend fun setPreferredFemaleVoice(voice: String) {
        dataStore.edit { preferences ->
            preferences[VideoPrefKeys.PREFERRED_FEMALE_VOICE] = voice
        }
        Log.d(TAG, "Voz Feminina Preferencial definida: $voice")
    }

    val voicePitch: Flow<Float> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Error reading preference ${VideoPrefKeys.VOICE_PITCH.name}", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences -> preferences[VideoPrefKeys.VOICE_PITCH] ?: 1.0f }

    suspend fun setVoicePitch(pitch: Float) {
        val coercedPitch = pitch.coerceIn(-99.99f, 99.99f)
        dataStore.edit { preferences ->
            preferences[VideoPrefKeys.VOICE_PITCH] = coercedPitch
        }
        Log.d(TAG, "Tom da Voz Padrão definido: $coercedPitch")
    }

    val voiceRate: Flow<Float> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Error reading preference ${VideoPrefKeys.VOICE_RATE.name}", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences -> preferences[VideoPrefKeys.VOICE_RATE] ?: 1.0f }

    suspend fun setVoiceRate(rate: Float) {
        val coercedRate = rate.coerceIn(-99.99f, 99.99f)
        dataStore.edit { preferences ->
            preferences[VideoPrefKeys.VOICE_RATE] = coercedRate
        }
        Log.d(TAG, "Velocidade da Voz Padrão definida: $coercedRate")
    }

    val videoAspectRatio: Flow<String> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Error reading preference ${VideoPrefKeys.VIDEO_ASPECT_RATIO.name}", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences -> preferences[VideoPrefKeys.VIDEO_ASPECT_RATIO] ?: "9:16" }

    internal fun calculateDimensions(aspectRatio: String): Pair<Int, Int> {
        return when (aspectRatio) {
            "1:1" -> Pair(720, 720)
            "16:9" -> Pair(1280, 720)
            "9:16" -> Pair(720, 1280)
            "2:1", "16:8" -> Pair(1280, 640)
            "1:2", "8:16" -> Pair(640, 1280)
            "4:3" -> Pair(960, 720)
            "3:2" -> Pair(1080, 720)
            "2:3" -> Pair(720, 1080)
            else -> {
                Log.w(TAG, "Proporção não reconhecida '$aspectRatio'. Usando dimensões padrão 720x1280 (9:16).")
                Pair(720, 1280)
            }
        }
    }

    suspend fun setVideoAspectRatio(aspectRatio: String) {
        val (largura, altura) = calculateDimensions(aspectRatio)
        dataStore.edit { preferences ->
            preferences[VideoPrefKeys.VIDEO_ASPECT_RATIO] = aspectRatio
            preferences[VideoPrefKeys.VIDEO_LARGURA] = largura
            preferences[VideoPrefKeys.VIDEO_ALTURA] = altura
            Log.d(TAG, "Proporção de Vídeo definida: $aspectRatio -> Largura: $largura, Altura: $altura")
        }
    }

    suspend fun setVideoDimensions(largura: Int, altura: Int) {
        dataStore.edit { preferences ->
            preferences[VideoPrefKeys.VIDEO_LARGURA] = largura
            preferences[VideoPrefKeys.VIDEO_ALTURA] = altura
            Log.d(TAG, "Dimensões de Vídeo definidas diretamente: Largura: $largura, Altura: $altura")
        }
    }

    val videoLargura: Flow<Int?> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Error reading preference ${VideoPrefKeys.VIDEO_LARGURA.name}", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[VideoPrefKeys.VIDEO_LARGURA]
                ?: calculateDimensions(preferences[VideoPrefKeys.VIDEO_ASPECT_RATIO] ?: "9:16").first
        }

    val videoAltura: Flow<Int?> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Error reading preference ${VideoPrefKeys.VIDEO_ALTURA.name}", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[VideoPrefKeys.VIDEO_ALTURA]
                ?: calculateDimensions(preferences[VideoPrefKeys.VIDEO_ASPECT_RATIO] ?: "9:16").second
        }

    val enableSubtitles: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Error reading preference ${VideoPrefKeys.ENABLE_SUBTITLES.name}", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences -> preferences[VideoPrefKeys.ENABLE_SUBTITLES] ?: true }

    suspend fun setEnableSubtitles(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[VideoPrefKeys.ENABLE_SUBTITLES] = enabled
        }
        Log.d(TAG, "Habilitar Legendas definido: $enabled")
    }

    val enableSceneTransitions: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Error reading preference ${VideoPrefKeys.ENABLE_SCENE_TRANSITIONS.name}", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences -> preferences[VideoPrefKeys.ENABLE_SCENE_TRANSITIONS] ?: true }

    suspend fun setEnableSceneTransitions(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[VideoPrefKeys.ENABLE_SCENE_TRANSITIONS] = enabled
        }
        Log.d(TAG, "Habilitar Transições definido: $enabled")
    }

    val enableZoomPan: Flow<Boolean> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Error reading preference ${VideoPrefKeys.ENABLE_ZOOM_PAN.name}", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences -> preferences[VideoPrefKeys.ENABLE_ZOOM_PAN] ?: false }

    suspend fun setEnableZoomPan(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[VideoPrefKeys.ENABLE_ZOOM_PAN] = enabled
        }
        Log.d(TAG, "Habilitar ZoomPan definido: $enabled")
    }

    val defaultSceneDurationSeconds: Flow<Float> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Error reading preference ${VideoPrefKeys.DEFAULT_SCENE_DURATION_SECONDS.name}", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences -> preferences[VideoPrefKeys.DEFAULT_SCENE_DURATION_SECONDS] ?: 5.0f }

    suspend fun setDefaultSceneDurationSeconds(duration: Float) {
        val coercedDuration = duration.coerceAtLeast(0.1f)
        dataStore.edit { preferences ->
            preferences[VideoPrefKeys.DEFAULT_SCENE_DURATION_SECONDS] = coercedDuration
        }
        Log.d(TAG, "Duração Padrão da Cena definida: $coercedDuration segundos")
    }

    val videoProjectDir: Flow<String> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Error reading preference ${VideoPrefKeys.VIDEO_PROJECT_DIR.name}", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences -> preferences[VideoPrefKeys.VIDEO_PROJECT_DIR] ?: getDefaultProjectDirName() }

    suspend fun setVideoProjectDir(dirName: String) {
        val sanitizedDirName = dirName.ifBlank { getDefaultProjectDirName() }
        dataStore.edit { preferences ->
            preferences[VideoPrefKeys.VIDEO_PROJECT_DIR] = sanitizedDirName
        }
        Log.d(TAG, "Nome do Diretório do Projeto Ativo definido: $sanitizedDirName")
    }

    private fun getDefaultProjectDirName(): String {
        return "EUIA_Default_Project"
    }
    
    suspend fun getCurrentProjectPhysicalDir(): File {
        val baseAppDir = appContext.getExternalFilesDir(null)
            ?: appContext.filesDir
        val activeProjectName = videoProjectDir.first()
        val projectDir = File(baseAppDir, activeProjectName)
        if (!projectDir.exists()) {
            projectDir.mkdirs()
        }
        return projectDir
    }

    suspend fun clearAllVideoPreferences() {
        dataStore.edit { preferences ->
            preferences.clear()
        }
        Log.d(TAG, "Todas as preferências de vídeo foram limpas.")
    }

    suspend fun clearVideoProjectDir() {
        dataStore.edit { preferences ->
            preferences.remove(VideoPrefKeys.VIDEO_PROJECT_DIR)
            val defaultAspectRatio = "9:16"
            val (defaultLargura, defaultAltura) = calculateDimensions(defaultAspectRatio)
            preferences[VideoPrefKeys.VIDEO_ASPECT_RATIO] = defaultAspectRatio
            preferences[VideoPrefKeys.VIDEO_LARGURA] = defaultLargura
            preferences[VideoPrefKeys.VIDEO_ALTURA] = defaultAltura
        }
        Log.d(TAG, "${VideoPrefKeys.VIDEO_PROJECT_DIR.name} limpo e dimensões resetadas para padrão.")
    }

    companion object {
        const val DEFAULT_VIDEO_WIDTH_FALLBACK = 720
        const val DEFAULT_VIDEO_HEIGHT_FALLBACK = 1280
    }
}
