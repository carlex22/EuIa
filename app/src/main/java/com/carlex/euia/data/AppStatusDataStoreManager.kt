// File: euia/data/AppStatusDataStoreManager.kt
package com.carlex.euia.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private const val APP_STATUS_PREFERENCES_NAME = "app_status_preferences"

// Define o DataStore
private val Context.appStatusDataStore: DataStore<Preferences> by preferencesDataStore(
    name = APP_STATUS_PREFERENCES_NAME
)

class AppStatusDataStoreManager(context: Context) {

    private val appContext = context.applicationContext
    private val TAG = "AppStatusDataStore"

    private object PreferencesKeys {
        val IGNORE_OVERLAY_PERMISSION_REQUEST = booleanPreferencesKey("ignore_overlay_permission_request")
        // <<< NOVA CHAVE ADICIONADA >>>
        val IGNORE_ZOMBIE_WORKER_WARNING = booleanPreferencesKey("ignore_zombie_worker_warning")
    }

    val ignoreOverlayPermissionRequest: Flow<Boolean> = appContext.appStatusDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Erro ao ler a preferência de ignorar overlay.", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.IGNORE_OVERLAY_PERMISSION_REQUEST] ?: false
        }

    suspend fun setIgnoreOverlayPermissionRequest(ignore: Boolean) {
        appContext.appStatusDataStore.edit { preferences ->
            preferences[PreferencesKeys.IGNORE_OVERLAY_PERMISSION_REQUEST] = ignore
        }
        Log.d(TAG, "Preferência de ignorar overlay definida como: $ignore")
    }

    // <<< NOVAS FUNÇÕES PARA A SEGUNDA IGNORADA >>>
    val ignoreZombieWorkerWarning: Flow<Boolean> = appContext.appStatusDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "Erro ao ler a preferência de ignorar aviso zumbi.", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.IGNORE_ZOMBIE_WORKER_WARNING] ?: false
        }

    suspend fun setIgnoreZombieWorkerWarning(ignore: Boolean) {
        appContext.appStatusDataStore.edit { preferences ->
            preferences[PreferencesKeys.IGNORE_ZOMBIE_WORKER_WARNING] = ignore
        }
        Log.d(TAG, "Preferência de ignorar aviso zumbi definida como: $ignore")
    }
}