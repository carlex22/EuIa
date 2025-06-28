// File: euia/data/VideoDataStoreManager.kt
package com.carlex.euia.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
// ... other imports ...

// Define a unique name for the video settings DataStore
private val Context.videoSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "VideoSettings")

// Define Preference Keys for Video Settings
object VideoPreferencesKeys {
    // REMOVED: val VIDEO_TITULO = stringPreferencesKey("video_titulo")
    val VIDEO_IMAGENS_REFERENCIA = stringPreferencesKey("video_imagens_referencia")
    // REMOVED: val VIDEO_MUSIC_PATH = stringPreferencesKey("video_music_path")
    val IS_PROCESSING_IMAGES = booleanPreferencesKey("is_processing_images") // <<<< ADICIONADO
}

// Manager class to interact with the DataStore
class VideoDataStoreManager(context: Context) {

    private val dataStore = context.videoSettingsDataStore

    // --- Flows for Reading Preferences ---

    // Este Flow continua lendo a string JSON bruta do DataStore
    // O ViewModel/Worker será responsável por desserializá-la para List<ImagemReferencia>
    val imagensReferenciaJson: Flow<String> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[VideoPreferencesKeys.VIDEO_IMAGENS_REFERENCIA] ?: "[]" }
    
    // <<<< ADICIONADO >>>>
    val isProcessingImages: Flow<Boolean> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[VideoPreferencesKeys.IS_PROCESSING_IMAGES] ?: false }


    // Esta função salva a string JSON (que representa a lista de ImagemReferencia)
    suspend fun setImagensReferenciaJson(imagensJson: String) {
        dataStore.edit { preferences -> preferences[VideoPreferencesKeys.VIDEO_IMAGENS_REFERENCIA] = imagensJson }
    }
    
    // <<<< ADICIONADO >>>>
    suspend fun setIsProcessingImages(isProcessing: Boolean) {
        dataStore.edit { preferences -> preferences[VideoPreferencesKeys.IS_PROCESSING_IMAGES] = isProcessing }
    }


    // Opcional: Função para limpar todas as configurações
    suspend fun clearAllSettings() {
        dataStore.edit { preferences -> preferences.clear() }
    }
}