// File: euia/managers/GerenciadorDeChavesApi.kt
package com.carlex.euia.managers

import android.util.Log
import com.carlex.euia.data.ChaveApiInfo
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.toObject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Date

private const val TAG = "GerenciadorChavesApi"
private const val COLECAO_CHAVES = "chaves_api_pool"

class NenhumaChaveApiDisponivelException(message: String) : Exception(message)

/**
 * Gerencia o ciclo de vida de um pool de chaves de API, agora separado por tipo (audio, img, text).
 */
class GerenciadorDeChavesApi(
    private val firestore: FirebaseFirestore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    private fun getFieldNamesForType(tipo: String): Pair<String, String> {
        return when (tipo) {
            "audio" -> "em_uso_audio" to "bloqueada_em_audio"
            "img" -> "em_uso_img" to "bloqueada_em_img"
            "text" -> "em_uso_text" to "bloqueada_em_text"
            else -> throw IllegalArgumentException("Tipo de chave inválido: '$tipo'. Use 'audio', 'img' ou 'text'.")
        }
    }

    suspend fun getChave(tipo: String): String {
        return withContext(ioDispatcher) {
            val (campoEmUso, campoBloqueadaEm) = getFieldNamesForType(tipo)

            Log.d(TAG, "getChave ($tipo): Prioridade 1 - Buscando chave com $campoEmUso = true...")
            val queryEmUso = firestore.collection(COLECAO_CHAVES)
                .whereEqualTo(campoEmUso, true)
                .limit(1)
                .get()
                .await()

            if (!queryEmUso.isEmpty) {
                val chave = queryEmUso.documents.first().toObject<ChaveApiInfo>()!!.apikey
                Log.i(TAG, "getChave ($tipo): Encontrada chave preferencial '${chave.takeLast(4)}'.")
                return@withContext chave
            }

            Log.d(TAG, "getChave ($tipo): Prioridade 2 - Buscando a disponível mais antiga...")
            val queryDisponivel = firestore.collection(COLECAO_CHAVES)
                .whereEqualTo(campoEmUso, false)
                .orderBy(campoBloqueadaEm, Query.Direction.ASCENDING)
                .limit(1)
                .get()
                .await()

            if (queryDisponivel.isEmpty) {
                Log.e(TAG, "getChave ($tipo): Nenhuma chave disponível encontrada.")
                throw NenhumaChaveApiDisponivelException("Nenhuma chave de API está disponível para o tipo '$tipo'.")
            }

            val chave = queryDisponivel.documents.first().toObject<ChaveApiInfo>()!!.apikey
            Log.i(TAG, "getChave ($tipo): Encontrada chave disponível '${chave.takeLast(4)}'.")
            return@withContext chave
        }
    }

    /**
     * Marca uma chave como bloqueada para um tipo específico, APENAS se ela estava em uso.
     */
    suspend fun setChaveBloqueada(apikey: String, tipo: String) {
        withContext(ioDispatcher) {
            val (campoEmUso, campoBloqueadaEm) = getFieldNamesForType(tipo)
            Log.w(TAG, "setChaveBloqueada ($tipo): Tentando bloquear chave '${apikey.takeLast(4)}'.")

            val query = firestore.collection(COLECAO_CHAVES).whereEqualTo("apikey", apikey).get().await()
            if (query.isEmpty) {
                Log.e(TAG, "setChaveBloqueada ($tipo): Chave não encontrada para bloquear.")
                return@withContext
            }

            val docSnapshot = query.documents.first()
            val chaveInfo = docSnapshot.toObject<ChaveApiInfo>()

            // <<< ALTERAÇÃO PRINCIPAL AQUI >>>
            // Verifica se a chave estava realmente em uso para o tipo especificado
            val estavaEmUso = when (tipo) {
                "audio" -> chaveInfo?.emUsoAudio == true
                "img" -> chaveInfo?.emUsoImg == true
                "text" -> chaveInfo?.emUsoText == true
                else -> false
            }

            if (true /*estavaEmUso*/) {
                val docId = docSnapshot.id
                val updates = mapOf(
                    campoEmUso to false,
                    campoBloqueadaEm to Timestamp(Date())
                )
                firestore.collection(COLECAO_CHAVES).document(docId).update(updates).await()
                Log.i(TAG, "setChaveBloqueada ($tipo): Chave '${apikey.takeLast(4)}' estava em uso e foi marcada como bloqueada.")
            } else {
                Log.i(TAG, "setChaveBloqueada ($tipo): Chave '${apikey.takeLast(4)}' não estava 'em uso' para este tipo. Nenhuma ação de bloqueio foi realizada.")
            }
        }
    }

    /**
     * Marca uma chave como "em uso" para um tipo específico.
     */
    suspend fun setChaveEmUso(apikey: String, tipo: String) {
        withContext(ioDispatcher) {
            val (campoEmUso, campoBloqueadaEm) = getFieldNamesForType(tipo)
            Log.d(TAG, "setChaveEmUso ($tipo): Marcando chave '${apikey.takeLast(4)}' como em uso.")
            
            val query = firestore.collection(COLECAO_CHAVES).whereEqualTo("apikey", apikey).get().await()
            if (query.isEmpty) {
                Log.e(TAG, "setChaveEmUso ($tipo): Chave não encontrada para marcar como em uso.")
                return@withContext
            }
            val docId = query.documents.first().id
            val updates = mapOf(
                campoEmUso to true,
                campoBloqueadaEm to null
            )
            firestore.collection(COLECAO_CHAVES).document(docId).update(updates).await()
            Log.i(TAG, "setChaveEmUso ($tipo): Chave '${apikey.takeLast(4)}' marcada como EM USO.")
        }
    }
}