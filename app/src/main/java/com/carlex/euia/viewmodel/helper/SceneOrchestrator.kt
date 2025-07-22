// File: euia/viewmodel/helper/SceneOrchestrator.kt
package com.carlex.euia.viewmodel.helper

import android.content.Context
import android.util.Log
import com.carlex.euia.R
import com.carlex.euia.data.SceneLinkData
import com.carlex.euia.managers.AppConfigManager
import com.carlex.euia.viewmodel.SceneGenerationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "SceneOrchestrator"

/**
 * Define os diferentes estados do processo de orquestração em lote.
 * A UI observa este estado para saber quando exibir diálogos, erros ou finalizar processos.
 */
sealed class BatchGenerationState {
    object Idle : BatchGenerationState()
    data class AwaitingCostConfirmation(val count: Int, val cost: Long, val scenes: List<SceneLinkData>) : BatchGenerationState()
    data class Error(val message: String) : BatchGenerationState()
    object Finished : BatchGenerationState()
}

/**
 * Orquestra os fluxos de trabalho complexos de geração de cenas.
 * Esta classe é o "cérebro" que coordena a chamada à IA para criar a estrutura de cenas,
 * verifica os custos, e comanda o `SceneWorkerManager` para enfileirar as tarefas em lote.
 *
 * @param context O contexto da aplicação para acesso a recursos de string.
 * @param scope O CoroutineScope do ViewModel, garantindo que as tarefas tenham o mesmo ciclo de vida.
 * @param sceneGenerationService O serviço que efetivamente se comunica com a IA para criar o roteiro.
 * @param sceneRepository O repositório para persistir as atualizações na lista de cenas.
 * @param sceneWorkerManager O gerenciador que enfileira os trabalhos em segundo plano.
 * @param creditChecker Uma função lambda de ordem superior para verificar e deduzir créditos do usuário.
 */
class SceneOrchestrator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sceneGenerationService: SceneGenerationService,
    private val sceneRepository: SceneRepository,
    private val sceneWorkerManager: SceneWorkerManager,
    private val creditChecker: suspend (Int) -> Result<Unit>
) {
    private val _batchState = MutableStateFlow<BatchGenerationState>(BatchGenerationState.Idle)
    val batchState = _batchState.asStateFlow()

    private var generationJob: Job? = null

    /**
     * Inicia o fluxo completo de geração da estrutura de cenas.
     * Lança uma corrotina que pode ser cancelada através de `cancelFullSceneStructureGeneration`.
     */
    fun generateFullSceneStructure() {
        generationJob = scope.launch {
            _batchState.value = BatchGenerationState.Idle
            val result = sceneGenerationService.generateSceneStructure()

            result.onSuccess { generatedScenes ->
                sceneRepository.replaceSceneList(generatedScenes)
                val scenesThatNeedImages = generatedScenes.filter { it.promptGeracao?.isNotBlank() == true && it.imagemGeradaPath == null }

                if (scenesThatNeedImages.isNotEmpty()) {
                    val costPerImage = AppConfigManager.getInt("task_COST_DEB_IMG") ?: 10
                    val totalCost = scenesThatNeedImages.size * costPerImage
                    
                    val creditCheckResult = creditChecker(totalCost)
                    
                    if (creditCheckResult.isSuccess) {
                        // Se houver créditos, muda o estado para aguardar a confirmação do usuário na UI.
                        _batchState.value = BatchGenerationState.AwaitingCostConfirmation(
                            count = scenesThatNeedImages.size,
                            cost = totalCost.toLong(),
                            scenes = scenesThatNeedImages
                        )
                    } else {
                        val errorMessage = creditCheckResult.exceptionOrNull()?.message 
                            ?: "context.getString(R.string.error_insufficient_credits_for_batch_generic"
                        _batchState.value = BatchGenerationState.Error(errorMessage)
                    }
                } else {
                    // Se não houver imagens a serem geradas, o processo é considerado finalizado.
                    _batchState.value = BatchGenerationState.Finished
                }
            }.onFailure { error ->
                _batchState.value = BatchGenerationState.Error(error.message ?: "Erro ao gerar a estrutura das cenas.")
            }
        }
    }
    
    /**
     * Chamado após o usuário confirmar o diálogo de custo.
     * Enfileira todos os workers necessários para a geração de imagens em lote.
     */
    fun confirmAndEnqueueBatchGeneration() {
        val currentState = _batchState.value
        if (currentState is BatchGenerationState.AwaitingCostConfirmation) {
            scope.launch {
                currentState.scenes.forEach { scene ->
                    if (!isActive) throw CancellationException("Batch enqueuing cancelled")
                    
                    val prompt = scene.promptGeracao 
                    if (prompt != null) {
                        sceneWorkerManager.enqueueImageGeneration(scene.id, prompt, emptyList())
                        //sceneRepository.updateScene(scene.id) { it.copy(isGenerating = true) }
                        delay(200) // Pequeno delay para não sobrecarregar o enfileiramento do WorkManager
                    } else {
                        Log.w(TAG, "Cena ${scene.id} não tinha prompt para geração, pulando.")
                    }
                }
                _batchState.value = BatchGenerationState.Finished
            }
        }
    }
    
    /**
     * Chamado quando o usuário cancela o diálogo de custo.
     * Reseta o estado para 'Idle'.
     */
    fun cancelBatchGeneration() {
        _batchState.value = BatchGenerationState.Idle
    }
    
    /**
     * Cancela o job de geração de estrutura de cenas, se estiver em andamento.
     */
    fun cancelFullSceneStructureGeneration() {
        if (generationJob?.isActive == true) {
            generationJob?.cancel(CancellationException("Geração de estrutura de cenas cancelada pelo usuário."))
            _batchState.value = BatchGenerationState.Idle
            Log.i(TAG, "Job de geração de estrutura de cenas foi cancelado.")
        }
    }
}