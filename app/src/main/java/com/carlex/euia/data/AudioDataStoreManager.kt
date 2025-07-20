// File: AudioDataStoreManager.kt
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
import java.io.IOException

// Extensão no Context para obter o DataStore
private val Context.audioDataStore: DataStore<Preferences> by preferencesDataStore(name = "audio_preferences")

class AudioDataStoreManager(private val context: Context) {

    private object PreferencesKeys {
        val SEXO = stringPreferencesKey("sexo")
        val EMOCAO = stringPreferencesKey("emocao")
        val IDADE = intPreferencesKey("idade")
        val VOZ = stringPreferencesKey("voz")

        val VOICE_SPEAKER_1 = stringPreferencesKey("voice_speaker_1")
        val VOICE_SPEAKER_2 = stringPreferencesKey("voice_speaker_2")
        val VOICE_SPEAKER_3 = stringPreferencesKey("voice_speaker_3")
        val IS_CHAT_NARRATIVE = booleanPreferencesKey("is_chat_narrative")
        val NARRATIVE_CONTEXT_FILE_PATH = stringPreferencesKey("narrative_context_file_path") // Chave para o arquivo de contexto

        val PROMPT = stringPreferencesKey("prompt")
        val AUDIO_PATH = stringPreferencesKey("audio_path")
        val LEGENDA_PATH = stringPreferencesKey("legenda_path")
        val IS_AUDIO_PROCESSING = booleanPreferencesKey("is_audio_processing")
        val GENERATION_PROGRESS_TEXT = stringPreferencesKey("generation_progress_text")
        val GENERATION_ERROR = stringPreferencesKey("generation_error")
        val VIDEO_TITULO = stringPreferencesKey("video_titulo")
        val VIDEO_EXTRAS_AUDIO = stringPreferencesKey("video_extras_audio")
        val VIDEO_IMAGENS_REFERENCIA_JSON_AUDIO = stringPreferencesKey("video_imagens_referencia_json_audio")
        val USER_NAME_COMPANY_AUDIO = stringPreferencesKey("user_name_company_audio")
        val USER_PROFESSION_SEGMENT_AUDIO = stringPreferencesKey("user_profession_segment_audio")
        val USER_ADDRESS_AUDIO = stringPreferencesKey("user_address_audio")
        val USER_LANGUAGE_TONE_AUDIO = stringPreferencesKey("user_language_tone_audio")
        val USER_TARGET_AUDIENCE_AUDIO = stringPreferencesKey("user_target_audience_audio")
        val VIDEO_OBJECTIVE_INTRODUCTION = stringPreferencesKey("video_objective_introduction")
        val VIDEO_OBJECTIVE_VIDEO = stringPreferencesKey("video_objective_video")
        val VIDEO_OBJECTIVE_OUTCOME = stringPreferencesKey("video_objective_outcome")
        val VIDEO_TIME_SECONDS = stringPreferencesKey("video_time_seconds")
        val VIDEO_MUSIC_PATH = stringPreferencesKey("video_music_path")
    }

    val sexo: Flow<String> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.SEXO] ?: "" }

    val emocao: Flow<String> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.EMOCAO] ?: "" }

    val idade: Flow<Int> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.IDADE] ?: 0 }

    val voz: Flow<String> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.VOZ] ?: "" }

    val voiceSpeaker1: Flow<String> = context.audioDataStore.data
        .catch { if (it is     IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.VOICE_SPEAKER_1] ?: "" }

    val voiceSpeaker2: Flow<String> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.VOICE_SPEAKER_2] ?: "" }

    val voiceSpeaker3: Flow<String?> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.VOICE_SPEAKER_3] }

    val isChatNarrative: Flow<Boolean> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.IS_CHAT_NARRATIVE] ?: false }

    val narrativeContextFilePath: Flow<String> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.NARRATIVE_CONTEXT_FILE_PATH] ?: "" }

    val prompt: Flow<String> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.PROMPT] ?: "" }

    val audioPath: Flow<String> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.AUDIO_PATH] ?: "" }

    val legendaPath: Flow<String> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.LEGENDA_PATH] ?: "" }

    val isAudioProcessing: Flow<Boolean> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.IS_AUDIO_PROCESSING] ?: false }

    val generationProgressText: Flow<String> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.GENERATION_PROGRESS_TEXT] ?: "" }

    val generationError: Flow<String?> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.GENERATION_ERROR] }

    val videoTitulo: Flow<String> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.VIDEO_TITULO] ?: "" }

    val videoExtrasAudio: Flow<String> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.VIDEO_EXTRAS_AUDIO] ?: "" }

    val videoImagensReferenciaJsonAudio: Flow<String> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.VIDEO_IMAGENS_REFERENCIA_JSON_AUDIO] ?: "[]" }

    val userNameCompanyAudio: Flow<String> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.USER_NAME_COMPANY_AUDIO] ?: "" }

    val userProfessionSegmentAudio: Flow<String> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.USER_PROFESSION_SEGMENT_AUDIO] ?: "" }

    val userAddressAudio: Flow<String> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.USER_ADDRESS_AUDIO] ?: "" }

    val userLanguageToneAudio: Flow<String> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.USER_LANGUAGE_TONE_AUDIO] ?: "" }

    val userTargetAudienceAudio: Flow<String> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.USER_TARGET_AUDIENCE_AUDIO] ?: "" }

    val videoObjectiveIntroduction: Flow<String> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.VIDEO_OBJECTIVE_INTRODUCTION] ?: "" }

    val videoObjectiveVideo: Flow<String> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.VIDEO_OBJECTIVE_VIDEO] ?: "" }

    val videoObjectiveOutcome: Flow<String> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.VIDEO_OBJECTIVE_OUTCOME] ?: "" }

    val videoTimeSeconds: Flow<String> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.VIDEO_TIME_SECONDS] ?: "30" }

    val videoMusicPath: Flow<String> = context.audioDataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { preferences -> preferences[PreferencesKeys.VIDEO_MUSIC_PATH] ?: "" }

    suspend fun setSexo(sexo: String) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.SEXO] = sexo } }
    suspend fun setEmocao(emocao: String) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.EMOCAO] = emocao } }
    suspend fun setIdade(idade: Int) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.IDADE] = idade } }
    suspend fun setVoz(voz: String) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.VOZ] = voz } }
    suspend fun setVoiceSpeaker1(voiceName: String) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.VOICE_SPEAKER_1] = voiceName } }
    suspend fun setVoiceSpeaker2(voiceName: String) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.VOICE_SPEAKER_2] = voiceName } }
    suspend fun setVoiceSpeaker3(voiceName: String?) {
        context.audioDataStore.edit { preferences ->
            if (voiceName.isNullOrBlank()) preferences.remove(PreferencesKeys.VOICE_SPEAKER_3)
            else preferences[PreferencesKeys.VOICE_SPEAKER_3] = voiceName
        }
    }
    suspend fun setIsChatNarrative(isChat: Boolean) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.IS_CHAT_NARRATIVE] = isChat } }
    suspend fun setNarrativeContextFilePath(filePath: String) {
        context.audioDataStore.edit { preferences ->
            if (filePath.isBlank()) preferences.remove(PreferencesKeys.NARRATIVE_CONTEXT_FILE_PATH)
            else preferences[PreferencesKeys.NARRATIVE_CONTEXT_FILE_PATH] = filePath
        }
    }
    suspend fun setPrompt(prompt: String) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.PROMPT] = prompt } }
    suspend fun setAudioPath(audioPath: String) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.AUDIO_PATH] = audioPath } }
    suspend fun setLegendaPath(legendaPath: String) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.LEGENDA_PATH] = legendaPath } }
    suspend fun setIsAudioProcessing(isProcessing: Boolean) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.IS_AUDIO_PROCESSING] = isProcessing } }
    suspend fun setGenerationProgressText(text: String) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.GENERATION_PROGRESS_TEXT] = text } }
    suspend fun setGenerationError(error: String?) {
        context.audioDataStore.edit { preferences ->
            if (error != null) preferences[PreferencesKeys.GENERATION_ERROR] = error
            else preferences.remove(PreferencesKeys.GENERATION_ERROR)
        }
    }
    suspend fun setVideoTitulo(titulo: String) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.VIDEO_TITULO] = titulo } }
    suspend fun setVideoExtrasAudio(extras: String) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.VIDEO_EXTRAS_AUDIO] = extras } }
    suspend fun setVideoImagensReferenciaJsonAudio(json: String) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.VIDEO_IMAGENS_REFERENCIA_JSON_AUDIO] = json } }
    suspend fun setUserNameCompanyAudio(name: String) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.USER_NAME_COMPANY_AUDIO] = name } }
    suspend fun setUserProfessionSegmentAudio(segment: String) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.USER_PROFESSION_SEGMENT_AUDIO] = segment } }
    suspend fun setUserAddressAudio(address: String) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.USER_ADDRESS_AUDIO] = address } }
    suspend fun setUserLanguageToneAudio(tone: String) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.USER_LANGUAGE_TONE_AUDIO] = tone } }
    suspend fun setUserTargetAudienceAudio(audience: String) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.USER_TARGET_AUDIENCE_AUDIO] = audience } }
    suspend fun setVideoObjectiveIntroduction(objective: String) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.VIDEO_OBJECTIVE_INTRODUCTION] = objective } }
    suspend fun setVideoObjectiveVideo(objective: String) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.VIDEO_OBJECTIVE_VIDEO] = objective } }
    suspend fun setVideoObjectiveOutcome(objective: String) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.VIDEO_OBJECTIVE_OUTCOME] = objective } }
    suspend fun setVideoTimeSeconds(time: String) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.VIDEO_TIME_SECONDS] = time } }
    suspend fun setVideoMusicPath(path: String) { context.audioDataStore.edit { preferences -> preferences[PreferencesKeys.VIDEO_MUSIC_PATH] = path } }

    suspend fun getInitialSexo(): String {
        return try {
            context.audioDataStore.data
                .map { preferences -> preferences[PreferencesKeys.SEXO] ?: "Female" }
                .first()
        } catch (exception: Exception) {
            Log.e("AudioDataStoreManager", "Erro ao ler initialSexo", exception)
            "Female"
        }
    }

    suspend fun clearAllAudioPreferences() {
        context.audioDataStore.edit { preferences ->
            preferences.clear()
        }
        Log.d("AudioDataStoreManager", "Todas as preferências de áudio foram limpas.")
    }
}