// File: data/VideoProjectDataStoreManager.kt
package com.carlex.euia.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

// Nome do DataStore para o estado do projeto de vídeo
private const val VIDEO_PROJECT_STATE_PREFERENCES_NAME = "VideoProjectState"

// Instância do DataStore para o estado do projeto de vídeo
private val Context.videoProjectStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = VIDEO_PROJECT_STATE_PREFERENCES_NAME
)

private const val TAG_DSM = "VideoProjectDSM" // Tag para logging

// Chaves de Preferência para o estado do projeto
internal object VideoProjectStateKeys { // Tornar internal para uso apenas dentro deste módulo/arquivo
    val USER_INFO_PROGRESS = intPreferencesKey("project_user_info_progress")
    val VIDEO_INFO_PROGRESS = intPreferencesKey("project_video_info_progress")
    val AUDIO_INFO_PROGRESS = intPreferencesKey("project_audio_info_progress")
    val MUSIC_PATH = stringPreferencesKey("project_music_path")
    val SCENE_LINK_DATA_JSON = stringPreferencesKey("project_scene_link_data_json")
}

/**
 * Gerencia o armazenamento persistente do estado específico de um projeto de vídeo,
 * incluindo progresso das etapas, caminho da música e a lista de cenas ([SceneLinkData]).
 * Utiliza Jetpack DataStore para persistência baseada em preferências.
 *
 * @param context O contexto da aplicação.
 */
class VideoProjectDataStoreManager(context: Context) {

    private val appContext = context.applicationContext
    private val dataStore: DataStore<Preferences> = appContext.videoProjectStateDataStore

    @OptIn(ExperimentalSerializationApi::class) // Necessário para ListSerializer
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false // Não é necessário para armazenamento, economiza espaço
        encodeDefaults = true
    }

    init {
        Log.d(TAG_DSM, "VideoProjectDataStoreManager inicializado.")
    }

    /**
     * Flow que emite o progresso da etapa de informações do usuário (0-100).
     * O valor padrão é 0.
     */
    val userInfoProgress: Flow<Int> = dataStore.data
        .catch { exception -> handlePreferenceError(exception, VideoProjectStateKeys.USER_INFO_PROGRESS.name) }
        .map { preferences -> preferences[VideoProjectStateKeys.USER_INFO_PROGRESS] ?: 0 }

    /**
     * Salva o progresso da etapa de informações do usuário.
     * @param progress O valor do progresso (0-100).
     */
    suspend fun setUserInfoProgress(progress: Int) {
        dataStore.edit { preferences -> preferences[VideoProjectStateKeys.USER_INFO_PROGRESS] = progress }
        Log.d(TAG_DSM, "Progresso UserInfo salvo: $progress")
    }

    /**
     * Flow que emite o progresso da etapa de informações de vídeo/imagens (0-100).
     * O valor padrão é 0.
     */
    val videoInfoProgress: Flow<Int> = dataStore.data
        .catch { exception -> handlePreferenceError(exception, VideoProjectStateKeys.VIDEO_INFO_PROGRESS.name) }
        .map { preferences -> preferences[VideoProjectStateKeys.VIDEO_INFO_PROGRESS] ?: 0 }

    /**
     * Salva o progresso da etapa de informações de vídeo/imagens.
     * @param progress O valor do progresso (0-100).
     */
    suspend fun setVideoInfoProgress(progress: Int) {
        dataStore.edit { preferences -> preferences[VideoProjectStateKeys.VIDEO_INFO_PROGRESS] = progress }
        Log.d(TAG_DSM, "Progresso VideoInfo salvo: $progress")
    }

    /**
     * Flow que emite o progresso da etapa de informações de áudio (0-100).
     * O valor padrão é 0.
     */
    val audioInfoProgress: Flow<Int> = dataStore.data
        .catch { exception -> handlePreferenceError(exception, VideoProjectStateKeys.AUDIO_INFO_PROGRESS.name) }
        .map { preferences -> preferences[VideoProjectStateKeys.AUDIO_INFO_PROGRESS] ?: 0 }

    /**
     * Salva o progresso da etapa de informações de áudio.
     * @param progress O valor do progresso (0-100).
     */
    suspend fun setAudioInfoProgress(progress: Int) {
        dataStore.edit { preferences -> preferences[VideoProjectStateKeys.AUDIO_INFO_PROGRESS] = progress }
        Log.d(TAG_DSM, "Progresso AudioInfo salvo: $progress")
    }

    /**
     * Flow que emite o caminho do arquivo de música de fundo selecionado para o projeto.
     * O valor padrão é uma string vazia.
     */
    val musicPath: Flow<String> = dataStore.data
        .catch { exception -> handlePreferenceError(exception, VideoProjectStateKeys.MUSIC_PATH.name) }
        .map { preferences -> preferences[VideoProjectStateKeys.MUSIC_PATH] ?: "" }

    /**
     * Salva o caminho do arquivo de música de fundo.
     * @param path O caminho do arquivo de música.
     */
    suspend fun setMusicPath(path: String) {
        dataStore.edit { preferences -> preferences[VideoProjectStateKeys.MUSIC_PATH] = path }
        Log.d(TAG_DSM, "Caminho da música do projeto salvo: $path")
    }

    /**
     * Flow que emite a lista de [SceneLinkData] desserializada do JSON armazenado.
     * Retorna uma lista vazia se o JSON estiver ausente, vazio, ou houver erro na desserialização.
     */
    @OptIn(ExperimentalSerializationApi::class)
    val sceneLinkDataList: Flow<List<SceneLinkData>> = dataStore.data
        .catch { exception -> handlePreferenceError(exception, VideoProjectStateKeys.SCENE_LINK_DATA_JSON.name) }
        .map { preferences ->
            val jsonString = preferences[VideoProjectStateKeys.SCENE_LINK_DATA_JSON]
            if (jsonString.isNullOrBlank() || jsonString == "[]") {
                emptyList()
            } else {
                try {
                    json.decodeFromString(ListSerializer(SceneLinkData.serializer()), jsonString)
                } catch (e: Exception) {
                    Log.e(TAG_DSM, "Falha ao desserializar SceneLinkData do JSON: '$jsonString'", e)
                    emptyList() // Retorna lista vazia em caso de erro
                }
            }
        }

    /**
     * Flow que emite a string JSON bruta da lista de [SceneLinkData].
     * Útil para persistência do estado do projeto sem necessidade de desserialização/re-serialização imediata.
     * O valor padrão é "[]" (um array JSON vazio).
     */
    val sceneLinkDataJsonString: Flow<String> = dataStore.data
        .catch { exception -> handlePreferenceError(exception, VideoProjectStateKeys.SCENE_LINK_DATA_JSON.name) }
        .map { preferences -> preferences[VideoProjectStateKeys.SCENE_LINK_DATA_JSON] ?: "[]" }


    /**
     * Salva uma lista de [SceneLinkData], serializando-a para JSON antes de armazenar.
     * @param list A lista de [SceneLinkData] a ser salva.
     * @return `true` se a serialização e o salvamento forem bem-sucedidos, `false` caso contrário.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun setSceneLinkDataList(list: List<SceneLinkData>): Boolean {
        return try {
            val jsonString = json.encodeToString(ListSerializer(SceneLinkData.serializer()), list)
            setSceneLinkDataJsonString(jsonString) // Chama a função que salva a string
            Log.d(TAG_DSM, "Lista SceneLinkData salva com ${list.size} itens.")
            true
        } catch (e: Exception) {
            Log.e(TAG_DSM, "Falha ao serializar lista SceneLinkData para salvar: ${e.message}", e)
            false
        }
    }

    /**
     * Salva diretamente a string JSON representando a lista de [SceneLinkData].
     * Esta função é interna e tipicamente usada por mecanismos de restauração de projeto.
     * @param jsonString A string JSON a ser salva.
     */
    suspend fun setSceneLinkDataJsonString(jsonString: String) {
        try {
            dataStore.edit { preferences ->
                preferences[VideoProjectStateKeys.SCENE_LINK_DATA_JSON] = jsonString
            }
            Log.d(TAG_DSM, "String JSON SceneLinkData definida diretamente. Tamanho: ${jsonString.length}")
        } catch (e: Exception) {
            Log.e(TAG_DSM, "Falha ao definir string JSON SceneLinkData: ${e.message}", e)
            // Considerar relançar ou logar mais detalhadamente se necessário
        }
    }

    /**
     * Limpa todas as preferências de estado do projeto armazenadas neste DataStore.
     * Após a limpeza, os Flows emitirão seus valores padrão.
     */
    suspend fun clearProjectState() {
        Log.d(TAG_DSM, "Iniciando limpeza do estado do projeto (VideoProjectState).")
        try {
            dataStore.edit { preferences ->
                preferences.clear()
            }
            Log.i(TAG_DSM, "Estado do projeto (VideoProjectState) limpo com sucesso.")
        } catch (e: Exception) {
            Log.e(TAG_DSM, "Falha ao limpar o estado do projeto (VideoProjectState)", e)
            // Considerar relançar para tratamento de erro mais robusto
        }
    }

    /**
     * Manipulador de exceções genérico para leituras do DataStore.
     * Em caso de IOException, emite preferências vazias para evitar que o Flow quebre.
     * Outras exceções são relançadas.
     * @param exception A exceção capturada.
     * @param preferenceName O nome da preferência que estava sendo lida (para logging).
     */
    private fun handlePreferenceError(exception: Throwable, preferenceName: String): Preferences {
        Log.e(TAG_DSM, "Erro ao ler preferência '$preferenceName'", exception)
        if (exception is IOException) {
            return emptyPreferences()
        } else {
            throw exception
        }
    }
}