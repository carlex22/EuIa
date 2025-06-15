// File: ui/AboutScreen.kt
package com.carlex.euia.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.carlex.euia.R
import java.util.Calendar

/**
 * Composable que exibe a tela "Sobre o App".
 *
 * Esta tela apresenta informações básicas sobre o aplicativo, como nome, versão,
 * uma breve descrição, desenvolvedor e informações de copyright.
 *
 * @param onNavigateBack Callback invocado quando o usuário solicita navegação para a tela anterior
 *                       (geralmente ao clicar no ícone de "voltar" na TopAppBar).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            // TODO: Adicionar um logo do aplicativo se disponível.
            // Image(
            //     painter = painterResource(id = R.drawable.app_logo),
            //     contentDescription = stringResource(R.string.content_desc_app_logo),
            //     modifier = Modifier.size(120.dp)
            // )
            // Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.app_name_full),
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            // TODO: Obter a versão dinamicamente do BuildConfig ou de um arquivo de constantes.
            Text(
                text = stringResource(R.string.app_version_placeholder, "beta 1.0.3"),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.app_short_description),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.app_developer_name),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            // <<< --- CORREÇÃO AQUI --- >>>
            Text(
                text = stringResource(R.string.app_copyright, currentYear.toString()), // A string já é formatada
                style = MaterialTheme.typography.labelSmall // O estilo é aplicado ao Text
            )
            // <<< --- FIM DA CORREÇÃO --- >>>
        }
    }
}