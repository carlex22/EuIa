// File: euia/ui/PremiumOfferScreen.kt
package com.carlex.euia.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer // <<<<< CORREÇÃO 1: Adicionar este import
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.billingclient.api.ProductDetails
import com.carlex.euia.R
import com.carlex.euia.data.User
import com.carlex.euia.viewmodel.BillingViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumOfferScreen(
    billingViewModel: BillingViewModel = viewModel()
) {
    val productDetails by billingViewModel.premiumProductDetails.collectAsState()
    val billingMessage by billingViewModel.billingMessage.collectAsState()
    val userProfile by billingViewModel.userProfile.collectAsState()

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.Top)
        ) {
            UserProfileStatusCard(userProfile)

            if (userProfile == null) {
                CircularProgressIndicator()
            } else if (!userProfile!!.isPremium) {
                PremiumOfferCard(
                    productDetails = productDetails,
                    onSubscribeClick = { details, offerToken ->
                        billingViewModel.preparePurchaseFlow(details, offerToken)
                    }
                )
            } else {
                 Text(
                     text = stringResource(R.string.premium_already_subscribed_message),
                     style = MaterialTheme.typography.titleLarge,
                     color = MaterialTheme.colorScheme.primary,
                     textAlign = TextAlign.Center
                 )
            }
            
            billingMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun UserProfileStatusCard(userProfile: User?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.premium_user_status_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Divider(modifier = Modifier.padding(vertical = 4.dp))

            if (userProfile != null) {
                val userType = if (userProfile.isPremium) stringResource(R.string.user_type_premium) else stringResource(R.string.user_type_free)
                val userTypeColor = if (userProfile.isPremium) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                InfoRow(label = stringResource(R.string.premium_label_user_type), value = userType, valueColor = userTypeColor)

                if (!userProfile.isPremium) {
                    InfoRow(label = stringResource(R.string.premium_label_credits), value = userProfile.creditos.toString())
                    InfoRow(label = stringResource(R.string.premium_label_credits_expire), value = formatTimestamp(userProfile.dataExpiraCredito?.toDate()?.time))
                }

                if (userProfile.isPremium) {
                    InfoRow(label = stringResource(R.string.premium_label_subscription_expires), value = formatTimestamp(userProfile.subscriptionEndDate))
                }
                
                InfoRow(label = stringResource(R.string.premium_label_user_id), value = userProfile.uid, isValueSelectable = true)

            } else {
                Text(stringResource(R.string.premium_loading_user_profile), modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun PremiumOfferCard(
    productDetails: ProductDetails?,
    onSubscribeClick: (ProductDetails, String) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.premium_unlock_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.premium_unlock_description),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(24.dp))

        if (productDetails != null) {
            val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
            if (offerToken != null) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(productDetails.name, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(4.dp))
                        Text(productDetails.description, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        productDetails.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.let { pricing ->
                            Text(
                                "${pricing.formattedPrice} / ${pricing.billingPeriod.toBillingPeriodString()}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = { onSubscribeClick(productDetails, offerToken) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.premium_action_subscribe_now))
                        }
                    }
                }
            } else {
                Text(stringResource(R.string.premium_offer_details_unavailable))
            }
        } else {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.premium_loading_offers))
        }
    }
}

// <<<<< CORREÇÃO 2: Reestruturação da função InfoRow >>>>>
@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = LocalContentColor.current, isValueSelectable: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
        // A lógica if/else agora envolve a chamada ao Composable, o que é o correto.
        if (isValueSelectable) {
            SelectionContainer {
                Text(text = value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
            }
        } else {
            Text(text = value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
        }
    }
}

private fun formatTimestamp(timestampMs: Long?): String {
    if (timestampMs == null || timestampMs <= 0) return "N/A"
    return try {
        val sdf = SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale.getDefault())
        sdf.format(Date(timestampMs))
    } catch (e: Exception) {
        "Data inválida"
    }
}

private fun String.toBillingPeriodString(): String {
    return when (this) {
        "P1M" -> "mês"
        "P1Y" -> "ano"
        "P6M" -> "6 meses"
        "P3M" -> "3 meses"
        else -> this
    }
}