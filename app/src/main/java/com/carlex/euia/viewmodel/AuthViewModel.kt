package com.carlex.euia.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlex.euia.R
import com.carlex.euia.data.User
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.firebase.Timestamp
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit

enum class TaskType(val cost: Long) {
    TEXT_STANDARD(1),
    TEXT_PRO(5),
    AUDIO_SINGLE(10),
    AUDIO_MULTI(20),
    IMAGE(10)
}

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val googleSignInClient: GoogleSignInClient

    private val _currentUser = MutableStateFlow<FirebaseUser?>(null)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    private val _userProfile = MutableStateFlow<User?>(null)
    val userProfile: StateFlow<User?> = _userProfile.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _youtubeAccessToken = MutableStateFlow<String?>(null)
    val youtubeAccessToken: StateFlow<String?> = _youtubeAccessToken.asStateFlow()

    companion object {
        const val YOUTUBE_UPLOAD_SCOPE = "https://www.googleapis.com/auth/youtube.upload"
    }

    init {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(application.getString(R.string.default_web_client_id))
            .requestEmail()
            .requestScopes(Scope(YOUTUBE_UPLOAD_SCOPE))
            .build()
        googleSignInClient = GoogleSignIn.getClient(application, gso)

        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            _currentUser.value = user
            if (user != null) {
                GoogleSignIn.getLastSignedInAccount(application)?.let { googleAccount ->
                    viewModelScope.launch {
                        val accessToken = getGoogleAccessToken(googleAccount, application)
                        _youtubeAccessToken.value = accessToken
                        Log.d("AuthViewModel", "Access Token do Google carregado: ${accessToken?.take(8)}...")
                    }
                }
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
                _youtubeAccessToken.value = null
            }
        }
    }

    /**
     * Busca o access token OAuth do Google para o escopo do YouTube.
     * Usa o e-mail da conta Google como identificador (correto para Android!).
     */
    private suspend fun getGoogleAccessToken(
    account: GoogleSignInAccount,
    context: Context
): String? = withContext(Dispatchers.IO) {
    val email = account.email
    if (email.isNullOrBlank()) {
        Log.e("AuthViewModel", "GoogleSignInAccount.email está nulo ou vazio!")
        return@withContext null
    }
    try {
        GoogleAuthUtil.getToken(
            context,
            email, // Agora garantido não-nulo
            "oauth2:$YOUTUBE_UPLOAD_SCOPE"
        )
    } catch (e: Exception) {
        Log.e("AuthViewModel", "Falha ao obter Google OAuth Token: ${e.localizedMessage}", e)
        null
    }
}


    fun getGoogleSignInIntent(): Intent {
        return googleSignInClient.signInIntent
    }

    fun handleGoogleSignInResult(data: Intent?) {
        _isLoading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val account = task.getResult(ApiException::class.java)
                val idToken = account.idToken
                if (idToken != null) {
                    val accessToken = getGoogleAccessToken(account, getApplication())
                    val credential = GoogleAuthProvider.getCredential(idToken, null)
                    signInWithFirebaseAndGoogle(credential, accessToken)
                } else {
                    throw Exception("ID Token do Google é nulo.")
                }
            } catch (e: ApiException) {
                _error.value = "Falha no login do Google: ${e.statusCode} - ${e.message}"
                Log.e("AuthViewModel", "Falha no login do Google", e)
                _isLoading.value = false
            } catch (e: Exception) {
                _error.value = "Erro ao processar login do Google: ${e.localizedMessage}"
                Log.e("AuthViewModel", "Erro geral no handleGoogleSignInResult", e)
                _isLoading.value = false
            }
        }
    }

    private suspend fun signInWithFirebaseAndGoogle(credential: AuthCredential, googleAccessToken: String?) {
        try {
            val authResult = auth.signInWithCredential(credential).await()
            val firebaseUser = authResult.user

            if (firebaseUser != null) {
                val userDocRef = firestore.collection("users").document(firebaseUser.uid)
                val userSnapshot = userDocRef.get().await()

                if (!userSnapshot.exists()) {
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
                    userDocRef.set(newUser).await()
                    Log.d("AuthViewModel", "Novo usuário Google registrado e perfil criado.")
                } else {
                    val userProfileData = userSnapshot.toObject(User::class.java)
                    if (userProfileData != null && !userProfileData.isPremium) {
                        val expirationDate = userProfileData.dataExpiraCredito?.toDate()
                        val currentDate = Date()
                        if (expirationDate == null || currentDate.after(expirationDate)) {
                            val calendar = Calendar.getInstance()
                            calendar.time = currentDate
                            calendar.add(Calendar.HOUR, 24)
                            val newExpirationTimestamp = Timestamp(calendar.time)
                            val creditUpdates = mapOf(
                                "creditos" to 1000L,
                                "dataExpiraCredito" to newExpirationTimestamp
                            )
                            userDocRef.update(creditUpdates).await()
                            Log.i("AuthViewModel", "Créditos 'free' renovados. Nova expiração: $newExpirationTimestamp")
                        }
                    }
                }
                _youtubeAccessToken.value = googleAccessToken
                Log.d("AuthViewModel", "Login com Google para Firebase e YouTube Access Token SUCESSO.")
            }
        } catch (e: Exception) {
            _error.value = e.localizedMessage ?: "Erro de autenticação com Google no Firebase"
            Log.e("AuthViewModel", "Erro de autenticação Google/Firebase", e)
        } finally {
            _isLoading.value = false
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
                        val expirationDate = userProfileData.dataExpiraCredito?.toDate()
                        val currentDate = Date()
                        if (expirationDate == null || currentDate.after(expirationDate)) {
                            val calendar = Calendar.getInstance()
                            calendar.time = currentDate
                            calendar.add(Calendar.HOUR, 24)
                            val newExpirationTimestamp = Timestamp(calendar.time)
                            val creditUpdates = mapOf(
                                "creditos" to 1000L,
                                "dataExpiraCredito" to newExpirationTimestamp
                            )
                            userDocRef.update(creditUpdates).await()
                            Log.i("AuthViewModel", "Créditos renovados. Nova expiração: $newExpirationTimestamp")
                        }
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
                Log.i("AuthViewModel", "Créditos suficientes. Deduzidos $cost para a tarefa ${taskType.name}. Saldo restante estimado: ${user.creditos - cost}")
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

    fun refundCredits(taskType: TaskType) {
        val uid = auth.currentUser?.uid ?: return
        val amount = taskType.cost
        Log.w("AuthViewModel", "Iniciando REEMBOLSO de $amount créditos para a tarefa ${taskType.name} do usuário $uid.")
        viewModelScope.launch {
            try {
                val userRef = firestore.collection("users").document(uid)
                userRef.update("creditos", FieldValue.increment(amount)).await()
                Log.i("AuthViewModel", "Reembolso de $amount créditos para o usuário $uid concluído com sucesso.")
            } catch (e: Exception) {
                Log.e("AuthViewModel", "FALHA CRÍTICA: Não foi possível reembolsar $amount créditos para o usuário $uid", e)
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            try {
                googleSignInClient.signOut().await()
                Log.d("AuthViewModel", "Deslogado do Google Sign-In.")
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Erro ao deslogar do Google Sign-In: ${e.message}", e)
            } finally {
                auth.signOut()
                Log.d("AuthViewModel", "Usuário deslogado do Firebase.")
                _youtubeAccessToken.value = null
            }
        }
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