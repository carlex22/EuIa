package com.carlex.euia.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

// Instância do DataStore (inalterada)
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "UserInfo")

// Objeto de Chaves: Removendo as chaves de preferências de vídeo
object PreferencesKeys {
    // --- Chaves de Persona/Perfil (Mantidas) ---
    val USER_NAME_COMPANY = stringPreferencesKey("user_name_company")
    val USER_PROFESSION_SEGMENT = stringPreferencesKey("user_profession_segment")
    val USER_ADDRESS = stringPreferencesKey("user_address")
    val USER_LANGUAGE_TONE = stringPreferencesKey("user_language_tone")
    val USER_TARGET_AUDIENCE = stringPreferencesKey("user_target_audience")

    // --- Chaves de Preferências de Vídeo REMOVIDAS daqui ---
    // val VIDEO_OBJECTIVE_INTRODUCTION = stringPreferencesKey("video_objective_introduction")
    // val VIDEO_OBJECTIVE_VIDEO = stringPreferencesKey("video_objective_video")
    // val VIDEO_OBJECTIVE_OUTCOME = stringPreferencesKey("video_objective_outcome")
    // val VIDEO_TIME_SECONDS = stringPreferencesKey("video_time_seconds")
    // val VIDEO_FORMAT = stringPreferencesKey("video_format")
}

// Classe Gerenciadora (com leitura/escrita APENAS para chaves de Persona)
class UserInfoDataStoreManager(context: Context) {

    private val settingsDataStore = context.dataStore

    // --- Leitura (Flows de Persona/Perfil - Mantidos) ---
    val userNameCompany: Flow<String> = settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.USER_NAME_COMPANY] ?: "" }

    val userProfessionSegment: Flow<String> = settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.USER_PROFESSION_SEGMENT] ?: "" }

    val userAddress: Flow<String> = settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.USER_ADDRESS] ?: "" }

    val userLanguageTone: Flow<String> = settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.USER_LANGUAGE_TONE] ?: "" }

    val userTargetAudience: Flow<String> = settingsDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.USER_TARGET_AUDIENCE] ?: "" }

    // --- Leituras de Preferências de Vídeo REMOVIDAS daqui ---
    // val videoObjectiveIntroduction: Flow<String> = settingsDataStore.data...
    // ... outros Flows ...

    // --- Escrita (Funções de Persona/Perfil - Mantidas) ---
    suspend fun setUserNameCompany(name: String) {
        settingsDataStore.edit { preferences -> preferences[PreferencesKeys.USER_NAME_COMPANY] = name }
    }

    suspend fun setUserProfessionSegment(profession: String) {
        settingsDataStore.edit { preferences -> preferences[PreferencesKeys.USER_PROFESSION_SEGMENT] = profession }
    }

     suspend fun setUserAddress(address: String) {
        settingsDataStore.edit { preferences -> preferences[PreferencesKeys.USER_ADDRESS] = address }
    }

     suspend fun setUserLanguageTone(tone: String) {
        settingsDataStore.edit { preferences -> preferences[PreferencesKeys.USER_LANGUAGE_TONE] = tone }
    }

     suspend fun setUserTargetAudience(audience: String) {
        settingsDataStore.edit { preferences -> preferences[PreferencesKeys.USER_TARGET_AUDIENCE] = audience }
    }

    // --- Funções de Escrita de Preferências de Vídeo REMOVIDAS daqui ---
    // suspend fun setVideoObjectiveIntroduction(objective: String) { ... }
    // ... outras funções de escrita ...
}