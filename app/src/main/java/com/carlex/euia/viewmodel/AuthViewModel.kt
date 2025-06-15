package com.carlex.euia.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.carlex.euia.data.User // Importe sua data class User
import kotlinx.coroutines.tasks.await // <<<<< ESTA IMPORTAÇÃO É CRUCIAL PARA RESOLVER O ERRO

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()

    private val _currentUser = MutableStateFlow<FirebaseUser?>(null)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    private val _userProfile = MutableStateFlow<User?>(null)
    val userProfile: StateFlow<User?> = _userProfile.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        auth.addAuthStateListener { firebaseAuth ->
            _currentUser.value = firebaseAuth.currentUser
            if (firebaseAuth.currentUser != null) {
                fetchUserProfile(firebaseAuth.currentUser!!.uid)
            } else {
                _userProfile.value = null
            }
        }
    }

    fun register(email: String, password: String) {
        _isLoading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                // O erro de 'await()' vinha desta linha ou de outras semelhantes.
                // Com a importação, deve ser resolvido.
                val result = auth.createUserWithEmailAndPassword(email, password).await()
                val firebaseUser = result.user
                if (firebaseUser != null) {
                    // Crie um perfil de usuário inicial no Firestore
                    val newUser = User(uid = firebaseUser.uid, email = firebaseUser.email ?: "", isPremium = false)
                    firestore.collection("users").document(firebaseUser.uid).set(newUser).await()
                    _userProfile.value = newUser
                    Log.d("AuthViewModel", "Usuário registrado e perfil criado: ${firebaseUser.email}")
                }
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: "Erro de registro"
                Log.e("AuthViewModel", "Erro de registro", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun login(email: String, password: String) {
        _isLoading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                auth.signInWithEmailAndPassword(email, password).await()
                Log.d("AuthViewModel", "Usuário logado: ${email}")
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: "Erro de login"
                Log.e("AuthViewModel", "Erro de login", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun logout() {
        auth.signOut()
        Log.d("AuthViewModel", "Usuário deslogado")
    }

    fun resetPassword(email: String) {
        _isLoading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                auth.sendPasswordResetEmail(email).await()
                Log.d("AuthViewModel", "Email de reset de senha enviado para: $email")
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: "Erro ao enviar email de reset"
                Log.e("AuthViewModel", "Erro ao enviar email de reset", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun fetchUserProfile(uid: String) {
        viewModelScope.launch {
            try {
                val userDoc = firestore.collection("users").document(uid).get().await()
                _userProfile.value = userDoc.toObject(User::class.java)
                Log.d("AuthViewModel", "Perfil do usuário carregado para UID: $uid")
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Erro ao buscar perfil do usuário para UID: $uid", e)
                _userProfile.value = null
            }
        }
    }

    // Funções para atualizar o status premium (chamadas após uma compra bem-sucedida)
    fun updatePremiumStatus(uid: String, isPremium: Boolean, subscriptionEndDate: Long? = null) {
        viewModelScope.launch {
            try {
                val updates = hashMapOf<String, Any>(
                    "isPremium" to isPremium
                )
                if (subscriptionEndDate != null) {
                    updates["subscriptionEndDate"] = subscriptionEndDate
                } else {
                    // Import para FieldValue se esta linha for ativada
                    // updates["subscriptionEndDate"] = com.google.firebase.firestore.FieldValue.delete() // Remove o campo se não houver data
                }

                firestore.collection("users").document(uid).update(updates).await()
                // Recarregar o perfil para refletir a mudança
                fetchUserProfile(uid)
                Log.d("AuthViewModel", "Status premium atualizado para UID: $uid - Premium: $isPremium")
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Erro ao atualizar status premium para UID: $uid", e)
            }
        }
    }

    
}