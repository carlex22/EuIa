package com.carlex.euia.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResult
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlex.euia.data.User
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit

enum class TaskType(val cost: Long) {
    TEXT_STANDARD(1),
    TEXT_PRO(5),
    AUDIO_SINGLE(10),
    AUDIO_MULTI(20),
    IMAGE(10),
    // Adicione outros tipos de tarefa aqui conforme necessário
}

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

    // ------------------ GOOGLE SIGN-IN - NOVO ------------------
    private var googleSignInClient: GoogleSignInClient? = null

    private fun initGoogleSignInClient() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken("COLE_AQUI_SEU_CLIENT_ID_WEB.apps.googleusercontent.com") // TROQUE pelo seu client_id web!
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(getApplication(), gso)
    }

    fun getGoogleSignInIntent(): Intent? {
        if (googleSignInClient == null) initGoogleSignInClient()
        return googleSignInClient?.signInIntent
    }

    fun handleGoogleSignInResult(result: ActivityResult) {
        val data = result.data
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account = task.result
            firebaseAuthWithGoogle(account)
        } catch (e: Exception) {
            _error.value = "Erro no login com Google: ${e.localizedMessage}"
        }
    }

    private fun firebaseAuthWithGoogle(account: GoogleSignInAccount?) {
        if (account == null) {
            _error.value = "Conta do Google inválida"
            return
        }
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val credential = GoogleAuthProvider.getCredential(account.idToken, null)
                val authResult = auth.signInWithCredential(credential).await()
                val firebaseUser = authResult.user
                if (firebaseUser != null) {
                    // Crie documento do usuário caso não exista
                    val userDoc = firestore.collection("users").document(firebaseUser.uid)
                    val snapshot = userDoc.get().await()
                    if (!snapshot.exists()) {
                        // Primeira vez com Google: cria perfil com créditos iniciais
                        val calendar = Calendar.getInstance()
                        calendar.add(Calendar.HOUR, 24)
                        val initialExpiration = Timestamp(calendar.time)
                        val newUser = User(
                            uid = firebaseUser.uid,
                            email = firebaseUser.email ?: "",
                            isPremium = false,
                            creditos = 1000L,
                            dataExpiraCredito = initialExpiration
                        )
                        userDoc.set(newUser).await()
                        Log.d("AuthViewModel", "Perfil Google criado com créditos iniciais.")
                    }
                }
            } catch (e: Exception) {
                _error.value = "Erro no login com Google: ${e.localizedMessage}"
                Log.e("AuthViewModel", "Erro no login Google", e)
            } finally {
                _isLoading.value = false
            }
        }
    }
    // ------------------ FIM GOOGLE SIGN-IN ---------------------

    init {
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            _currentUser.value = user
            if (user != null) {
                firestore.collection("users").document(user.uid)
                    .addSnapshotListener { snapshot, e ->
                        if (e != null) {
                            Log.w("AuthViewModel", "Falha no listener do perfil do usuário.", e)
                            _userProfile.value = null
                            return@addSnapshotListener
                        }
                        if (snapshot != null && snapshot.exists()) {
                            _userProfile.value = snapshot.toObject(User::class.java)
                            Log.d("AuthViewModel", "Perfil do usuário atualizado via listener em tempo real.")
                        } else {
                            Log.w("AuthViewModel", "Documento do usuário não encontrado ou nulo para UID: ${user.uid}")
                            _userProfile.value = null
                        }
                    }
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
                val result = auth.createUserWithEmailAndPassword(email, password).await()
                val firebaseUser = result.user
                if (firebaseUser != null) {
                    val calendar = Calendar.getInstance()
                    calendar.add(Calendar.HOUR, 24)
                    val initialExpiration = Timestamp(calendar.time)
                    val newUser = User(
                        uid = firebaseUser.uid,
                        email = firebaseUser.email ?: "",
                        isPremium = false,
                        creditos = 1000L,
                        dataExpiraCredito = initialExpiration
                    )
                    firestore.collection("users").document(firebaseUser.uid).set(newUser).await()
                    Log.d("AuthViewModel", "Usuário registrado e perfil criado com 1000 créditos iniciais.")
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
                val authResult = auth.signInWithEmailAndPassword(email, password).await()
                Log.d("AuthViewModel", "Usuário logado: $email")
                val firebaseUser = authResult.user
                if (firebaseUser != null) {
                    val userDocRef = firestore.collection("users").document(firebaseUser.uid)
                    val userSnapshot = userDocRef.get().await()
                    val userProfileData = userSnapshot.toObject(User::class.java)

                    if (userProfileData != null && !userProfileData.isPremium) {
                        Log.d("AuthViewModel", "Verificando créditos para usuário 'free'.")
                        val expirationDate = userProfileData.dataExpiraCredito?.toDate()
                        val currentDate = Date()
                        Log.d("AuthViewModel", "currentDate $currentDate - Expira $expirationDate")
                        if (expirationDate == null || currentDate.after(expirationDate)) {
                            Log.i("AuthViewModel", "Créditos expirados. Renovando para 1000...")
                            val calendar = Calendar.getInstance()
                            calendar.time = currentDate
                            calendar.add(Calendar.HOUR, 24)
                            val newExpirationTimestamp = Timestamp(calendar.time)
                            val creditUpdates = mapOf(
                                "creditos" to 1000L,
                                "data_expira_credito" to newExpirationTimestamp
                            )
                            userDocRef.update(creditUpdates).await()
                            Log.i("AuthViewModel", "Créditos renovados. Nova expiração: $newExpirationTimestamp")
                        } else {
                            Log.d("AuthViewModel", "Créditos ainda válidos. Expiração em: $expirationDate")
                        }
                    } else if (userProfileData != null && userProfileData.isPremium) {
                        Log.d("AuthViewModel", "Usuário é Premium, não há renovação de créditos.")
                    }
                }
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: "Erro de login"
                Log.e("AuthViewModel", "Erro de login", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    suspend fun checkAndDeductCredits(taskType: TaskType): Result<Unit> {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            val errorMsg = "Usuário não logado para verificar/deduzir créditos."
            Log.e("AuthViewModel", errorMsg)
            return Result.failure(Exception(errorMsg))
        }
        val cost = taskType.cost
        return try {
            val userDocRef = firestore.collection("users").document(uid)
            val userSnapshot = userDocRef.get().await()
            val user = userSnapshot.toObject(User::class.java)
            if (user == null) {
                val errorMsg = "Perfil do usuário não encontrado no Firestore."
                Log.e("AuthViewModel", errorMsg)
                return Result.failure(Exception(errorMsg))
            }
            if (user.isPremium) {
                Log.i("AuthViewModel", "Usuário é Premium. Não há dedução de créditos para a tarefa ${taskType.name}.")
                return Result.success(Unit)
            }
            if (user.creditos >= cost) {
                userDocRef.update("creditos", FieldValue.increment(-cost)).await()
                Log.i("AuthViewModel", "Créditos suficientes. Deduzidos $cost para a tarefa ${taskType.name}. Saldo estimado: ${user.creditos - cost}")
                Result.success(Unit)
            } else {
                val expirationDate = user.dataExpiraCredito?.toDate()
                val timeRemainingMsg = if (expirationDate != null) {
                    val diff = expirationDate.time - System.currentTimeMillis()
                    if (diff > 0) {
                        val hours = TimeUnit.MILLISECONDS.toHours(diff)
                        val minutes = TimeUnit.MILLISECONDS.toMinutes(diff) % 60
                        "Novos créditos em aproximadamente $hours horas e $minutes minutos."
                    } else {
                        "Tente fazer login novamente para receber novos créditos."
                    }
                } else {
                    "Tente fazer login novamente para receber novos créditos."
                }
                val errorMsg = "Créditos insuficientes para ${taskType.name}. Necessários: $cost, Disponíveis: ${user.creditos}. $timeRemainingMsg"
                Log.w("AuthViewModel", errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            val errorMsg = "Erro no banco de dados ao verificar créditos: ${e.localizedMessage}"
            Log.e("AuthViewModel", errorMsg, e)
            Result.failure(Exception(errorMsg))
        }
    }

    suspend fun refundCredits(taskType: TaskType) {
        val uid = auth.currentUser?.uid ?: return
        val amount = taskType.cost
        Log.w("AuthViewModel", "Iniciando REEMBOLSO de $amount créditos para a tarefa ${taskType.name} do usuário $uid.")
        try {
            val userRef = firestore.collection("users").document(uid)
            userRef.update("creditos", FieldValue.increment(amount)).await()
            Log.i("AuthViewModel", "Reembolso de $amount créditos para o usuário $uid concluído com sucesso.")
        } catch (e: Exception) {
            Log.e("AuthViewModel", "FALHA CRÍTICA: Não foi possível reembolsar $amount créditos para o usuário $uid", e)
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
    
    fun updatePremiumStatus(uid: String, isPremium: Boolean, subscriptionEndDate: Long? = null) {
        viewModelScope.launch {
            try {
                val updates = mutableMapOf<String, Any>(
                    "isPremium" to isPremium
                )
                if (subscriptionEndDate != null) {
                    updates["subscriptionEndDate"] = subscriptionEndDate
                } else {
                    updates["subscriptionEndDate"] = FieldValue.delete()
                }
                firestore.collection("users").document(uid).update(updates).await()
                Log.d("AuthViewModel", "Status premium atualizado para UID: $uid - Premium: $isPremium")
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Erro ao atualizar status premium para UID: $uid", e)
            }
        }
    }
}
