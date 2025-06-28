// File: euia/viewmodel/AddGeminiApiViewModel.kt
package com.carlex.euia.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.carlex.euia.data.ChaveApiInfo
import com.carlex.euia.managers.ProvisionadorDeChavesApi
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.toObject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TAG = "ApiKeyPoolViewModel"

class AddGeminiApiViewModel : ViewModel() {

    private val firestore = FirebaseFirestore.getInstance()
    private val provisionador = ProvisionadorDeChavesApi(firestore)

    private val _apiKeys = MutableStateFlow<List<ChaveApiInfo>>(emptyList())
    val apiKeys: StateFlow<List<ChaveApiInfo>> = _apiKeys.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _uiEvent = MutableSharedFlow<String>()
    val uiEvent: SharedFlow<String> = _uiEvent.asSharedFlow()

    init {
        loadApiKeys()
    }

    fun loadApiKeys() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val snapshot = firestore.collection("chaves_api_pool")
                    .orderBy("bloqueada_em_audio", Query.Direction.ASCENDING) // Apenas para ter uma ordem
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
                loadApiKeys() // Recarrega a lista após adicionar
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
                loadApiKeys() // Recarrega a lista
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
                loadApiKeys() // Recarrega a lista
            }.onFailure {
                _uiEvent.emit("Falha ao resetar: ${it.localizedMessage}")
            }
            _isLoading.value = false
        }
    }
}