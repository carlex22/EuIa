package com.carlex.euia.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

// Define um nome único para o DataStore do gerador de vídeo
private val Context.videoGeneratorDataStore: DataStore<Preferences> by preferencesDataStore(name = "VideoGenerator")

// Define as Chaves de Preferência para as Configurações do Gerador de Vídeo
object VideoGeneratorPreferencesKeys {
    val GENERATED_VIDEO_TITLE = stringPreferencesKey("generated_video_title")
    val GENERATED_AUDIO_PROMPT = stringPreferencesKey("generated_audio_prompt")
    val GENERATED_MUSIC_PATH = stringPreferencesKey("generated_music_path")
    val GENERATED_NUMBER_OF_SCENES = intPreferencesKey("generated_number_of_scenes")
    val GENERATED_TOTAL_DURATION = doublePreferencesKey("generated_total_duration")
    val FINAL_VIDEO_PATH = stringPreferencesKey("final_video_path")
}

// Classe Gerenciadora para interagir com o DataStore
class VideoGeneratorDataStoreManager(context: Context) {

    private val dataStore = context.applicationContext.videoGeneratorDataStore

    private val DEFAULT_TITLE = ""
    private val DEFAULT_AUDIO_PROMPT = ""
    private val DEFAULT_MUSIC_PATH = ""
    private val DEFAULT_NUMBER_OF_SCENES = 0
    private val DEFAULT_TOTAL_DURATION = 0.0
    private val DEFAULT_VIDEO_PATH = ""

    // --- Flows para Leitura de Preferências ---
    val generatedVideoTitle: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[VideoGeneratorPreferencesKeys.GENERATED_VIDEO_TITLE] ?: DEFAULT_TITLE }

    val generatedAudioPrompt: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[VideoGeneratorPreferencesKeys.GENERATED_AUDIO_PROMPT] ?: DEFAULT_AUDIO_PROMPT }

    val generatedMusicPath: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[VideoGeneratorPreferencesKeys.GENERATED_MUSIC_PATH] ?: DEFAULT_MUSIC_PATH }

    val generatedNumberOfScenes: Flow<Int> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[VideoGeneratorPreferencesKeys.GENERATED_NUMBER_OF_SCENES] ?: DEFAULT_NUMBER_OF_SCENES }

    val generatedTotalDuration: Flow<Double> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[VideoGeneratorPreferencesKeys.GENERATED_TOTAL_DURATION] ?: DEFAULT_TOTAL_DURATION }

    val finalVideoPath: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[VideoGeneratorPreferencesKeys.FINAL_VIDEO_PATH] ?: DEFAULT_VIDEO_PATH }

    // --- Suspend Functions para Escrita de Preferências ---
    suspend fun saveGenerationDataSnapshot(
        title: String,
        audioPrompt: String,
        musicPath: String,
        numberOfScenes: Int,
        totalDuration: Double
    ) {
        dataStore.edit { preferences ->
            preferences[VideoGeneratorPreferencesKeys.GENERATED_VIDEO_TITLE] = title
            preferences[VideoGeneratorPreferencesKeys.GENERATED_AUDIO_PROMPT] = audioPrompt
            preferences[VideoGeneratorPreferencesKeys.GENERATED_MUSIC_PATH] = musicPath
            preferences[VideoGeneratorPreferencesKeys.GENERATED_NUMBER_OF_SCENES] = numberOfScenes
            preferences[VideoGeneratorPreferencesKeys.GENERATED_TOTAL_DURATION] = totalDuration
            preferences[VideoGeneratorPreferencesKeys.FINAL_VIDEO_PATH] = DEFAULT_VIDEO_PATH // Limpa o caminho anterior
        }
    }

    suspend fun setFinalVideoPath(path: String) {
        dataStore.edit { preferences ->
            preferences[VideoGeneratorPreferencesKeys.FINAL_VIDEO_PATH] = path
        }
    }

    suspend fun clearGeneratorState() {
    
        dataStore.edit { preferences ->
            preferences[VideoGeneratorPreferencesKeys.FINAL_VIDEO_PATH] = ""
        }
    
        dataStore.edit { preferences ->
            preferences.clear()
        }
        Log.d("VideoGenDataStore", "Estado do gerador de vídeo limpo.")
    }

    suspend fun clearFinalVideoPath() {
        dataStore.edit { preferences ->
            preferences[VideoGeneratorPreferencesKeys.FINAL_VIDEO_PATH] = ""
        }
    }
}