// File: euia/viewmodel/AddGeminiApiViewModel.kt
package com.carlex.euia.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carlex.euia.data.ChaveApiInfo
import com.carlex.euia.managers.AppConfigManager
import com.carlex.euia.managers.ProvisionadorDeChavesApi
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.toObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TAG = "AdminViewModel"

/**
 * Estado da UI para a tela de configuração.
 */
sealed class ConfigState {
    object Loading : ConfigState()
    data class Success(val config: List<Pair<String, String>>) : ConfigState()
    data class Error(val message: String) : ConfigState()
}

class AddGeminiApiViewModel : ViewModel() {

    private val firestore = FirebaseFirestore.getInstance()
    private val provisionador = ProvisionadorDeChavesApi(firestore)

    // --- StateFlows para Gerenciamento de Chaves de API ---
    private val _apiKeys = MutableStateFlow<List<ChaveApiInfo>>(emptyList())
    val apiKeys: StateFlow<List<ChaveApiInfo>> = _apiKeys.asStateFlow()

    // --- StateFlows para Configuração do App ---
    private val _appConfigState = MutableStateFlow<ConfigState>(ConfigState.Loading)
    val appConfigState: StateFlow<ConfigState> = _appConfigState.asStateFlow()

    private val _editableConfig = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val editableConfig: StateFlow<List<Pair<String, String>>> = _editableConfig.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _uiEvent = MutableSharedFlow<String>()
    val uiEvent: SharedFlow<String> = _uiEvent.asSharedFlow()

    init {
        loadApiKeys()
        loadAppConfigForEditing()
    }

    /**
     * Carrega a configuração do AppConfigManager para a lista editável da UI.
     * Esta função é chamada na inicialização do ViewModel.
     */
    fun loadAppConfigForEditing() {
        viewModelScope.launch {
            _appConfigState.value = ConfigState.Loading
            // Observa o estado de carregamento do manager
            AppConfigManager.isLoaded.collect { isLoaded ->
                if (true) {
                    val configMap = AppConfigManager.getAllConfigs()
                    if (configMap.isNotEmpty()) {
                        val configList = configMap.map { (key, value) -> key to value.toString() }.sortedBy { it.first }
                        _editableConfig.value = configList
                        _appConfigState.value = ConfigState.Success(configList)
                    } else {
                        val errorMsg = "Configuração não encontrada no Firestore."
                        _appConfigState.value = ConfigState.Error(errorMsg)
                    }
                }
            }
        }
    }

    /**
     * Atualiza um valor na lista de configuração editável na memória.
     */
    fun updateEditableConfigValue(key: String, newValue: String) {
        val currentList = _editableConfig.value.toMutableList()
        val index = currentList.indexOfFirst { it.first == key }
        if (index != -1) {
            currentList[index] = key to newValue
            _editableConfig.value = currentList
        }
    }

    /**
     * Salva a lista de configuração editável de volta no Firestore.
     */
    fun saveEditableConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                // Converte os valores de String para seus tipos numéricos/booleanos apropriados
                val configMapToSave = _editableConfig.value.associate { (key, value) ->
                    val convertedValue: Any = value.toLongOrNull() ?: value.toDoubleOrNull() ?: value.toBooleanStrictOrNull() ?: value
                    key to convertedValue
                }
                
                firestore.collection("Data_app").document("config").set(configMapToSave).await()
                
                _uiEvent.emit("Configuração salva com sucesso!")
                
            } catch (e: Exception) {
                val errorMessage = "Falha ao salvar configuração: ${e.message}"
                Log.e(TAG, errorMessage, e)
                _uiEvent.emit(errorMessage)
            } finally {
                _isLoading.value = false
            }
        }
    }

    // --- Funções de gerenciamento de chaves (sem alterações) ---
    fun loadApiKeys() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val snapshot = firestore.collection("chaves_api_pool")
                    .orderBy("bloqueada_em_audio", Query.Direction.ASCENDING)
                    .get()
                    .await()
                _apiKeys.value = snapshot.documents.mapNotNull { it.toObject<ChaveApiInfo>() }
                Log.d(TAG, "Carregadas ${_apiKeys.value.size} chaves.")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao carregar chaves de API", e)
                _uiEvent.emit("Erro ao carregar chaves: ${e.localizedMessage}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun addApiKey(apiKey: String) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = provisionador.adicionarNovaChave(apiKey)
            result.onSuccess {
                _uiEvent.emit(it)
                loadApiKeys()
            }.onFailure {
                _uiEvent.emit("Falha: ${it.localizedMessage}")
            }
            _isLoading.value = false
        }
    }

    fun deleteApiKey(apiKey: ChaveApiInfo) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = provisionador.deleteChave(apiKey.apikey)
            result.onSuccess {
                _uiEvent.emit(it)
                loadApiKeys()
            }.onFailure {
                _uiEvent.emit("Falha ao deletar: ${it.localizedMessage}")
            }
            _isLoading.value = false
        }
    }

    fun resetApiKey(apiKey: ChaveApiInfo) {
        viewModelScope.launch {
            _isLoading.value = true
            val result = provisionador.resetarStatusDeChave(apiKey.apikey)
            result.onSuccess {
                _uiEvent.emit(it)
                loadApiKeys()
            }.onFailure {
                _uiEvent.emit("Falha ao resetar: ${it.localizedMessage}")
            }
            _isLoading.value = false
        }
    }
}