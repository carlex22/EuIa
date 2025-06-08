package com.carlex.euia.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private const val TAG = "RefImageDataStore"
private const val REF_IMAGE_PREFERENCES_NAME = "ref_image_preferences"

// Define o DataStore
private val Context.refImagePreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = REF_IMAGE_PREFERENCES_NAME
)

class RefImageDataStoreManager(context: Context) {

    private val appContext = context.applicationContext

    // Chaves de Preferências
    companion object {
        val REF_OBJETO_PROMPT_KEY = stringPreferencesKey("ref_objeto_prompt")
        val REF_OBJETO_DETALHES_JSON_KEY = stringPreferencesKey("ref_objeto_detalhes_json")
    }

    // --- Ref Objeto Prompt ---
    val refObjetoPrompt: Flow<String> = appContext.refImagePreferencesDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Erro ao ler refObjetoPrompt.", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[REF_OBJETO_PROMPT_KEY] ?: "Descreva o objeto principal." // Valor padrão
        }

    suspend fun setRefObjetoPrompt(prompt: String) {
        appContext.refImagePreferencesDataStore.edit { preferences ->
            preferences[REF_OBJETO_PROMPT_KEY] = prompt
        }
        Log.d(TAG, "RefObjetoPrompt salvo: $prompt")
    }

    // --- Ref Objeto Detalhes JSON ---
    val refObjetoDetalhesJson: Flow<String> = appContext.refImagePreferencesDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Erro ao ler refObjetoDetalhesJson.", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[REF_OBJETO_DETALHES_JSON_KEY] ?: "{}" // Valor padrão (JSON de objeto vazio)
        }

    suspend fun setRefObjetoDetalhesJson(detalhesJson: String) {
        appContext.refImagePreferencesDataStore.edit { preferences ->
            preferences[REF_OBJETO_DETALHES_JSON_KEY] = detalhesJson
        }
        Log.d(TAG, "RefObjetoDetalhesJson salvo. Tamanho: ${detalhesJson.length}")
    }

    /**
     * Limpa todas as preferências de imagem de referência.
     * Após a limpeza, os Flows emitirão seus valores padrão.
     */
    suspend fun clearAllRefImagePreferences() {
        appContext.refImagePreferencesDataStore.edit { preferences ->
            preferences.clear()
        }
        Log.d(TAG, "Todas as preferências de imagem de referência foram limpas.")
    }
}