// File: euia/viewmodel/AuthViewModel.kt
package com.carlex.euia.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlex.euia.R
import com.carlex.euia.data.User // Importa a data class User
import com.carlex.euia.managers.AppConfigManager // Importa o manager de configuração
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.text.toIntOrNull
import com.carlex.euia.BuildConfig
import com.carlex.euia.data.UserInfoDataStoreManager
import kotlinx.coroutines.delay

/**
 * Exceção customizada para ser lançada quando uma configuração essencial não é encontrada.
 */
class MissingConfigurationException(message: String) : IllegalStateException(message)

// Enum TaskType agora obtém os custos dinamicamente do AppConfigManager.
enum class TaskType {
    TEXT_STANDARD,
    TEXT_PRO,
    AUDIO_SINGLE,
    AUDIO_MULTI,
    IMAGE,
    CREDIT_FREE;

    val cost: Int
        get() = when (this) {
            TEXT_STANDARD -> AppConfigManager.getInt("task_COST_DEB_TEXT") ?: 1
            TEXT_PRO -> AppConfigManager.getInt("task_COST_DEB_TEXT_PRO") ?: 5
            AUDIO_SINGLE -> AppConfigManager.getInt("task_COST_DEB_AUD") ?: 10
            AUDIO_MULTI -> AppConfigManager.getInt("task_COST_DEB_AUD_MULT") ?: 20
            IMAGE -> AppConfigManager.getInt("task_COST_DEB_IMG") ?: 10
            CREDIT_FREE -> AppConfigManager.getInt("task_COST_CRE_FREE") ?: 100
        }
}

class AuthViewModel(application: Application) : AndroidViewModel(application) {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
    private lateinit var googleSignInClient: GoogleSignInClient
    private val appContext: Context = application.applicationContext

    private val userInfoDataStoreManager = UserInfoDataStoreManager(application)

    // --- Propriedades que leem do AppConfigManager. Lançam exceção se a chave não existir. ---
    private val cryptoMasterKey: String get() = AppConfigManager.getString("crypto_MASTER_KEY") ?: ""
    private val fbKeyWallet: String get() = AppConfigManager.getString("firebase_FIELD_CREDITS") ?: ""
    private val fbKeyCredExp: String get() = AppConfigManager.getString("firebase_FIELD_CREDIT_EXPIRY") ?: ""
    private val fbCollectionUsers: String get() = AppConfigManager.getString("firebase_COLLECTION_USERS") ?: ""
    private val fbKeyIsPremium: String get() = AppConfigManager.getString("firebase_FIELD_IS_PREMIUM") ?: ""
    private val fbKeyValidation: String get() = AppConfigManager.getString("firebase_FIELD_VALIDATION_KEY") ?: ""
    private val googleWebClientIds: String get() = BuildConfig.GOOGLE_WEB_CLIENT_IDS
    private val youtubeUploadScope: String get() = AppConfigManager.getString("youtube_UPLOAD_SCOPE") ?: ""

    // --- State Flows ---
    private val _currentUser = MutableStateFlow<FirebaseUser?>(null)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()
    private val _userProfile = MutableStateFlow<User?>(null)
    val userProfile: StateFlow<User?> = _userProfile.asStateFlow()
    var saldoDescriptografadoString: String = "0"
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    private val _youtubeAccessToken = MutableStateFlow<String?>(null)
    val youtubeAccessToken: StateFlow<String?> = _youtubeAccessToken.asStateFlow()

    companion object {
        private const val TAG = "AuthViewModel"
        private const val MAX_TRANSACTION_RETRIES = 3
        private const val RETRY_DELAY_MS = 200L
    }

    init {
        viewModelScope.launch {
            _error.value = "Aguardando configuração do servidor..."
            AppConfigManager.isLoaded.collect { isLoaded ->
                if(isLoaded) {
                    try {
                        _error.value = null
                        Log.d(TAG, "Configuração carregada. Inicializando AuthViewModel...")
                        initializeGsoAndListener()
                    } catch(e: MissingConfigurationException) {
                        Log.e(TAG, "ERRO CRÍTICO DE CONFIGURAÇÃO: ${e.message}")
                        _error.value = "Erro crítico de configuração: ${e.message}"
                    }
                }
            }
        }
    }

    private fun initializeGsoAndListener() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(googleWebClientIds)
            .requestEmail()
            .requestScopes(Scope(youtubeUploadScope))
            .build()
        googleSignInClient = GoogleSignIn.getClient(getApplication(), gso)

        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            _currentUser.value = user
            if (user != null) {
                firestore.collection(fbCollectionUsers).document(user.uid)
                    .addSnapshotListener { snapshot, e ->
                        if (e != null) {
                            Log.w(TAG, "Falha no listener do perfil do usuário.", e)
                            _userProfile.value = null
                            return@addSnapshotListener
                        }
                        if (snapshot != null && snapshot.exists()) {
                            _userProfile.value = snapshot.toObject(User::class.java)
                        } else {
                            _userProfile.value = null
                        }
                    }
            } else {
                _userProfile.value = null
                _youtubeAccessToken.value = null
            }
        }
    }

    private fun generateXORKey(validationKey: String): ByteArray {
        return try {
            MessageDigest.getInstance("SHA-256").digest(validationKey.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            ("FallbackKeyForXOR" + validationKey).toByteArray(Charsets.UTF_8).take(32).toByteArray()
        }
    }
    private fun xorEncrypt(data: String, key: String): String? {
        if (data.isBlank() || key.isBlank()) return null
        val dataBytes = data.toByteArray(Charsets.UTF_8)
        val xorKeyBytes = generateXORKey(key)
        val resultBytes = ByteArray(dataBytes.size)
        for (i in dataBytes.indices) {
            resultBytes[i] = (dataBytes[i].toInt() xor xorKeyBytes[i % xorKeyBytes.size].toInt()).toByte()
        }
        return Base64.encodeToString(resultBytes, Base64.NO_WRAP)
    }
     fun xorDecrypt(encryptedBase64: String, key: String): String? {
        if (encryptedBase64.isBlank() || key.isBlank()) return null
        return try {
            val encryptedBytes = Base64.decode(encryptedBase64, Base64.DEFAULT)
            val xorKeyBytes = generateXORKey(key)
            val resultBytes = ByteArray(encryptedBytes.size)
            for (i in encryptedBytes.indices) {
                resultBytes[i] = (encryptedBytes[i].toInt() xor xorKeyBytes[i % xorKeyBytes.size].toInt()).toByte()
            }
            String(resultBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao descriptografar XOR: ${e.message}", e)
            null
        }
    }

    fun generateLocalValidationKey(): String {
        val userIdValue = auth.currentUser?.uid!! ?: "xxx"
        val cryptoValue = cryptoMasterKey!! ?: "xxx"
        val key = xorEncrypt("${userIdValue}-${cryptoValue}", cryptoMasterKey!! ?: "xxx")
        return key ?: "xxx"
    }

    fun getGoogleSignInIntent(): Intent {
        if (!::googleSignInClient.isInitialized) {
            _error.value = "Serviço de login não inicializado devido a erro de configuração."
            return Intent()
        }
        return googleSignInClient.signInIntent
    }

    fun handleGoogleSignInResult(data: Intent?) {
        if (!::googleSignInClient.isInitialized) {
             _error.value = "Serviço de login não inicializado. Verifique a configuração."
            return
        }
        _isLoading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val account = withContext(Dispatchers.IO) {
                    task.getResult(ApiException::class.java)
                }
                val idToken = account.idToken
                if (idToken != null) {
                    val accessToken = getGoogleAccessToken(account, getApplication())
                    val credential = GoogleAuthProvider.getCredential(idToken, null)
                    signInWithFirebaseAndGoogle(credential, accessToken)
                } else {
                    throw ApiException(com.google.android.gms.common.api.Status(com.google.android.gms.common.api.CommonStatusCodes.SIGN_IN_REQUIRED))
                }
            } catch (e: ApiException) {
                val errorMsg = when (e.statusCode) {
                    com.google.android.gms.common.api.CommonStatusCodes.SIGN_IN_REQUIRED -> getApplication<Application>().getString(R.string.google_sign_in_required)
                    com.google.android.gms.common.api.CommonStatusCodes.CANCELED -> getApplication<Application>().getString(R.string.google_sign_in_cancelled)
                    else -> getApplication<Application>().getString(R.string.google_sign_in_api_exception, e.statusCode)
                }
                _error.value = errorMsg
            } catch (e: Exception) {
                _error.value = getApplication<Application>().getString(R.string.google_sign_in_process_error, e.localizedMessage ?: "xxx")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun signInWithFirebaseAndGoogle(credential: AuthCredential, googleAccessToken: String?) {
        viewModelScope.launch {
            try {
                val authResult = auth.signInWithCredential(credential).await()
                val firebaseUser = authResult.user

                if (firebaseUser != null) {
                    val userDocRef = firestore.collection(fbCollectionUsers).document(firebaseUser.uid)
                    val userSnapshot = userDocRef.get().await()

                    if (!userSnapshot.exists()) {
                        Log.d(TAG, "Novo usuário Google. Criando perfil inicial com créditos criptografados.")
                        val calendar = Calendar.getInstance()
                        calendar.add(Calendar.HOUR, 24)
                        val initialExpiration = Timestamp(calendar.time)

                        val chaveValidacaoFirebase = generateLocalValidationKey()
                        if (chaveValidacaoFirebase.isBlank()) throw java.lang.IllegalStateException(getApplication<Application>().getString(R.string.error_failed_generate_validation_key))

                        val initialCreditsAmount = TaskType.CREDIT_FREE.cost
                        val initialCreditsCriptografados = xorEncrypt(initialCreditsAmount.toString(), chaveValidacaoFirebase)

                        if (initialCreditsCriptografados.isNullOrBlank()) throw java.lang.IllegalStateException(getApplication<Application>().getString(R.string.error_failed_encrypt_initial_credits))

                        val newUser = User(
                            uid = firebaseUser.uid,
                            email = firebaseUser.email ?: "",
                            name = firebaseUser.displayName,
                            isPremium = false,
                            creditos = initialCreditsCriptografados,
                            dataExpiraCredito = initialExpiration,
                            chaveValidacaoFirebase = chaveValidacaoFirebase
                        )
                        userDocRef.set(newUser).await()

                        userInfoDataStoreManager.setUserNameCompany(firebaseUser.displayName ?: "")
                        userInfoDataStoreManager.setUserProfessionSegment("")
                        userInfoDataStoreManager.setUserAddress("")

                        Log.d(TAG, "Novo usuário Google registrado e perfil criado com créditos criptografados e verificação de validação criptografada.")
                    } else {
                        val userProfileData = userSnapshot.toObject(User::class.java)
                        if (userProfileData != null) {
                            if (!userProfileData.isPremium) {
                               val expirationDate = userProfileData.dataExpiraCredito?.toDate()
                               val currentDate = Date()
                               if (expirationDate == null || currentDate.after(expirationDate)) {
                                    Log.i(TAG, "Créditos 'free' expirados ou nulos para usuário existente. Renovando.")
                                    val calendar = Calendar.getInstance()
                                    calendar.time = currentDate
                                    calendar.add(Calendar.HOUR, 24)
                                    val newExpirationTimestamp = Timestamp(calendar.time)

                                    val chaveValidacaoFirebase = generateLocalValidationKey()
                                    if (chaveValidacaoFirebase.isBlank()) throw java.lang.IllegalStateException(getApplication<Application>().getString(R.string.error_failed_generate_validation_key_renewal))


                                    val initialCreditsAmount = TaskType.CREDIT_FREE.cost
                                    val initialCreditsCriptografados = xorEncrypt(initialCreditsAmount.toString(), chaveValidacaoFirebase)
                                    if (initialCreditsCriptografados.isNullOrBlank()) throw java.lang.IllegalStateException(getApplication<Application>().getString(R.string.error_failed_encrypt_credits_renewal))

                                    val creditUpdates = mapOf(
                                        fbKeyWallet to initialCreditsCriptografados,
                                        fbKeyCredExp to newExpirationTimestamp,
                                        fbKeyValidation to chaveValidacaoFirebase
                                    )
                                    userDocRef.update(creditUpdates).await()
                                    Log.i(TAG, "Créditos 'free' renovados para usuário existente. Nova expiração: $newExpirationTimestamp, Nova chave de validação salva.")
                               } else {
                                    Log.i(TAG, "Usuário existente, créditos 'free' ainda válidos.")
                               }
                               val chaveValidacaoFirebase = generateLocalValidationKey()

                               if (chaveValidacaoFirebase != userProfileData.chaveValidacaoFirebase) {
                                    Log.w(TAG, "Login Google: Discrepância na chave de validação para usuário existente. Atualizando Firebase.")
                                    userDocRef.update(fbKeyValidation, chaveValidacaoFirebase).await()
                                    Log.d(TAG, "Login Google: Chave de validação do Firebase atualizada.")
                               } else {
                                    Log.d(TAG, "Login Google: Chave de validação corresponde para usuário existente. OK.")
                               }
                            } else if (userProfileData.isPremium) {
                                 Log.i(TAG, "Login Google: Existing user is Premium. No changes to free credits/expiry.")
                                 val chaveValidacaoFirebase = generateLocalValidationKey()

                                 if (chaveValidacaoFirebase != userProfileData.chaveValidacaoFirebase) {
                                      Log.w(TAG, "Login Google: Discrepância na chave de validação para usuário Premium. Atualizando Firebase.")
                                      userDocRef.update(fbKeyValidation, chaveValidacaoFirebase).await()
                                      Log.d(TAG, "Login Google: Chave de validação do Firebase atualizada para usuário Premium.")
                                 } else {
                                      Log.d(TAG, "Login Google: Chave de validação corresponde para usuário Premium. OK.")
                                 }
                            }
                        } else {
                             Log.e(TAG, "Login Google: Usuário existente, mas dados do perfil nulos após snapshot.")
                        }
                    }
                    _youtubeAccessToken.value = googleAccessToken
                    _error.value = null
                    Log.d(TAG, "Login com Google para Firebase SUCESSO. UID: ${firebaseUser.uid.take(6)}.")
                }
            } catch (e: Exception) {
                _error.value = getApplication<Application>().getString(R.string.auth_failed_firebase_google, e.localizedMessage ?: "xxx")
                Log.e(TAG, "Erro ao autenticar com Google/Firebase", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun register(
        email: String,
        password: String,
        userNameCompany: String,
        userProfessionSegment: String,
        userAddress: String
    ) {
        _isLoading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                val result = auth.createUserWithEmailAndPassword(email, password).await()
                val firebaseUser = result.user
                if (firebaseUser != null) {
                    val calendar = Calendar.getInstance().apply { add(Calendar.HOUR, 24) }
                    val chaveValidacaoFirebase = generateLocalValidationKey()
                    if (chaveValidacaoFirebase.isBlank()) throw java.lang.IllegalStateException(getApplication<Application>().getString(R.string.error_failed_generate_validation_key_register))
                    val initialCreditsCriptografados = xorEncrypt(TaskType.CREDIT_FREE.cost.toString(), chaveValidacaoFirebase)
                    if (initialCreditsCriptografados.isNullOrBlank()) throw java.lang.IllegalStateException(getApplication<Application>().getString(R.string.error_failed_encrypt_credits_register))

                    val newUser = User(
                        uid = firebaseUser.uid, email = firebaseUser.email ?: "", isPremium = false,
                        creditos = initialCreditsCriptografados, dataExpiraCredito = Timestamp(calendar.time),
                        chaveValidacaoFirebase = chaveValidacaoFirebase
                    )
                    firestore.collection(fbCollectionUsers).document(firebaseUser.uid).set(newUser).await()

                    userInfoDataStoreManager.setUserNameCompany(userNameCompany)
                    userInfoDataStoreManager.setUserProfessionSegment(userProfessionSegment)
                    userInfoDataStoreManager.setUserAddress(userAddress)

                    _error.value = null
                }
            } catch (e: MissingConfigurationException) {
                _error.value = "Erro de Configuração: ${e.message}"
                Log.e(TAG, "Registro falhou por falta de configuração.", e)
            } catch (e: com.google.firebase.auth.FirebaseAuthUserCollisionException) {
                 _error.value = getApplication<Application>().getString(R.string.auth_error_email_already_in_use)
            } catch (e: com.google.firebase.auth.FirebaseAuthWeakPasswordException) {
                 _error.value = getApplication<Application>().getString(R.string.auth_error_weak_password)
            } catch (e: com.google.firebase.auth.FirebaseAuthInvalidCredentialsException) {
                 _error.value = getApplication<Application>().getString(R.string.auth_error_invalid_email)
            } catch (e: Exception) {
                _error.value = getApplication<Application>().getString(R.string.auth_failed_register, e.localizedMessage ?: "xxx")
                Log.e(TAG, "Erro no registro (geral)", e)
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
                val firebaseUser = authResult.user
                if (firebaseUser != null) {
                    val userDocRef = firestore.collection(fbCollectionUsers).document(firebaseUser.uid)
                    val userSnapshot = userDocRef.get().await()
                    val userProfileData = userSnapshot.toObject(User::class.java)

                    if (userProfileData != null && !userProfileData.isPremium) {
                        val expirationDate = userProfileData.dataExpiraCredito?.toDate()
                        if (expirationDate == null || Date().after(expirationDate)) {
                            val calendar = Calendar.getInstance().apply { add(Calendar.HOUR, 24) }
                            val chaveValidacaoFirebase = generateLocalValidationKey()
                            if (chaveValidacaoFirebase.isBlank()) throw java.lang.IllegalStateException(getApplication<Application>().getString(R.string.error_failed_generate_validation_key_renewal_login))
                            val initialCreditsCriptografados = xorEncrypt(TaskType.CREDIT_FREE.cost.toString(), chaveValidacaoFirebase)
                            if (initialCreditsCriptografados.isNullOrBlank()) throw java.lang.IllegalStateException(getApplication<Application>().getString(R.string.error_failed_encrypt_credits_renewal_login))
                            val creditUpdates = mapOf(fbKeyWallet to initialCreditsCriptografados, fbKeyCredExp to Timestamp(calendar.time), fbKeyValidation to chaveValidacaoFirebase)
                            userDocRef.update(creditUpdates).await()
                        }
                    }
                }
                 _error.value = null
            } catch (e: MissingConfigurationException) {
                _error.value = "Erro de Configuração: ${e.message}"
                Log.e(TAG, "Login falhou por falta de configuração.", e)
            } catch (e: com.google.firebase.auth.FirebaseAuthInvalidUserException) {
                 _error.value = getApplication<Application>().getString(R.string.auth_error_email_not_found)
            } catch (e: com.google.firebase.auth.FirebaseAuthInvalidCredentialsException) {
                 _error.value = getApplication<Application>().getString(R.string.auth_error_wrong_password)
            } catch (e: Exception) {
                _error.value = getApplication<Application>().getString(R.string.auth_failed_login, e.localizedMessage ?: "xxx")
            } finally {
                _isLoading.value = false
            }
        }
    }

    suspend fun checkAndDeductCredits(taskType: TaskType): Result<Unit> {
        val userId = auth.currentUser?.uid ?: return Result.failure(Exception(getApplication<Application>().getString(R.string.error_user_not_authenticated)))
        val amountToDeduct = taskType.cost

        var attempts = 0
        while (attempts < MAX_TRANSACTION_RETRIES) {
            try {
                return withContext(Dispatchers.IO) {
                    firestore.runTransaction { transaction ->
                        val userDocRef = firestore.collection(fbCollectionUsers).document(userId)
                        val user = transaction.get(userDocRef).toObject(User::class.java)
                            ?: throw Exception(getApplication<Application>().getString(R.string.error_user_profile_not_found))
                        
                        if (user.isPremium) return@runTransaction

                        val chaveValidacaoFirebase = generateLocalValidationKey()
                        if (chaveValidacaoFirebase.isBlank() || user.chaveValidacaoFirebase != chaveValidacaoFirebase) {
                            throw Exception(getApplication<Application>().getString(R.string.error_validation_key_mismatch))
                        }

                        val currentCredits = user.creditos?.let { xorDecrypt(it, chaveValidacaoFirebase)?.toIntOrNull() } ?: 0
                        if (currentCredits < amountToDeduct) {
                            throw Exception(getApplication<Application>().getString(R.string.error_insufficient_credits_simple))
                        }

                        val newCredits = currentCredits - amountToDeduct
                        val newEncryptedCredits = xorEncrypt(newCredits.toString(), chaveValidacaoFirebase)
                            ?: throw Exception(getApplication<Application>().getString(R.string.error_failed_encrypt_new_balance))
                        
                        transaction.update(userDocRef, fbKeyWallet, newEncryptedCredits)
                    }.await()
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                if (e.message?.contains("FAILED_PRECONDITION") == true) {
                    attempts++
                    Log.w(TAG, "Falha na precondição da transação (tentativa $attempts/$MAX_TRANSACTION_RETRIES). Tentando novamente em $RETRY_DELAY_MS ms.")
                    delay(RETRY_DELAY_MS)
                } else {
                    val finalError = e.message ?: "Erro desconhecido na transação"
                    _error.value = finalError
                    return Result.failure(Exception(finalError))
                }
            }
        }
        return Result.failure(Exception("Falha ao debitar créditos após $MAX_TRANSACTION_RETRIES tentativas devido a conflitos."))
    }

    fun refundCredits(taskType: TaskType) {
        val userId = auth.currentUser?.uid ?: return
        val amountToRefund = taskType.cost
        if (amountToRefund <= 0) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                firestore.runTransaction { transaction ->
                    val userDocRef = firestore.collection(fbCollectionUsers).document(userId)
                    val user = transaction.get(userDocRef).toObject(User::class.java) ?: return@runTransaction
                    val chaveValidacaoFirebase = generateLocalValidationKey()
                    if (chaveValidacaoFirebase.isBlank() || user.chaveValidacaoFirebase != chaveValidacaoFirebase) return@runTransaction
                    val currentCredits = user.creditos?.let { xorDecrypt(it, chaveValidacaoFirebase)?.toIntOrNull() } ?: 0
                    val newCredits = currentCredits + amountToRefund
                    val newEncryptedCredits = xorEncrypt(newCredits.toString(), chaveValidacaoFirebase) ?: return@runTransaction
                    transaction.update(userDocRef, fbKeyWallet, newEncryptedCredits)
                }.await()
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao reembolsar créditos.", e)
            }
        }
    }

    fun logout() {
        if (!::googleSignInClient.isInitialized) {
            auth.signOut()
            return
        }
        _isLoading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                if (GoogleSignIn.getLastSignedInAccount(getApplication()) != null) {
                   googleSignInClient.signOut().await()
                }
            } finally {
                auth.signOut()
                _youtubeAccessToken.value = null
                _isLoading.value = false
            }
        }
    }

    fun resetPassword(email: String) {
        _isLoading.value = true
        _error.value = null
        viewModelScope.launch {
            try {
                auth.sendPasswordResetEmail(email).await()
                _error.value = null
            } catch (e: com.google.firebase.auth.FirebaseAuthInvalidUserException) {
                 _error.value = getApplication<Application>().getString(R.string.auth_error_email_not_found)
            } catch (e: Exception) {
                _error.value = getApplication<Application>().getString(R.string.auth_failed_password_reset, e.localizedMessage ?: "xxx")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updatePremiumStatus(uid: String, isPremium: Boolean, subscriptionEndDate: Long? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val userDocRef = firestore.collection(fbCollectionUsers).document(uid)
                val updates = mutableMapOf<String, Any>(fbKeyIsPremium to isPremium)
                if (subscriptionEndDate != null) updates["subscriptionEndDate"] = subscriptionEndDate
                else updates["subscriptionEndDate"] = FieldValue.delete()
                userDocRef.update(updates).await()
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao atualizar status premium para UID: ${uid.take(6)}", e)
            }
        }
    }

    fun getDecryptedCredits(): Int {
        val currentUserProfile = userProfile.value
        val userId = currentUserProfile?.uid
        if (currentUserProfile == null || userId.isNullOrBlank()) {
            saldoDescriptografadoString = "0"
            return 0
        }
        if (currentUserProfile.isPremium) {
             saldoDescriptografadoString = "Premium"
            return 0
        }
        val chaveValidacaoFirebase = generateLocalValidationKey()
        if (chaveValidacaoFirebase.isBlank()) {
            saldoDescriptografadoString = "Erro"
            return 0
        }

        val saldoCriptografado = currentUserProfile.creditos
        if (saldoCriptografado.isNullOrBlank()) {
            saldoDescriptografadoString = "0"
            return 0
        }
        val saldoDescriptografado = xorDecrypt(saldoCriptografado, chaveValidacaoFirebase)
        saldoDescriptografadoString = saldoDescriptografado ?: "Erro"
        val saldoInt = saldoDescriptografado?.toIntOrNull()
        if (saldoInt == null) {
            saldoDescriptografadoString = "Erro"
            return 0
        }
        return saldoInt.coerceAtLeast(0)
    }

    private suspend fun getGoogleAccessToken(account: GoogleSignInAccount, context: Context): String? = withContext(Dispatchers.IO) {
        val email = account.email
        if (email.isNullOrBlank()) return@withContext null
        return@withContext try {
            GoogleAuthUtil.getToken(context, email, "oauth2:$youtubeUploadScope")
        } catch (e: Exception) {
            null
        }
    }
}