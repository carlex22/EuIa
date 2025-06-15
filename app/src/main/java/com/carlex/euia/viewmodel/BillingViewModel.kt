package com.carlex.euia.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.*
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow // Import para MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow // Import para asSharedFlow
import kotlinx.coroutines.launch
import com.carlex.euia.data.User
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await // Importação crucial para Tasks do Firebase
import kotlinx.coroutines.Dispatchers // Importação para Dispatchers.IO

class BillingViewModel(application: Application) : AndroidViewModel(application), PurchasesUpdatedListener {

    val billingClient: BillingClient // Tornada pública para acesso pelo Composable
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()

    private val _premiumProductDetails = MutableStateFlow<ProductDetails?>(null)
    val premiumProductDetails: StateFlow<ProductDetails?> = _premiumProductDetails.asStateFlow()

    private val _isPremiumUser = MutableStateFlow(false)
    val isPremiumUser: StateFlow<Boolean> = _isPremiumUser.asStateFlow()

    private val _billingMessage = MutableStateFlow<String?>(null)
    val billingMessage: StateFlow<String?> = _billingMessage.asStateFlow()

    // Evento para sinalizar à UI para iniciar o fluxo de faturamento
    private val _launchBillingFlowEvent = MutableSharedFlow<BillingFlowParams>()
    val launchBillingFlowEvent: SharedFlow<BillingFlowParams> = _launchBillingFlowEvent.asSharedFlow() // Usando asSharedFlow

    init {
        billingClient = BillingClient.newBuilder(application)
            .setListener(this)
            .enablePendingPurchases()
            .build()

        connectToGooglePlayBilling()

        // Observar o perfil do usuário para atualizar o status premium
        auth.addAuthStateListener { firebaseAuth ->
            firebaseAuth.currentUser?.uid?.let { uid ->
                firestore.collection("users").document(uid)
                    .addSnapshotListener { snapshot, e ->
                        if (e != null) {
                            Log.w("BillingViewModel", "Listen failed.", e)
                            return@addSnapshotListener
                        }
                        if (snapshot != null && snapshot.exists()) {
                            val user = snapshot.toObject(User::class.java)
                            _isPremiumUser.value = user?.isPremium ?: false
                            Log.d("BillingViewModel", "User premium status updated: ${_isPremiumUser.value}")
                        }
                    }
            } ?: run {
                _isPremiumUser.value = false // No user logged in
            }
        }
    }

    private fun connectToGooglePlayBilling() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d("BillingViewModel", "Billing client setup OK")
                    queryProductDetails()
                    queryPurchases()
                } else {
                    Log.e("BillingViewModel", "Billing client setup failed: ${billingResult.debugMessage}")
                    _billingMessage.value = "Erro de conexão com a Play Store: ${billingResult.debugMessage}"
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w("BillingViewModel", "Billing service disconnected. Retrying...")
                // Tente reconectar
                connectToGooglePlayBilling()
            }
        })
    }

    private fun queryProductDetails() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId("premium_subscription_monthly") // Seu ID de produto do Play Console
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
            // Adicione outros produtos/assinaturas aqui
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        // FIX: Chamar a função suspendida dentro de uma coroutine e tratar o resultado
        viewModelScope.launch(Dispatchers.IO) { // Usar Dispatchers.IO para operações de rede
            val productDetailsResult = billingClient.queryProductDetails(params)
            val billingResult = productDetailsResult.billingResult
            val productDetailsList = productDetailsResult.productDetailsList

            // FIX: Usar isNullOrEmpty() para verificar null e se a lista está vazia de forma segura
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && !productDetailsList.isNullOrEmpty()) {
                _premiumProductDetails.value = productDetailsList.first()
                // FIX: Usar safe call para acessar propriedades de _premiumProductDetails.value, pois ele é nullable
                Log.d("BillingViewModel", "Product details queried: ${_premiumProductDetails.value?.name}")
            } else {
                Log.e("BillingViewModel", "Failed to query product details: ${billingResult.debugMessage}")
                _billingMessage.value = "Não foi possível carregar detalhes do produto: ${billingResult.debugMessage}"
            }
        }
    }

    private fun queryPurchases() {
        if (!billingClient.isReady) {
            Log.w("BillingViewModel", "BillingClient not ready to query purchases.")
            return
        }
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { billingResult, purchasesList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                for (purchase in purchasesList) {
                    handlePurchase(purchase)
                }
            } else {
                Log.e("BillingViewModel", "Failed to query purchases: ${billingResult.debugMessage}")
            }
        }
    }

    // Função que prepara os parâmetros de compra e emite um evento para a UI
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

        viewModelScope.launch {
            _launchBillingFlowEvent.emit(billingFlowParams)
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                handlePurchase(purchase)
            }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            _billingMessage.value = "Compra cancelada pelo usuário."
        } else {
            _billingMessage.value = "Erro na compra: ${billingResult.debugMessage}"
            Log.e("BillingViewModel", "Purchase error: ${billingResult.debugMessage}")
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                billingClient.acknowledgePurchase(acknowledgePurchaseParams) { billingResult ->
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        Log.d("BillingViewModel", "Purchase acknowledged: ${purchase.orderId}")
                        verifyPurchaseOnBackend(purchase)
                    } else {
                        Log.e("BillingViewModel", "Failed to acknowledge purchase: ${billingResult.debugMessage}")
                    }
                }
            } else {
                // Compra já reconhecida, apenas verifique no backend
                verifyPurchaseOnBackend(purchase)
            }
        } else if (purchase.purchaseState == Purchase.PurchaseState.PENDING) {
            _billingMessage.value = "Compra pendente. Aguardando confirmação."
        } else if (purchase.purchaseState == Purchase.PurchaseState.UNSPECIFIED_STATE) {
            _billingMessage.value = "Estado de compra não especificado."
        }
    }

    // MUITO IMPORTANTE: VERIFIQUE A COMPRA NO SEU BACKEND!
    // Isso evita fraudes e garante que o status premium seja confiável.
    private fun verifyPurchaseOnBackend(purchase: Purchase) {
        val currentUserUid = auth.currentUser?.uid
        if (currentUserUid == null) {
            Log.e("BillingViewModel", "No authenticated user to link purchase to.")
            _billingMessage.value = "Erro: Usuário não autenticado para vincular a compra."
            return
        }

        // Em um ambiente de produção, você enviaria purchase.purchaseToken e purchase.products
        // para um Cloud Function (Firebase Functions) ou seu próprio backend.
        // O backend então usaria a API do Google Play Developer para verificar a validade da compra.
        // Se a compra for válida, o backend atualizaria o status isPremium do usuário no Firestore.

        // Para fins de demonstração, vamos simular a atualização direta no Firestore.
        // NÃO FAÇA ISSO EM PRODUÇÃO SEM VERIFICAÇÃO DE BACKEND!
        viewModelScope.launch {
            try {
                val userRef = firestore.collection("users").document(currentUserUid)
                userRef.update(
                    "isPremium", true,
                    "subscriptionEndDate", purchase.purchaseTime + 30L * 24 * 60 * 60 * 1000 // Exemplo: 30 dias
                ).await() // Usando .await() para a Task do Firestore
                _isPremiumUser.value = true
                _billingMessage.value = "Assinatura ativada com sucesso!"
                Log.d("BillingViewModel", "Premium status updated directly in Firestore (for demo).")
            } catch (e: Exception) {
                Log.e("BillingViewModel", "Failed to update premium status in Firestore: ${e.message}", e)
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