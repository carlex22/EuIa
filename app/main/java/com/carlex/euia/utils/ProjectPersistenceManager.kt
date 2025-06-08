package com.carlex.euia.utils

import android.content.Context
import android.util.Log
import com.carlex.euia.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

@Serializable
internal data class ProjectState(
    // De AudioDataStoreManager
    val sexo: String?,
    val emocao: String?,
    val idade: Int?,
    val voz: String?, // Voz principal (legada ou Speaker 1 se não for chat)

    // NOVOS CAMPOS DE ÁUDIO PARA CHAT
    val voiceSpeaker1Audio: String?,
    val voiceSpeaker2Audio: String?,
    val voiceSpeaker3Audio: String?, // Nullable
    val isChatNarrativeAudio: Boolean?,

    val promptAudio: String?,
    val audioPath: String?,
    val legendaPath: String?,
    val videoTituloAudio: String?,
    val videoExtrasAudio: String?,
    val videoImagensReferenciaJsonAudio: String?,
    val userNameCompanyAudio: String?,
    val userProfessionSegmentAudio: String?,
    val userAddressAudio: String?,
    val userLanguageToneAudio: String?,
    val userTargetAudienceAudio: String?,
    val videoObjectiveIntroductionAudio: String?,
    val videoObjectiveVideoAudio: String?,
    val videoObjectiveOutcomeAudio: String?,
    val videoTimeSecondsAudio: String?,
    val videoMusicPathAudio: String?,

    // De RefImageDataStoreManager
    val refObjetoPrompt: String?,
    val refObjetoDetalhesJson: String?,

    // De VideoDataStoreManager
    val imagensReferenciaJsonVideo: String?,

    // De VideoProjectDataStoreManager
    val userInfoProgressProject: Int?,
    val videoInfoProgressProject: Int?,
    val audioInfoProgressProject: Int?,
    val musicPathProject: String?,
    val sceneLinkDataListJsonProject: String?,

    // De VideoPreferencesDataStoreManager
    val videoProjectDirPref: String?, // Apenas o diretório do projeto é parte do estado do PROJETO.
                                      // Outras preferências de vídeo são globais.

    // De VideoGeneratorDataStoreManager
    val generatedVideoTitleGen: String?,
    val generatedAudioPromptGen: String?,
    val generatedMusicPathGen: String?,
    val generatedNumberOfScenesGen: Int?,
    val generatedTotalDurationGen: Double?,
    val finalVideoPathGen: String?
)

object ProjectPersistenceManager {
    private const val TAG = "ProjectPersistence"
    private const val PROJECT_STATE_FILENAME = "euia_project_data.json"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true // Importante para booleanos e outros tipos com padrão
    }

    internal fun getBaseProjectsDirectory(context: Context): File {
        return context.getExternalFilesDir(null)
            ?: throw IllegalStateException("Armazenamento externo específico do app não está disponível.")
    }

    internal fun getProjectDirectory(context: Context, projectDirName: String): File {
        if (projectDirName.isBlank()) {
            Log.w(TAG, "Tentando obter diretório para nome de projeto em branco. Usando 'default_project_if_blank'.")
            val baseDir = getBaseProjectsDirectory(context)
            return File(baseDir, "default_project_if_blank").apply { mkdirs() }
        }
        val baseDir = getBaseProjectsDirectory(context)
        val projectDir = File(baseDir, projectDirName)
        if (!projectDir.exists()) {
            if (!projectDir.mkdirs()) {
                Log.e(TAG, "Falha ao criar diretório do projeto: ${projectDir.absolutePath}")
            } else {
                Log.d(TAG, "Diretório do projeto criado: ${projectDir.absolutePath}")
            }
        }
        return projectDir
    }

    private fun getProjectStateFile(context: Context, projectDirName: String): File {
        val projectDirFile = getProjectDirectory(context, projectDirName)
        return File(projectDirFile, PROJECT_STATE_FILENAME)
    }

    suspend fun saveProjectState(context: Context) = withContext(Dispatchers.IO) {
        val audioDm = AudioDataStoreManager(context)
        val refImageDm = RefImageDataStoreManager(context)
        val videoDm = VideoDataStoreManager(context)
        val videoProjectDm = VideoProjectDataStoreManager(context)
        val videoPrefsDm = VideoPreferencesDataStoreManager(context)
        val videoGeneratorDm = VideoGeneratorDataStoreManager(context)

        val currentProjectDirName = videoPrefsDm.videoProjectDir.first()
        if (currentProjectDirName.isBlank()) {
            Log.e(TAG, "Nome do diretório do projeto atual está em branco. Não é possível salvar o estado.")
            return@withContext
        }
        Log.d(TAG, "Iniciando salvamento do estado do projeto para o diretório: $currentProjectDirName")

        try {
            val currentState = ProjectState(
                sexo = audioDm.sexo.first(),
                emocao = audioDm.emocao.first(),
                idade = audioDm.idade.first(),
                voz = audioDm.voz.first(),

                // SALVANDO NOVOS DADOS DE ÁUDIO
                voiceSpeaker1Audio = audioDm.voiceSpeaker1.first(),
                voiceSpeaker2Audio = audioDm.voiceSpeaker2.first(),
                voiceSpeaker3Audio = audioDm.voiceSpeaker3.first(), // Pode ser null
                isChatNarrativeAudio = audioDm.isChatNarrative.first(),

                promptAudio = audioDm.prompt.first(),
                audioPath = audioDm.audioPath.first(),
                legendaPath = audioDm.legendaPath.first(),
                videoTituloAudio = audioDm.videoTitulo.first(),
                videoExtrasAudio = audioDm.videoExtrasAudio.first(),
                videoImagensReferenciaJsonAudio = audioDm.videoImagensReferenciaJsonAudio.first(),
                userNameCompanyAudio = audioDm.userNameCompanyAudio.first(),
                userProfessionSegmentAudio = audioDm.userProfessionSegmentAudio.first(),
                userAddressAudio = audioDm.userAddressAudio.first(),
                userLanguageToneAudio = audioDm.userLanguageToneAudio.first(),
                userTargetAudienceAudio = audioDm.userTargetAudienceAudio.first(),
                videoObjectiveIntroductionAudio = audioDm.videoObjectiveIntroduction.first(),
                videoObjectiveVideoAudio = audioDm.videoObjectiveVideo.first(),
                videoObjectiveOutcomeAudio = audioDm.videoObjectiveOutcome.first(),
                videoTimeSecondsAudio = audioDm.videoTimeSeconds.first(),
                videoMusicPathAudio = audioDm.videoMusicPath.first(),

                refObjetoPrompt = refImageDm.refObjetoPrompt.first(),
                refObjetoDetalhesJson = refImageDm.refObjetoDetalhesJson.first(),

                imagensReferenciaJsonVideo = videoDm.imagensReferenciaJson.first(),

                userInfoProgressProject = videoProjectDm.userInfoProgress.first(),
                videoInfoProgressProject = videoProjectDm.videoInfoProgress.first(),
                audioInfoProgressProject = videoProjectDm.audioInfoProgress.first(),
                musicPathProject = videoProjectDm.musicPath.first(),
                sceneLinkDataListJsonProject = videoProjectDm.sceneLinkDataJsonString.first(),

                videoProjectDirPref = currentProjectDirName,

                generatedVideoTitleGen = videoGeneratorDm.generatedVideoTitle.first(),
                generatedAudioPromptGen = videoGeneratorDm.generatedAudioPrompt.first(),
                generatedMusicPathGen = videoGeneratorDm.generatedMusicPath.first(),
                generatedNumberOfScenesGen = videoGeneratorDm.generatedNumberOfScenes.first(),
                generatedTotalDurationGen = videoGeneratorDm.generatedTotalDuration.first(),
                finalVideoPathGen = videoGeneratorDm.finalVideoPath.first()
            )

            val jsonString = json.encodeToString(currentState)
            val projectStateFile = getProjectStateFile(context, currentProjectDirName)
            projectStateFile.writeText(jsonString)
            Log.i(TAG, "Estado do projeto salvo com sucesso em: ${projectStateFile.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Falha ao salvar o estado do projeto para '$currentProjectDirName': ${e.message}", e)
        }
    }

    suspend fun loadProjectState(context: Context, projectDirToLoadName: String): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Iniciando carregamento do estado do projeto de: $projectDirToLoadName")
        if (projectDirToLoadName.isBlank()){
            Log.e(TAG, "Nome do projeto para carregar está em branco.")
            return@withContext false
        }
        val projectStateFile = getProjectStateFile(context, projectDirToLoadName)

        if (!projectStateFile.exists()) {
            Log.w(TAG, "Arquivo de estado do projeto não encontrado: ${projectStateFile.absolutePath}")
            return@withContext false
        }

        return@withContext try {
            val jsonString = projectStateFile.readText()
            val loadedState = json.decodeFromString<ProjectState>(jsonString)

            val audioDm = AudioDataStoreManager(context)
            val refImageDm = RefImageDataStoreManager(context)
            val videoDm = VideoDataStoreManager(context)
            val videoProjectDm = VideoProjectDataStoreManager(context)
            val videoPrefsDm = VideoPreferencesDataStoreManager(context)
            val videoGeneratorDm = VideoGeneratorDataStoreManager(context)

            // Restaurar AudioDataStoreManager
            loadedState.sexo?.let { audioDm.setSexo(it) }
            loadedState.emocao?.let { audioDm.setEmocao(it) }
            loadedState.idade?.let { audioDm.setIdade(it) }
            loadedState.voz?.let { audioDm.setVoz(it) } // Voz principal

            // RESTAURANDO NOVOS DADOS DE ÁUDIO
            loadedState.voiceSpeaker1Audio?.let { audioDm.setVoiceSpeaker1(it) }
            loadedState.voiceSpeaker2Audio?.let { audioDm.setVoiceSpeaker2(it) }
            audioDm.setVoiceSpeaker3(loadedState.voiceSpeaker3Audio) // Passa nulo se for nulo
            loadedState.isChatNarrativeAudio?.let { audioDm.setIsChatNarrative(it) }

            loadedState.promptAudio?.let { audioDm.setPrompt(it) }
            loadedState.audioPath?.let { audioDm.setAudioPath(it) }
            loadedState.legendaPath?.let { audioDm.setLegendaPath(it) }
            loadedState.videoTituloAudio?.let { audioDm.setVideoTitulo(it) }
            loadedState.videoExtrasAudio?.let { audioDm.setVideoExtrasAudio(it) }
            loadedState.videoImagensReferenciaJsonAudio?.let { audioDm.setVideoImagensReferenciaJsonAudio(it) }
            loadedState.userNameCompanyAudio?.let { audioDm.setUserNameCompanyAudio(it) }
            loadedState.userProfessionSegmentAudio?.let { audioDm.setUserProfessionSegmentAudio(it) }
            loadedState.userAddressAudio?.let { audioDm.setUserAddressAudio(it) }
            loadedState.userLanguageToneAudio?.let { audioDm.setUserLanguageToneAudio(it) }
            loadedState.userTargetAudienceAudio?.let { audioDm.setUserTargetAudienceAudio(it) }
            loadedState.videoObjectiveIntroductionAudio?.let { audioDm.setVideoObjectiveIntroduction(it) }
            loadedState.videoObjectiveVideoAudio?.let { audioDm.setVideoObjectiveVideo(it) }
            loadedState.videoObjectiveOutcomeAudio?.let { audioDm.setVideoObjectiveOutcome(it) }
            loadedState.videoTimeSecondsAudio?.let { audioDm.setVideoTimeSeconds(it) }
            loadedState.videoMusicPathAudio?.let { audioDm.setVideoMusicPath(it) }

            // Restaurar RefImageDataStoreManager
            loadedState.refObjetoPrompt?.let { refImageDm.setRefObjetoPrompt(it) }
            loadedState.refObjetoDetalhesJson?.let { refImageDm.setRefObjetoDetalhesJson(it) }

            // Restaurar VideoDataStoreManager
            loadedState.imagensReferenciaJsonVideo?.let { videoDm.setImagensReferenciaJson(it) }

            // Restaurar VideoProjectDataStoreManager
            loadedState.userInfoProgressProject?.let { videoProjectDm.setUserInfoProgress(it) }
            loadedState.videoInfoProgressProject?.let { videoProjectDm.setVideoInfoProgress(it) }
            loadedState.audioInfoProgressProject?.let { videoProjectDm.setAudioInfoProgress(it) }
            loadedState.musicPathProject?.let { videoProjectDm.setMusicPath(it) }
            loadedState.sceneLinkDataListJsonProject?.let { jsonList ->
                videoProjectDm.setSceneLinkDataJsonString(jsonList)
            }

            // Restaurar VideoPreferencesDataStoreManager (apenas o nome do diretório do projeto)
            videoPrefsDm.setVideoProjectDir(projectDirToLoadName)
            Log.d(TAG, "Diretório do projeto ativo definido para: $projectDirToLoadName")

            // Restaurar VideoGeneratorDataStoreManager
            if (loadedState.generatedVideoTitleGen != null) {
                videoGeneratorDm.saveGenerationDataSnapshot(
                    title = loadedState.generatedVideoTitleGen,
                    audioPrompt = loadedState.generatedAudioPromptGen ?: "",
                    musicPath = loadedState.generatedMusicPathGen ?: "",
                    numberOfScenes = loadedState.generatedNumberOfScenesGen ?: 0,
                    totalDuration = loadedState.generatedTotalDurationGen ?: 0.0
                )
                loadedState.finalVideoPathGen?.let { videoGeneratorDm.setFinalVideoPath(it) }
            }

            Log.i(TAG, "Estado do projeto '$projectDirToLoadName' carregado e aplicado com sucesso.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao carregar ou aplicar o estado do projeto '$projectDirToLoadName': ${e.message}", e)
            // Considere limpar os DataStores para um estado padrão se o carregamento falhar criticamente
            // Ex: videoPrefsDm.clearAllVideoPreferences(), audioDm.clearAllAudioPreferences(), etc.
            // Isso evitaria que um estado corrompido parcial permanecesse.
            false
        }
    }

    suspend fun listProjectNames(context: Context): List<String> = withContext(Dispatchers.IO) {
        // ... (sem alterações)
        val baseDir = getBaseProjectsDirectory(context)
        if (!baseDir.exists() || !baseDir.isDirectory) {
            return@withContext emptyList()
        }
        Log.d(TAG, "Listando projetos em: ${baseDir.absolutePath}")
        baseDir.listFiles { file ->
            file.isDirectory && File(file, PROJECT_STATE_FILENAME).exists()
        }?.map { it.name }?.sorted() ?: emptyList<String>().also {
            Log.d(TAG, "Nenhum projeto com arquivo de estado encontrado.")
        }
    }

    suspend fun deleteProject(context: Context, projectDirName: String): Boolean = withContext(Dispatchers.IO) {
        // ... (sem alterações)
        if (projectDirName.isBlank()) {
            Log.w(TAG, "Tentativa de excluir projeto com nome em branco.")
            return@withContext false
        }
        Log.i(TAG, "Iniciando exclusão do projeto: $projectDirName")
        val projectDirFile = getProjectDirectory(context, projectDirName)

        return@withContext try {
            if (projectDirFile.exists() && projectDirFile.isDirectory) {
                val deleted = projectDirFile.deleteRecursively()
                if (deleted) {
                    Log.i(TAG, "Projeto '$projectDirName' excluído com sucesso de ${projectDirFile.absolutePath}")
                    val videoPrefsDm = VideoPreferencesDataStoreManager(context)
                    if (videoPrefsDm.videoProjectDir.first() == projectDirName) {
                        videoPrefsDm.clearVideoProjectDir()
                        Log.i(TAG, "Projeto ativo '$projectDirName' limpo das preferências após exclusão.")
                    }
                } else {
                    Log.e(TAG, "Falha ao excluir recursivamente o diretório do projeto '$projectDirName'.")
                }
                deleted
            } else {
                Log.w(TAG, "Projeto '$projectDirName' não encontrado ou não é um diretório para exclusão em ${projectDirFile.absolutePath}.")
                false
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Erro de segurança ao excluir projeto '$projectDirName': ${e.message}", e)
            false
        } catch (e: IOException) {
            Log.e(TAG, "Erro de I/O ao excluir projeto '$projectDirName': ${e.message}", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Erro genérico ao excluir projeto '$projectDirName': ${e.message}", e)
            false
        }
    }
}