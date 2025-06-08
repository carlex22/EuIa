// File: euia/ui/PremiumOfferScreen.kt
package com.carlex.euia.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlex.euia.viewmodel.BillingViewModel
import androidx.compose.ui.text.style.TextAlign

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumOfferScreen(
    billingViewModel: BillingViewModel = viewModel()
) {
    val productDetails by billingViewModel.premiumProductDetails.collectAsState()
    val billingMessage by billingViewModel.billingMessage.collectAsState()

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("Desbloqueie o Premium!", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            Text("Acesso ilimitado a gerações de IA, sem marca d'água e muito mais!",
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(32.dp))

            productDetails?.let { details ->
                val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
                if (offerToken != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(details.name, style = MaterialTheme.typography.titleLarge)
                            Text(details.description)
                            Spacer(Modifier.height(8.dp))
                            details.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.let { pricing ->
                                Text("Preço: ${pricing.formattedPrice} / ${pricing.billingPeriod}", style = MaterialTheme.typography.bodyLarge)
                            }
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    // FIX: Change to call preparePurchaseFlow
                                    billingViewModel.preparePurchaseFlow(details, offerToken)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Assinar Agora")
                            }
                        }
                    }
                } else {
                    Text("Detalhes da assinatura não disponíveis. Verifique sua conexão ou tente mais tarde.")
                }
            } ?: run {
                CircularProgressIndicator()
                Text("Carregando ofertas...")
            }

            billingMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}