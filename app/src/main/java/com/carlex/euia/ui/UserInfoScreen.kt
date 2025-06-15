// File: ui/UserInfoScreen.kt
package com.carlex.euia.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
// import androidx.compose.material.icons.Icons // Não usado diretamente aqui
// import androidx.compose.material.icons.filled.ArrowBack // Não usado diretamente aqui
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.carlex.euia.R
import com.carlex.euia.viewmodel.UserInfoViewModel
import kotlinx.coroutines.launch

/**
 * Tela para exibir e editar as informações de perfil/persona do usuário.
 * Os dados são persistidos através do [UserInfoViewModel] e [UserInfoDataStoreManager].
 *
 * Esta tela inclui:
 * - Campos para nome/empresa, profissão/segmento e endereço.
 * - Botões para Salvar ou Cancelar as alterações.
 * - Um diálogo de confirmação é exibido se o usuário tentar sair com alterações não salvas.
 * - Feedback ao salvar é fornecido via Snackbar.
 *
 * @param navController O [NavController] para gerenciar a navegação (principalmente para voltar).
 * @param userInfoViewModel O [UserInfoViewModel] que fornece e armazena os dados do perfil.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserInfoScreen(
    navController: NavController,
    userInfoViewModel: UserInfoViewModel = viewModel()
) {
    // Coleta os valores iniciais/persistidos do ViewModel
    val initialUserNameCompany by userInfoViewModel.userNameCompany.collectAsState()
    val initialUserProfessionSegment by userInfoViewModel.userProfessionSegment.collectAsState()
    val initialUserAddress by userInfoViewModel.userAddress.collectAsState()
    // As seguintes são coletadas, mas não editadas nesta tela (mantidas para consistência com o ViewModel)
    val initialUserLanguageTone by userInfoViewModel.userLanguageTone.collectAsState()
    val initialUserTargetAudience by userInfoViewModel.userTargetAudience.collectAsState()

    // Estados locais editáveis para os campos do formulário
    var currentUserNameCompany by remember { mutableStateOf("") }
    var currentUserProfessionSegment by remember { mutableStateOf("") }
    var currentUserAddress by remember { mutableStateOf("") }
    // Não são mais necessários estados locais para tom e público alvo nesta tela

    var hasChanges by remember { mutableStateOf(false) }
    var showExitConfirmationDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current // Para obter strings para o Snackbar

    // Sincroniza o estado local com os dados do ViewModel na inicialização ou quando os dados externos mudam.
    // Atualiza 'hasChanges' apenas se houver uma mudança real nos campos editáveis desta tela.
    LaunchedEffect(
        initialUserNameCompany, initialUserProfessionSegment, initialUserAddress
    ) {
        val nameChanged = currentUserNameCompany != initialUserNameCompany
        val segmentChanged = currentUserProfessionSegment != initialUserProfessionSegment
        val addressChanged = currentUserAddress != initialUserAddress

        currentUserNameCompany = initialUserNameCompany
        currentUserProfessionSegment = initialUserProfessionSegment
        currentUserAddress = initialUserAddress

        // Recalcula hasChanges com base nos campos desta tela
        hasChanges = nameChanged || segmentChanged || addressChanged
    }

    // Intercepta o botão "voltar" do sistema se houver alterações não salvas
    BackHandler(enabled = hasChanges) {
        showExitConfirmationDialog = true
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            // Uma simples linha divisória no topo, já que o título é gerenciado pelo AppNavigationHost
            Divider()
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding) // Aplica o padding do Scaffold (se houver, vindo do NavHost)
        ) {
            // Conteúdo principal rolável
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f) // Ocupa o espaço vertical disponível
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp) // Padding interno para o conteúdo
            ) {
                SettingsSectionTitle(stringResource(id = R.string.user_info_section_persona_profile))

                SettingTextFieldItem(
                    label = stringResource(id = R.string.user_info_label_name_company),
                    value = currentUserNameCompany,
                    onValueChange = {
                        currentUserNameCompany = it
                        hasChanges = it != initialUserNameCompany || currentUserProfessionSegment != initialUserProfessionSegment || currentUserAddress != initialUserAddress
                    },
                    singleLine = true,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                SettingTextFieldItem(
                    label = stringResource(id = R.string.user_info_label_profession_segment),
                    value = currentUserProfessionSegment,
                    onValueChange = {
                        currentUserProfessionSegment = it
                        hasChanges = currentUserNameCompany != initialUserNameCompany || it != initialUserProfessionSegment || currentUserAddress != initialUserAddress
                    },
                    singleLine = true,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                SettingTextFieldItem(
                    label = stringResource(id = R.string.user_info_label_address),
                    value = currentUserAddress,
                    onValueChange = {
                        currentUserAddress = it
                        hasChanges = currentUserNameCompany != initialUserNameCompany || currentUserProfessionSegment != initialUserProfessionSegment || it != initialUserAddress
                    },
                    singleLine = true, // Pode ser false se o endereço for longo
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } // Fim da Column rolável

            // Rodapé fixo com botões Salvar e Cancelar
            Column(
                modifier = Modifier
                    .height(60.dp) // Altura fixa para a barra de botões
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface) // Fundo para destacar
            ) {
                Divider(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), // Cor sutil para o divisor
                    thickness = 1.dp
                )
                Row(
                    modifier = Modifier
                        .fillMaxSize() // Preenche a altura da Column pai (60.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp), // Padding para os botões
                    horizontalArrangement = Arrangement.SpaceEvenly, // Espaça os botões uniformemente
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            userInfoViewModel.setUserNameCompany(currentUserNameCompany)
                            userInfoViewModel.setUserProfessionSegment(currentUserProfessionSegment)
                            userInfoViewModel.setUserAddress(currentUserAddress)
                            // Não salva tom/audiência aqui, pois são gerenciados em outro lugar

                            hasChanges = false // Reseta o estado "sujo"
                            val settingsSavedMessage = context.getString(R.string.user_info_toast_settings_saved)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = settingsSavedMessage,
                                    duration = SnackbarDuration.Short
                                )
                            }
                            navController.popBackStack() // Volta para a tela anterior
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 4.dp) // Pequeno espaço entre os botões
                    ) {
                        Text(stringResource(id = R.string.action_save))
                    }

                    Button(
                        onClick = {
                            if (hasChanges) {
                                showExitConfirmationDialog = true
                            } else {
                                navController.popBackStack()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp) // Pequeno espaço entre os botões
                    ) {
                        Text(stringResource(id = R.string.action_cancel))
                    }
                }
            } // Fim da Column do rodapé
        } // Fim da Column principal
    } // Fim do Scaffold

    // Diálogo de confirmação ao sair com alterações não salvas
    if (showExitConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { showExitConfirmationDialog = false },
            title = { Text(stringResource(id = R.string.exit_dialog_title)) },
            text = { Text(stringResource(id = R.string.exit_dialog_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        userInfoViewModel.setUserNameCompany(currentUserNameCompany)
                        userInfoViewModel.setUserProfessionSegment(currentUserProfessionSegment)
                        userInfoViewModel.setUserAddress(currentUserAddress)
                        hasChanges = false
                        showExitConfirmationDialog = false
                        navController.popBackStack()
                    }
                ) { Text(stringResource(id = R.string.exit_dialog_save)) }
            },
            dismissButton = { // Combina os botões de "Descartar" e "Cancelar" do diálogo original
                Row {
                    TextButton(
                        onClick = {
                            showExitConfirmationDialog = false
                            // Não salva, apenas volta
                            navController.popBackStack()
                        }
                    ) { Text(stringResource(id = R.string.exit_dialog_discard)) } // "Descartar e Sair"
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { showExitConfirmationDialog = false } // Apenas fecha o diálogo
                    ) { Text(stringResource(id = R.string.exit_dialog_cancel)) } // "Cancelar Saída"
                }
            }
        )
    }
}

/**
 * Composable auxiliar para exibir um título de seção formatado.
 * @param title O texto do título.
 */
@Composable
private fun SettingsSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp) // Aumentado padding vertical para mais respiro
    )
}

/**
 * Composable auxiliar para um item de campo de texto editável com label.
 *
 * @param label O texto do label para o campo.
 * @param value O valor atual do campo.
 * @param onValueChange Callback invocado quando o valor do campo é alterado.
 * @param modifier [Modifier] para aplicar ao [Column] que envolve o campo.
 * @param placeholder Texto de placeholder opcional para o campo.
 * @param singleLine True se o campo deve ter apenas uma linha, false caso contrário.
 * @param maxLines Número máximo de linhas se não for singleLine.
 */
@Composable
private fun SettingTextFieldItem(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = { if (placeholder != null) Text(placeholder) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            maxLines = maxLines
            // keyboardOptions e keyboardActions podem ser adicionados se necessário para comportamentos específicos
        )
    }
}