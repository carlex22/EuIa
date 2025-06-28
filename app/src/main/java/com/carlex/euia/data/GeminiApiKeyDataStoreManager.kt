// File: euia/data/GeminiApiKeyDataStoreManager.kt
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

private const val TAG = "GeminiApiKeyDataStore"
private const val USER_GEMINI_API_KEY_PREFERENCES = "user_gemini_api_key_prefs"

// Define o DataStore específico para a chave de API do usuário
private val Context.geminiApiKeyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = USER_GEMINI_API_KEY_PREFERENCES
)

/**
 * Gerencia o armazenamento seguro e persistente da chave de API do Gemini fornecida pelo usuário.
 */
class GeminiApiKeyDataStoreManager(context: Context) {

    private val appContext = context.applicationContext

    // Chave de Preferência para a API Key
    private object PreferencesKeys {
        val USER_API_KEY = stringPreferencesKey("user_gemini_api_key")
    }

    /**
     * Um Flow que emite a chave de API do usuário salva.
     * Emite uma string vazia se nenhuma chave estiver salva.
     */
    val userGeminiApiKey: Flow<String> = appContext.geminiApiKeyDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Erro ao ler a chave de API do usuário do DataStore.", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.USER_API_KEY] ?: ""
        }

    /**
     * Salva ou atualiza a chave de API do Gemini do usuário.
     * Se uma string vazia for passada, a chave é removida.
     *
     * @param apiKey A nova chave de API a ser salva.
     */
    suspend fun saveUserGeminiApiKey(apiKey: String) {
        appContext.geminiApiKeyDataStore.edit { preferences ->
            if (apiKey.isBlank()) {
                preferences.remove(PreferencesKeys.USER_API_KEY)
                Log.d(TAG, "Chave de API do usuário removida do DataStore.")
            } else {
                preferences[PreferencesKeys.USER_API_KEY] = apiKey
                Log.d(TAG, "Chave de API do usuário salva no DataStore.")
            }
        }
    }
}