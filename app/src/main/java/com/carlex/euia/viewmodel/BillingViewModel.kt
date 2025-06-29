// File: euia/viewmodel/BillingViewModel.kt
package com.carlex.euia.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.*
import com.carlex.euia.data.User
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class BillingViewModel(application: Application) : AndroidViewModel(application), PurchasesUpdatedListener {

    val billingClient: BillingClient
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    // Detalhes do produto de assinatura carregados da Play Store
    private val _premiumProductDetails = MutableStateFlow<ProductDetails?>(null)
    val premiumProductDetails: StateFlow<ProductDetails?> = _premiumProductDetails.asStateFlow()

    // Perfil completo do usuário, observado em tempo real do Firestore
    private val _userProfile = MutableStateFlow<User?>(null)
    val userProfile: StateFlow<User?> = _userProfile.asStateFlow()

    // Estado booleano derivado do perfil do usuário para fácil acesso
    val isPremiumUser: StateFlow<Boolean> = _userProfile.map { it?.isPremium ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Mensagens de status para a UI (ex: "Compra pendente", "Erro...")
    private val _billingMessage = MutableStateFlow<String?>(null)
    val billingMessage: StateFlow<String?> = _billingMessage.asStateFlow()

    // Evento para sinalizar à UI que o fluxo de compra deve ser iniciado
    private val _launchBillingFlowEvent = MutableSharedFlow<BillingFlowParams>()
    val launchBillingFlowEvent: SharedFlow<BillingFlowParams> = _launchBillingFlowEvent.asSharedFlow()

    init {
        billingClient = BillingClient.newBuilder(application)
            .setListener(this)
            .enablePendingPurchases()
            .build()

        connectToGooglePlayBilling()

        // Observa o estado de autenticação para buscar ou limpar o perfil do usuário
        auth.addAuthStateListener { firebaseAuth ->
            val currentUser = firebaseAuth.currentUser
            if (currentUser != null) {
                listenToUserProfile(currentUser.uid)
            } else {
                _userProfile.value = null
            }
        }
    }

    /**
     * Inicia a conexão com o serviço da Google Play Billing.
     */
    private fun connectToGooglePlayBilling() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d("BillingViewModel", "Configuração do Billing Client OK")
                    queryProductDetails()
                    queryPurchases()
                } else {
                    Log.e("BillingViewModel", "Falha na configuração do Billing Client: ${billingResult.debugMessage}")
                    _billingMessage.value = "Erro de conexão com a Play Store: ${billingResult.debugMessage}"
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w("BillingViewModel", "Serviço de faturamento desconectado. Tentando reconectar...")
                connectToGooglePlayBilling()
            }
        })
    }

    /**
     * Ouve em tempo real as atualizações do documento do usuário no Firestore.
     */
    private fun listenToUserProfile(uid: String) {
        firestore.collection("users").document(uid)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.w("BillingViewModel", "Falha ao ouvir o perfil do usuário.", e)
                    _userProfile.value = null
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    _userProfile.value = snapshot.toObject(User::class.java)
                } else {
                    Log.w("BillingViewModel", "Documento do usuário não encontrado para o UID: $uid")
                    _userProfile.value = null
                }
            }
    }

    /**
     * Busca os detalhes dos produtos de assinatura na Play Store.
     */
    private fun queryProductDetails() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId("premium_subscription_monthly") // SEU ID DE PRODUTO
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder().setProductList(productList).build()

        viewModelScope.launch(Dispatchers.IO) {
            val productDetailsResult = billingClient.queryProductDetails(params)
            val billingResult = productDetailsResult.billingResult
            val productDetailsList = productDetailsResult.productDetailsList

            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && !productDetailsList.isNullOrEmpty()) {
                _premiumProductDetails.value = productDetailsList.first()
            } else {
                Log.e("BillingViewModel", "Falha ao buscar detalhes do produto: ${billingResult.debugMessage}")
                _billingMessage.value = "Não foi possível carregar detalhes do produto."
            }
        }
    }

    /**
     * Busca por compras ativas ou pendentes que o usuário já possa ter.
     */
    private fun queryPurchases() {
        if (!billingClient.isReady) return
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                for (purchase in purchasesList) {
                    handlePurchase(purchase)
                }
            }
        }
    }

    /**
     * Prepara os parâmetros de compra e emite um evento para a UI iniciar o fluxo.
     */
    fun preparePurchaseFlow(productDetails: ProductDetails, offerToken: String) {
        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .setOfferToken(offerToken)
                .build()
        )
        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()
        viewModelScope.launch { _launchBillingFlowEvent.emit(billingFlowParams) }
    }

    /**
     * Callback para atualizações de compras.
     */
    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                handlePurchase(purchase)
            }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            _billingMessage.value = "Compra cancelada pelo usuário."
        } else {
            _billingMessage.value = "Erro na compra: ${billingResult.debugMessage}"
        }
    }

    /**
     * Lida com uma compra, reconhecendo-a e verificando-a.
     */
    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        Log.d("BillingViewModel", "Compra reconhecida: ${purchase.orderId}")
                        verifyPurchaseOnBackend(purchase)
                    }
                }
            } else {
                verifyPurchaseOnBackend(purchase)
            }
        } else if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            _billingMessage.value = "Compra pendente. Aguardando confirmação."
        }
    }

    /**
     * Simula a verificação da compra e atualiza o status do usuário no Firestore.
     * NÃO FAÇA ISSO EM PRODUÇÃO SEM VERIFICAÇÃO DE BACKEND!
     */
    private fun verifyPurchaseOnBackend(purchase: Purchase) {
        val currentUserUid = auth.currentUser?.uid ?: return
        viewModelScope.launch {
            try {
                val userRef = firestore.collection("users").document(currentUserUid)
                userRef.update(
                    "isPremium", true,
                    "subscriptionEndDate", purchase.purchaseTime + 30L * 24 * 60 * 60 *  100 // Exemplo: 30 dias
                ).await()
                _billingMessage.value = "Assinatura ativada com sucesso!"
            } catch (e: Exception) {
                Log.e("BillingViewModel", "Falha ao atualizar status premium no Firestore: ${e.message}", e)
                _billingMessage.value = "Erro ao ativar assinatura no perfil."
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (billingClient.isReady) {
            billingClient.endConnection()
        }
    }
}