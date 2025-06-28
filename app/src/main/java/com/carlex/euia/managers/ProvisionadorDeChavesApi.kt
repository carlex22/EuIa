// File: euia/managers/ProvisionadorDeChavesApi.kt
package com.carlex.euia.managers

import android.util.Log
import com.carlex.euia.data.ChaveApiInfo // Importa a data class que você já tem
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Date

private const val TAG = "ProvisionadorDeChaves"
private const val COLECAO_CHAVES = "chaves_api_pool"

/**
 * Classe responsável por adicionar e gerenciar chaves de API no pool do Firestore.
 * Esta classe é ideal para ser usada em uma área administrativa do app ou por um script de backend.
 */
class ProvisionadorDeChavesApi(
    private val firestore: FirebaseFirestore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Adiciona uma nova chave de API ao pool no Firestore.
     * Antes de adicionar, verifica se a chave já existe para evitar duplicatas.
     *
     * @param novaChaveApi A string da chave de API a ser adicionada.
     * @return Um [Result] com uma mensagem de sucesso ou falha.
     */
    suspend fun adicionarNovaChave(novaChaveApi: String): Result<String> {
        return withContext(ioDispatcher) {
            if (novaChaveApi.isBlank() || !novaChaveApi.startsWith("AIza")) { // Uma verificação simples
                return@withContext Result.failure(IllegalArgumentException("Formato de chave de API inválido."))
            }

            try {
                // Passo 1: Verificar se a chave já existe
                val queryExistente = firestore.collection(COLECAO_CHAVES)
                    .whereEqualTo("apikey", novaChaveApi)
                    .limit(1)
                    .get()
                    .await()

                if (!queryExistente.isEmpty) {
                    val msg = "A chave que termina em '${novaChaveApi.takeLast(4)}' já existe no banco de dados."
                    Log.w(TAG, msg)
                    return@withContext Result.failure(IllegalStateException(msg))
                }

                // Passo 2: Se não existir, criar o novo documento
                val novaChaveInfo = ChaveApiInfo(
                    apikey = novaChaveApi,
                    emUsoAudio = false,
                    bloqueadaEmAudio = Timestamp(Date()), // Inicia como disponível
                    emUsoImg = false,
                    bloqueadaEmImg = Timestamp(Date()),   // Inicia como disponível
                    emUsoText = false,
                    bloqueadaEmText = Timestamp(Date())  // Inicia como disponível
                )

                firestore.collection(COLECAO_CHAVES).add(novaChaveInfo).await()
                
                val msg = "Chave '${novaChaveApi.takeLast(4)}' adicionada com sucesso ao pool."
                Log.i(TAG, msg)
                Result.success(msg)

            } catch (e: Exception) {
                val msg = "Erro ao adicionar nova chave: ${e.message}"
                Log.e(TAG, msg, e)
                Result.failure(e)
            }
        }
    }

    /**
     * Exclui uma chave de API do pool no Firestore.
     *
     * @param chaveApiParaDeletar A string da chave de API a ser removida.
     * @return Um [Result] com uma mensagem de sucesso ou falha.
     */
    suspend fun deleteChave(chaveApiParaDeletar: String): Result<String> {
        return withContext(ioDispatcher) {
            try {
                val querySnapshot = firestore.collection(COLECAO_CHAVES)
                    .whereEqualTo("apikey", chaveApiParaDeletar)
                    .limit(1)
                    .get()
                    .await()

                if (querySnapshot.isEmpty) {
                    val msg = "Chave '${chaveApiParaDeletar.takeLast(4)}' não encontrada para exclusão."
                    Log.w(TAG, msg)
                    return@withContext Result.failure(NoSuchElementException(msg))
                }

                val documentId = querySnapshot.documents.first().id
                firestore.collection(COLECAO_CHAVES).document(documentId).delete().await()

                val msg = "Chave '${chaveApiParaDeletar.takeLast(4)}' excluída com sucesso."
                Log.i(TAG, msg)
                Result.success(msg)

            } catch (e: Exception) {
                val msg = "Erro ao excluir chave: ${e.message}"
                Log.e(TAG, msg, e)
                Result.failure(e)
            }
        }
    }


    /**
     * Reseta o estado de uma chave específica, marcando-a como totalmente disponível.
     * Útil caso uma chave fique "presa" em estado de uso por algum erro.
     *
     * @param chaveApiParaResetar A chave de API a ser resetada.
     * @return Um [Result] com uma mensagem de sucesso ou falha.
     */
    suspend fun resetarStatusDeChave(chaveApiParaResetar: String): Result<String> {
        return withContext(ioDispatcher) {
            try {
                val query = firestore.collection(COLECAO_CHAVES)
                    .whereEqualTo("apikey", chaveApiParaResetar)
                    .get().await()

                if (query.isEmpty) {
                    return@withContext Result.failure(Exception("Chave não encontrada para resetar."))
                }

                val docId = query.documents.first().id
                val updates = mapOf(
                    "em_uso_audio" to false,
                    "bloqueada_em_audio" to Timestamp(Date()),
                    "em_uso_img" to false,
                    "bloqueada_em_img" to Timestamp(Date()),
                    "em_uso_text" to false,
                    "bloqueada_em_text" to Timestamp(Date())
                )

                firestore.collection(COLECAO_CHAVES).document(docId).update(updates).await()
                val msg = "Chave '${chaveApiParaResetar.takeLast(4)}' resetada para disponível."
                Log.i(TAG, msg)
                Result.success(msg)

            } catch (e: Exception) {
                val msg = "Erro ao resetar chave: ${e.message}"
                Log.e(TAG, msg, e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * Força o reset de TODAS as chaves no pool para o estado disponível.
     * ATENÇÃO: Use com cuidado, pois pode interromper processos em andamento.
     *
     * @return Um [Result] com o número de chaves resetadas ou uma exceção.
     */
    suspend fun resetarTodasAsChaves(): Result<Int> {
        return withContext(ioDispatcher) { 
             Log.w(TAG, "ATENÇÃO: Iniciando reset de TODAS as chaves do pool.")
            try {
                val querySnapshot = firestore.collection(COLECAO_CHAVES).get().await()
                if (querySnapshot.isEmpty) {
                    Log.i(TAG, "Nenhuma chave encontrada no pool para resetar.")
                    return@withContext Result.success(0)
                }

                val batch = firestore.batch()
                val updates = mapOf(
                    "em_uso_audio" to false, "bloqueada_em_audio" to Timestamp(Date()),
                    "em_uso_img" to false, "bloqueada_em_img" to Timestamp(Date()),
                    "em_uso_text" to false, "bloqueada_em_text" to Timestamp(Date())
                )

                for (document in querySnapshot.documents) {
                    batch.update(document.reference, updates)
                }

                batch.commit().await()
                Log.i(TAG, "Reset em lote concluído com sucesso para ${querySnapshot.size()} chaves.")
                Result.success(querySnapshot.size())

            } catch (e: Exception) {
                Log.e(TAG, "Erro ao executar reset em lote de todas as chaves.", e)
                Result.failure(e)
            }
        }
    }
}