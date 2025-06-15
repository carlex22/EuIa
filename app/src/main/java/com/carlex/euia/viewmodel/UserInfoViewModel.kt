package com.carlex.euia.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlex.euia.data.UserInfoDataStoreManager // Mantenha este, pois ainda gerencia Persona
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class UserInfoViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStoreManager = UserInfoDataStoreManager(application) // Gerencia Persona

    // --- StateFlows de Persona (Mantidos) ---
    val userNameCompany: StateFlow<String> = dataStoreManager.userNameCompany
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "")

    val userProfessionSegment: StateFlow<String> = dataStoreManager.userProfessionSegment
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "")

    val userAddress: StateFlow<String> = dataStoreManager.userAddress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "")

    val userLanguageTone: StateFlow<String> = dataStoreManager.userLanguageTone
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "")

    val userTargetAudience: StateFlow<String> = dataStoreManager.userTargetAudience
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), "")

    // --- StateFlows para Preferências de Vídeo REMOVIDOS daqui ---
    // val videoObjectiveIntroduction: StateFlow<String> = ...
    // ... outros StateFlows ...


    // --- Funções de Atualização (Persona - Mantidas) ---
    fun setUserNameCompany(name: String) {
        viewModelScope.launch { dataStoreManager.setUserNameCompany(name) }
    }

    fun setUserProfessionSegment(profession: String) {
        viewModelScope.launch { dataStoreManager.setUserProfessionSegment(profession) }
    }

    fun setUserAddress(address: String) {
        viewModelScope.launch { dataStoreManager.setUserAddress(address) }
    }

    fun setUserLanguageTone(tone: String) {
        viewModelScope.launch { dataStoreManager.setUserLanguageTone(tone) }
    }

    fun setUserTargetAudience(audience: String) {
        viewModelScope.launch { dataStoreManager.setUserTargetAudience(audience) }
    }

    // --- Funções de Atualização para Preferências de Vídeo REMOVIDAS daqui ---
    // fun setVideoObjectiveIntroduction(objective: String) { ... }
    // ... outras funções ...
}