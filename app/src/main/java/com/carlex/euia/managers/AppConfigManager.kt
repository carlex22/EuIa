// File: euia/managers/AppConfigManager.kt
package com.carlex.euia.managers

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "AppConfigManager"
private const val CONFIG_COLLECTION = "Data_app"
private const val CONFIG_DOCUMENT_ID = "config"

/**
 * Singleton responsável por carregar, armazenar em cache e fornecer acesso
 * às configurações dinâmicas do aplicativo armazenadas no Firestore.
 *
 * Ele observa as alterações no documento de configuração em tempo real e
 * mantém um cache em memória para acesso rápido.
 */
object AppConfigManager {

    private val firestore = FirebaseFirestore.getInstance()
    private val configDocRef = firestore.collection(CONFIG_COLLECTION).document(CONFIG_DOCUMENT_ID)

    // Um cache thread-safe para armazenar os valores de configuração.
    private val configCache = ConcurrentHashMap<String, Any>()

    // Um StateFlow para que a UI (ou outras partes reativas) possa observar o estado do carregamento.
    private val _isLoaded = MutableStateFlow(false)
    val isLoaded = _isLoaded.asStateFlow()

    // Escopo de coroutine para este objeto singleton, que sobrevive ao ciclo de vida de ViewModels.
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        startListeningForUpdates()
    }

    /**
     * Inicia um listener em tempo real no documento de configuração do Firestore.
     * Sempre que o documento é alterado, o cache local é atualizado.
     */
    private fun startListeningForUpdates() {
        managerScope.launch {
            configDocRef.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Erro ao ouvir as atualizações da configuração.", error)
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val data = snapshot.data
                    if (data != null) {
                        Log.i(TAG, "Configuração do app atualizada a partir do Firestore. Total de chaves: ${data.size}")
                        // Limpa o cache antigo e preenche com os novos dados
                        configCache.clear()
                        configCache.putAll(data)
                        // Marca como carregado
                        _isLoaded.value = true
                    } else {
                        Log.w(TAG, "Snapshot da configuração existe, mas não contém dados.")
                    }
                } else {
                    Log.w(TAG, "Documento de configuração '$CONFIG_DOCUMENT_ID' não encontrado na coleção '$CONFIG_COLLECTION'.")
                }
            }
        }
    }

    /**
     * Busca um valor de configuração pela sua chave (key) e o retorna SEMPRE como uma String.
     *
     * @param key A chave da configuração a ser recuperada (ex: "VEO_MODEL_ID").
     * @param defaultValue O valor String a ser retornado se a chave não for encontrada.
     * @return O valor da configuração como String, ou o valor padrão.
     */
    fun get(key: String, defaultValue: String): String {
        return configCache[key]?.toString() ?: defaultValue
    }

    /**
     * Retorna o cache de configuração inteiro como um mapa de String para Any.
     * Útil para a tela de administração exibir todos os valores.
     */
    fun getAllConfigs(): Map<String, Any> {
        return configCache.toMap()
    }

    /**
     * Função de conveniência para obter um valor como String.
     */
    fun getString(key: String, defaultValue: String = ""): String? {
        return get(key, defaultValue) ?: null
    }

    /**
     * Função de conveniência para obter um valor como Int.
     * Converte a String recuperada para Int, retornando o valor padrão em caso de falha.
     */
    fun getInt(key: String, defaultValue: Int = 0): Int? {
        return get(key, defaultValue.toString()).toIntOrNull() ?: null
    }

    /**
     * Função de conveniência para obter um valor como Long.
     * Converte a String recuperada para Long, retornando o valor padrão em caso de falha.
     */
    fun getLong(key: String, defaultValue: Long = 0L): Long? {
        return get(key, defaultValue.toString()).toLongOrNull() ?: null
    }

    /**
     * Função de conveniência para obter um valor como Boolean.
     * Converte a String recuperada para Boolean, retornando o valor padrão em caso de falha.
     */
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean? {
        // "true" (independente de maiúsculas/minúsculas) será true, qualquer outra coisa será false.
        return get(key, defaultValue.toString()).equals("true", ignoreCase = true) ?: false
    }
}