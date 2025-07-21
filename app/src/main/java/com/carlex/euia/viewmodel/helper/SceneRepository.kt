// File: euia/viewmodel/helper/SceneRepository.kt
package com.carlex.euia.viewmodel.helper

import android.util.Log
import com.carlex.euia.data.SceneLinkData
import com.carlex.euia.data.VideoProjectDataStoreManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "SceneRepository"

/**
 * Repositório que atua como a Única Fonte da Verdade (Single Source of Truth) para a lista de SceneLinkData.
 * Ele encapsula o DataStore e fornece métodos seguros para ler e modificar o estado das cenas do projeto.
 * O ViewModel observa este repositório para obter a lista de cenas e o utiliza para persistir quaisquer alterações.
 *
 * @param dataStoreManager A instância do DataStore que armazena fisicamente os dados das cenas.
 * @param externalScope O CoroutineScope do ViewModel, garantindo que o StateFlow tenha o mesmo ciclo de vida.
 */
class SceneRepository(
    private val dataStoreManager: VideoProjectDataStoreManager,
    externalScope: CoroutineScope
) {
    // Mutex para garantir que as operações de leitura-modificação-escrita na lista de cenas
    // sejam atômicas e seguras contra condições de corrida (race conditions).
    private val updateMutex = Mutex()

    /**
     * Um StateFlow público que expõe a lista atual de cenas.
     * Ele é derivado diretamente do Flow do DataStore, garantindo que a UI sempre
     * veja a versão mais recente e persistida do estado.
     */
    val sceneLinkDataList: StateFlow<List<SceneLinkData>> = dataStoreManager.sceneLinkDataList
        .stateIn(externalScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Atualiza uma única cena na lista de forma segura e atômica.
     *
     * @param sceneId O ID da cena a ser atualizada.
     * @param updateAction Uma função lambda que recebe a cena antiga encontrada e retorna a nova versão modificada.
     */
    suspend fun updateScene(sceneId: String, updateAction: (SceneLinkData) -> SceneLinkData) {
        updateMutex.withLock {
            val currentList = sceneLinkDataList.value
            val newList = currentList.map { scene ->
                if (scene.id == sceneId) {
                    updateAction(scene)
                } else {
                    scene
                }
            }
            // Apenas salva no DataStore se a lista realmente mudou, evitando escritas desnecessárias.
            if (newList != currentList) {
                dataStoreManager.setSceneLinkDataList(newList)
                Log.d(TAG, "Cena $sceneId atualizada no DataStore.")
            }
        }
    }

    /**
     * Substitui completamente a lista de cenas existente por uma nova.
     * Útil para operações como carregar um projeto ou gerar um roteiro de cenas do zero.
     *
     * @param newList A nova lista de [SceneLinkData] a ser persistida.
     */
    suspend fun replaceSceneList(newList: List<SceneLinkData>) {
        updateMutex.withLock {
            dataStoreManager.setSceneLinkDataList(newList)
            Log.i(TAG, "Lista de cenas substituída com ${newList.size} novas cenas.")
        }
    }
    
    /**
     * Limpa completamente a lista de cenas, resultando em uma lista vazia.
     */
    suspend fun clearAllScenes() {
        updateMutex.withLock {
            dataStoreManager.setSceneLinkDataList(emptyList())
            Log.w(TAG, "Todas as cenas foram limpas do DataStore.")
        }
    }
}