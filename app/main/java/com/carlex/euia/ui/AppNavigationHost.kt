package com.carlex.euia.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast // Embora seja melhor usar Snackbar, o Toast foi mantido onde já estava.
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.carlex.euia.* // Importa AppDestinations, DrawerMenuItem, WorkflowStage, etc.
import com.carlex.euia.R
import com.carlex.euia.viewmodel.*
import com.carlex.euia.viewmodel.ProjectManagementViewModelFactory // Import da factory do ProjectManagementViewModel
import com.carlex.euia.utils.shareVideoFile // Import da função de compartilhamento de vídeo

import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigationHostComposable(
    navController: NavHostController,
    mainActivityContext: Context // Passado de MainActivity para acessar o context da Activity se necessário (ex: Toast, embora Snackbar seja melhor)
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val application = LocalContext.current.applicationContext as Application // Obtém a instância da Application

    // Instanciar ViewModels globais e de autenticação/billing
    val authViewModel: AuthViewModel = viewModel()
    val billingViewModel: BillingViewModel = viewModel()
    val currentUser by authViewModel.currentUser.collectAsState()

    // ViewModels do Workflow (podem ser instanciados aqui e passados para VideoCreationWorkflowScreen)
    // Instanciar aqui garante que eles sobrevivam à navegação entre as abas do workflow
    // e sejam resetados apenas na navegação para fora do workflow ou por ação de projeto
    val videoWorkflowViewModel: VideoWorkflowViewModel = viewModel()
    val audioViewModel: AudioViewModel = viewModel()
    val videoInfoViewModel: VideoViewModel = viewModel()
    val refImageInfoViewModel: RefImageViewModel = viewModel()
    val videoProjectViewModel: VideoProjectViewModel = viewModel()
    val videoGeneratorViewModel: VideoGeneratorViewModel = viewModel()

    val selectedWorkflowTabIndex by videoWorkflowViewModel.selectedWorkflowTabIndex.collectAsState()
    val workflowStages = videoWorkflowViewModel.workflowStages
    val isWorkflowScreen = currentRoute == AppDestinations.VIDEO_CREATION_WORKFLOW

    val isCurrentStageProcessing by videoWorkflowViewModel.isCurrentStageProcessing.collectAsState()
    val currentStageProgressText by videoWorkflowViewModel.currentStageProgressText.collectAsState()
    val currentStageNumericProgress by videoWorkflowViewModel.currentStageNumericProgress.collectAsState()

    // Ações para a aba de Contexto
    val actualSaveContextAction by videoWorkflowViewModel.actualSaveContextAction.collectAsState()
    val isSaveContextEnabled by videoWorkflowViewModel.isSaveContextEnabled.collectAsState()

    // Ações dos botões da BottomAppBar do Workflow
    val currentStageLaunchPickerAction by videoWorkflowViewModel.currentStageLaunchPickerAction.collectAsState()
    val currentStageAnalyzeAction by videoWorkflowViewModel.currentStageAnalyzeAction.collectAsState()
    val currentStageCreateNarrativeAction by videoWorkflowViewModel.currentStageCreateNarrativeAction.collectAsState()
    val currentStageGenerateAudioAction by videoWorkflowViewModel.currentStageGenerateAudioAction.collectAsState()
    val currentStageGenerateScenesAction by videoWorkflowViewModel.currentStageGenerateScenesAction.collectAsState()
    val currentStageRecordVideoAction by videoWorkflowViewModel.currentStageRecordVideoAction.collectAsState()
    val currentShareVideoActionFromVM by videoWorkflowViewModel.currentShareVideoAction.collectAsState()

    val globalSnackbarHostState = remember { SnackbarHostState() }

    val isContextScreenDirty by videoWorkflowViewModel.isContextScreenDirty.collectAsState()
    val pendingNavigationAction by videoWorkflowViewModel.showConfirmExitContextDialog.collectAsState()

    // ActivityResultLauncher para o seletor de imagens (usado na aba de Imagens)
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri>? ->
        if (uris.isNullOrEmpty()) {
            scope.launch {
                globalSnackbarHostState.showSnackbar(
                    message = mainActivityContext.getString(R.string.toast_no_images_selected),
                    duration = SnackbarDuration.Short
                )
            }
            return@rememberLauncherForActivityResult
        }
        videoInfoViewModel.processImages(uris)
    }

    // Correção: Garante que a ação de 'launchPicker' seja fornecida ao ViewModel do workflow.
    LaunchedEffect(imagePickerLauncher, videoWorkflowViewModel) {
        videoWorkflowViewModel.setLaunchPickerAction { imagePickerLauncher.launch("image/*") }
    }

    // Lógica de redirecionamento de autenticação
    LaunchedEffect(currentUser) {
        val isAuthScreen = currentRoute == AppDestinations.LOGIN_ROUTE || currentRoute == AppDestinations.REGISTER_ROUTE

        if (currentUser == null) {
            // Se não há usuário logado e não estamos em uma tela de autenticação, vá para o login
            if (!isAuthScreen) {
                navController.navigate(AppDestinations.LOGIN_ROUTE) {
                    popUpTo(navController.graph.id) { inclusive = true }
                }
            }
        } else {
            // Se o usuário logou e está nas telas de autenticação, navegue para a tela principal de gerenciamento de projetos
            if (isAuthScreen) {
                navController.navigate(AppDestinations.PROJECT_MANAGEMENT_ROUTE) {
                    popUpTo(navController.graph.id) { inclusive = true }
                }
            }
        }
    }

    // Manipulador do botão de voltar do sistema
    val backPressedDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val backCallback = remember {
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Se estiver nas telas de login/cadastro, o back padrão deve funcionar (sair do app ou voltar para splash/etc)
                if (currentRoute == AppDestinations.LOGIN_ROUTE || currentRoute == AppDestinations.REGISTER_ROUTE) {
                    isEnabled = false // Desabilita este callback específico
                    backPressedDispatcher?.onBackPressed() // Chama o manipulador de back padrão
                    return
                }

                // Se o drawer estiver aberto, feche-o
                if (drawerState.isOpen) {
                    scope.launch { drawerState.close() }
                    return
                }

                // Lógica de manipulação de volta para a tela de workflow
                val isCurrentlyOnContextTab = isWorkflowScreen &&
                        selectedWorkflowTabIndex == 0 &&
                        workflowStages.getOrNull(selectedWorkflowTabIndex)?.identifier == AppDestinations.WORKFLOW_STAGE_CONTEXT &&
                        !isCurrentStageProcessing

                if (isCurrentlyOnContextTab && isContextScreenDirty) {
                    // Se a aba de Contexto está suja e não está processando, mostra o diálogo de confirmação para sair
                    videoWorkflowViewModel.attemptToChangeTab(-1) // Usando -1 como um sinal para "back" ou sair do workflow
                } else if (isWorkflowScreen && selectedWorkflowTabIndex > 0 && !isCurrentStageProcessing) {
                    // Se estiver no workflow e não for a primeira aba, e não estiver processando, volte uma aba
                    videoWorkflowViewModel.updateSelectedTabIndex(selectedWorkflowTabIndex - 1)
                } else {
                    // Se estiver na primeira aba do workflow (e não estiver processando), salve o projeto e saia do workflow
                    // Ou se estiver em uma tela comum, simplesmente volte na pilha de navegação
                    if (isWorkflowScreen && selectedWorkflowTabIndex == 0 && !isCurrentStageProcessing) {
                        videoWorkflowViewModel.triggerProjectSave()
                    }
                    isEnabled = false // Desabilita este callback específico
                    backPressedDispatcher?.onBackPressed() // Chama o manipulador de back padrão
                }
            }
        }
    }

    // Efeito para adicionar/remover o callback do botão de voltar
    LaunchedEffect(
        isWorkflowScreen, selectedWorkflowTabIndex, isCurrentStageProcessing,
        drawerState.isOpen, navBackStackEntry, isContextScreenDirty, pendingNavigationAction,
        currentRoute // Adicionado para reagir a mudanças de rota
    ) {
        val canGoToPreviousTab = isWorkflowScreen && selectedWorkflowTabIndex > 0 && !isCurrentStageProcessing
        val onFirstWorkflowTab = isWorkflowScreen && selectedWorkflowTabIndex == 0 && !isCurrentStageProcessing
        val canPopRegularBackStack = navController.previousBackStackEntry != null && !isWorkflowScreen

        // Habilita o callback personalizado se alguma condição de manipulação de volta se aplicar
        // e não estiver em uma tela de autenticação (onde o back padrão deve funcionar)
        val isAuthScreen = currentRoute == AppDestinations.LOGIN_ROUTE || currentRoute == AppDestinations.REGISTER_ROUTE
        backCallback.isEnabled = !isAuthScreen && (
                pendingNavigationAction != null || // Diálogo de confirmação de saída de contexto está visível
                drawerState.isOpen ||               // Drawer está aberto
                canGoToPreviousTab ||               // Pode voltar uma aba no workflow
                onFirstWorkflowTab ||               // Está na primeira aba do workflow (para salvar e sair)
                canPopRegularBackStack              // Pode voltar na pilha de navegação normal
            )
    }

    // Adiciona o callback ao dispatcher quando o Composable entra na árvore e o remove na saída
    DisposableEffect(backPressedDispatcher, backCallback) {
        backPressedDispatcher?.addCallback(backCallback)
        onDispose { backCallback.remove() }
    }

    // Diálogo de confirmação para sair da aba de Contexto com alterações não salvas
    if (pendingNavigationAction != null) {
        AlertDialog(
            onDismissRequest = { videoWorkflowViewModel.dismissExitContextDialog() },
            title = { Text(stringResource(R.string.dialog_title_unsaved_changes)) },
            text = { Text(stringResource(R.string.dialog_message_unsaved_changes_context)) },
            confirmButton = {
                Button(onClick = {
                    actualSaveContextAction?.invoke() // Salva as alterações
                    videoWorkflowViewModel.markContextAsSaved() // Marca como salvo
                    videoWorkflowViewModel.confirmExitContextDialogAction() // Executa a ação de navegação pendente
                }) { Text(stringResource(R.string.dialog_action_save_and_proceed)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    videoWorkflowViewModel.markContextAsSaved() // Descarta as alterações (apenas marca como salvo para não disparar novamente)
                    videoWorkflowViewModel.confirmExitContextDialogAction() // Executa a ação de navegação pendente
                }) { Text(stringResource(R.string.dialog_action_discard_and_proceed)) }
            }
        )
    }

    // Definição dos itens do menu lateral
    val drawerMenuItems = remember(mainActivityContext) {
        listOf(
            DrawerMenuItem(mainActivityContext.getString(R.string.drawer_menu_video_edit_start), iconImageVector = Icons.Filled.VideoCall, route = AppDestinations.VIDEO_CREATION_WORKFLOW),
            DrawerMenuItem(title = "", isDivider = true, route = ""),
            DrawerMenuItem(mainActivityContext.getString(R.string.drawer_menu_project_management), iconImageVector = Icons.Filled.FolderOpen, route = AppDestinations.PROJECT_MANAGEMENT_ROUTE),
            DrawerMenuItem(mainActivityContext.getString(R.string.drawer_menu_user_profile), iconResId = R.drawable.ic_perfil, route = AppDestinations.USERINFO_ROUTE),
            DrawerMenuItem(mainActivityContext.getString(R.string.drawer_menu_premium_features), iconImageVector = Icons.Filled.Stars, route = AppDestinations.PREMIUM_OFFER_ROUTE),
            DrawerMenuItem(title = "", isDivider = true, route = ""),
            DrawerMenuItem(mainActivityContext.getString(R.string.drawer_menu_settings), iconImageVector = Icons.Filled.Settings, route = AppDestinations.SETTINGS_ROUTE),
            DrawerMenuItem(title = "", isDivider = true, route = ""),
            DrawerMenuItem(mainActivityContext.getString(R.string.drawer_menu_about_app), iconResId = R.drawable.ic_info, route = AppDestinations.ABOUT_ROUTE),
            DrawerMenuItem(title = "", isDivider = true, route = ""),
            // Ação de logout no drawer
            DrawerMenuItem(mainActivityContext.getString(R.string.drawer_menu_logout), iconImageVector = Icons.Filled.ExitToApp, route = "logout_action")
        )
    }

    // Verifica se a TopAppBar e BottomBar devem ser exibidas
    val showAppBars = currentRoute != AppDestinations.LOGIN_ROUTE && currentRoute != AppDestinations.REGISTER_ROUTE

    // Estrutura principal de navegação com Drawer
    ModalNavigationDrawer(
        drawerState = drawerState,
        // Gestos do drawer habilitados apenas se o drawer estiver aberto para evitar conflitos de rolagem
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(16.dp))
                drawerMenuItems.forEach { item ->
                    if (item.isDivider) {
                        Divider(modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp))
                    } else {
                        NavigationDrawerItem(
                            label = { Text(item.title) },
                            selected = currentRoute == item.route,
                            onClick = {
                                // Lógica para a ação de logout
                                if (item.route == "logout_action") {
                                    scope.launch { drawerState.close() }
                                    authViewModel.logout()
                                    // A navegação para o LoginScreen será tratada pelo LaunchedEffect(currentUser)
                                    // quando currentUser se tornar null.
                                    return@NavigationDrawerItem
                                }

                                val performNavigationLambda = { // Envolve a navegação em uma lambda
                                    if (currentRoute != item.route) {
                                        // Se estiver saindo do workflow, acione o salvamento do projeto
                                        if (currentRoute == AppDestinations.VIDEO_CREATION_WORKFLOW && item.route != AppDestinations.VIDEO_CREATION_WORKFLOW) {
                                            videoWorkflowViewModel.triggerProjectSave()
                                        }
                                        navController.navigate(item.route) {
                                            // Pop up para a tela inicial do grafo para evitar múltiplas instâncias
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            // Garante que apenas uma instância da tela esteja na pilha
                                            launchSingleTop = true
                                            // Restaura o estado da tela se ela já existia
                                            restoreState = true
                                        }
                                    }
                                }

                                // Verifica se a aba de Contexto está suja e não está saindo para o próprio workflow
                                val isOnContextTabAndDirty = currentRoute == AppDestinations.VIDEO_CREATION_WORKFLOW &&
                                        selectedWorkflowTabIndex == 0 &&
                                        workflowStages.getOrNull(selectedWorkflowTabIndex)?.identifier == AppDestinations.WORKFLOW_STAGE_CONTEXT &&
                                        isContextScreenDirty &&
                                        item.route != AppDestinations.VIDEO_CREATION_WORKFLOW

                                scope.launch { drawerState.close() } // Fechar o drawer imediatamente

                                if (isOnContextTabAndDirty) {
                                    videoWorkflowViewModel.setPendingNavigationAction(performNavigationLambda)
                                } else {
                                    performNavigationLambda()
                                }
                            },
                            icon = {
                                item.iconResId?.let {
                                    Icon(painter = painterResource(id = it), contentDescription = item.title)
                                } ?: item.iconImageVector?.let {
                                    Icon(imageVector = it, contentDescription = item.title)
                                }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                if (showAppBars) { // Exibe TopAppBar apenas se não for tela de autenticação
                    Column {
                        TopAppBar(
                            title = {
                                Text(
                                    when {
                                        currentRoute == AppDestinations.PROJECT_MANAGEMENT_ROUTE -> stringResource(R.string.top_bar_title_project_management)
                                        isWorkflowScreen && selectedWorkflowTabIndex < workflowStages.size -> workflowStages[selectedWorkflowTabIndex].title
                                        currentRoute == AppDestinations.SETTINGS_ROUTE -> stringResource(R.string.top_bar_title_preferences)
                                        currentRoute == AppDestinations.USERINFO_ROUTE -> stringResource(R.string.top_bar_title_user_profile)
                                        currentRoute == AppDestinations.ABOUT_ROUTE -> stringResource(R.string.top_bar_title_about_euia)
                                        currentRoute == AppDestinations.PREMIUM_OFFER_ROUTE -> stringResource(R.string.top_bar_title_premium_offer)
                                        else -> stringResource(R.string.top_bar_title_default)
                                    }
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.content_desc_open_menu))
                                }
                            }
                        )
                        // Barra de abas para o workflow
                        if (isWorkflowScreen) {
                            ScrollableTabRow(
                                selectedTabIndex = selectedWorkflowTabIndex,
                                edgePadding = 0.dp
                            ) {
                                workflowStages.forEachIndexed { index, stage ->
                                    Tab(
                                        selected = selectedWorkflowTabIndex == index,
                                        onClick = { videoWorkflowViewModel.attemptToChangeTab(index) },
                                        text = { Text(stage.title) },
                                        enabled = !isCurrentStageProcessing // Desabilita abas se estiver processando
                                    )
                                }
                            }
                        }
                    }
                }
            },
            bottomBar = {
                if (isWorkflowScreen) { // Exibe BottomBar apenas se for tela de workflow
                    WorkflowBottomBar(
                        workflowStages = workflowStages,
                        selectedTabIndex = selectedWorkflowTabIndex,
                        isCurrentStageProcessing = isCurrentStageProcessing,
                        currentStageProgressText = currentStageProgressText,
                        currentStageNumericProgress = currentStageNumericProgress,
                        isSaveContextEnabled = isSaveContextEnabled,
                        onSaveContextClick = {
                            actualSaveContextAction?.invoke()
                            videoWorkflowViewModel.markContextAsSaved()
                        },
                        onLaunchPickerClick = { currentStageLaunchPickerAction?.invoke() },
                        onAnalyzeClick = { currentStageAnalyzeAction?.invoke() },
                        onCreateNarrativeClick = { currentStageCreateNarrativeAction?.invoke() },
                        onGenerateAudioClick = { currentStageGenerateAudioAction?.invoke() },
                        onGenerateScenesClick = { currentStageGenerateScenesAction?.invoke() },
                        onRecordVideoClick = { currentStageRecordVideoAction?.invoke() },
                        onShareVideoClick = currentShareVideoActionFromVM, // Passando a ação do ViewModel
                        onBackClick = {
                            if (selectedWorkflowTabIndex > 0 && !isCurrentStageProcessing) {
                                videoWorkflowViewModel.updateSelectedTabIndex(selectedWorkflowTabIndex - 1)
                            }
                        },
                        onNextClick = {
                            val nextIndex = selectedWorkflowTabIndex + 1
                            if (nextIndex < workflowStages.size && !isCurrentStageProcessing) {
                                videoWorkflowViewModel.attemptToChangeTab(nextIndex)
                            }
                        }
                    )
                }
            },
            snackbarHost = { SnackbarHost(globalSnackbarHostState) }
        ) { scaffoldInnerPadding ->
            // Host de navegação principal
            NavHost(
                navController = navController,
                // Define a rota inicial com base no estado de autenticação
                // As rotas LOGIN_ROUTE, REGISTER_ROUTE, PREMIUM_OFFER_ROUTE são do AppDestinations
                startDestination = if (currentUser != null) AppDestinations.PROJECT_MANAGEMENT_ROUTE else AppDestinations.LOGIN_ROUTE,
                modifier = Modifier.padding(scaffoldInnerPadding) // Aplica o padding do Scaffold
            ) {
                // Rotas de autenticação e monetização
                composable(AppDestinations.LOGIN_ROUTE) {
                    LoginScreen(navController = navController, authViewModel = authViewModel)
                }
                composable(AppDestinations.REGISTER_ROUTE) {
                    RegistrationScreen(navController = navController, authViewModel = authViewModel)
                }
                composable(AppDestinations.PREMIUM_OFFER_ROUTE) {
                    PremiumOfferScreen(billingViewModel = billingViewModel)
                }

                // Rotas de funcionalidade principal
                composable(AppDestinations.VIDEO_CREATION_WORKFLOW) {
                    // Todos os ViewModels do workflow são passados aqui
                    VideoCreationWorkflowScreen(
                        navController = navController,
                        snackbarHostState = globalSnackbarHostState,
                        innerPadding = PaddingValues(0.dp), // A tela de workflow gerencia seu próprio padding interno
                        videoWorkflowViewModel = videoWorkflowViewModel,
                        audioViewModel = audioViewModel,
                        videoInfoViewModel = videoInfoViewModel,
                        refImageInfoViewModel = refImageInfoViewModel,
                        videoProjectViewModel = videoProjectViewModel,
                        videoGeneratorViewModel = videoGeneratorViewModel,
                        generatedVideoPath = videoGeneratorViewModel.generatedVideoPath.collectAsState().value // Passa o path diretamente do VM
                    )
                }
                composable(AppDestinations.PROJECT_MANAGEMENT_ROUTE) {
                    val factory = ProjectManagementViewModelFactory(application)
                    val projectManagementViewModelInstance: ProjectManagementViewModel = viewModel(factory = factory)
                    ProjectManagementScreen(
                        navController = navController,
                        projectManagementViewModel = projectManagementViewModelInstance
                    )
                }
                composable(AppDestinations.USERINFO_ROUTE) { UserInfoScreen(navController = navController) }
                composable(AppDestinations.SETTINGS_ROUTE) { VideoPreferencesScreen(navController = navController) }
                composable(AppDestinations.ABOUT_ROUTE) { AboutScreen(onNavigateBack = { navController.popBackStack() }) }
                composable(AppDestinations.IMPORT_DATA_URL_ROUTE) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.placeholder_import_data_url))
                    }
                 }
                composable(AppDestinations.CHAT_ROUTE) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.placeholder_chat_screen))
                    }
                }
            }
        }
    }
}

// Composable da barra inferior do Workflow (permanece como estava)
@Composable
private fun WorkflowBottomBar(
    workflowStages: List<WorkflowStage>,
    selectedTabIndex: Int,
    isCurrentStageProcessing: Boolean,
    currentStageProgressText: String,
    currentStageNumericProgress: Float,
    isSaveContextEnabled: Boolean,
    onSaveContextClick: () -> Unit,
    onLaunchPickerClick: () -> Unit,
    onAnalyzeClick: () -> Unit,
    onCreateNarrativeClick: () -> Unit,
    onGenerateAudioClick: () -> Unit,
    onGenerateScenesClick: () -> Unit,
    onRecordVideoClick: () -> Unit,
    onShareVideoClick: (() -> Unit)?,
    onBackClick: () -> Unit,
    onNextClick: () -> Unit
) {
    Column {
        // Indicador de progresso (LinearProgressIndicator)
        if (isCurrentStageProcessing) {
           if (currentStageNumericProgress > 0f && workflowStages.getOrNull(selectedTabIndex)?.identifier == AppDestinations.WORKFLOW_STAGE_FINALIZE) {
                LinearProgressIndicator(progress = { currentStageNumericProgress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        } else {
            Divider() // Linha divisória quando não há progresso
        }
       /* // Texto de progresso
       if (currentStageProgressText.isNotBlank()) {
            Text(
                text = currentStageProgressText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 4.dp)
            )
        } else if (isCurrentStageProcessing) {
            Spacer(modifier = Modifier.height(4.dp)) // Espaço vazio para manter a altura consistente
        }*/

        // Linha principal de botões de navegação e ação
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Botão "Voltar"
            Button(
                onClick = onBackClick,
                enabled = selectedTabIndex > 0 && !isCurrentStageProcessing // Desabilita na primeira aba ou se estiver processando
            ) { Text(stringResource(R.string.bottom_bar_action_back)) }

            Spacer(Modifier.width(8.dp))

            // Box para o botão de ação central (ocupa mais espaço)
            Box(modifier = Modifier.weight(2f)) {
                val currentStageId = workflowStages.getOrNull(selectedTabIndex)?.identifier
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (currentStageId) {
                        AppDestinations.WORKFLOW_STAGE_CONTEXT -> {
                            Button(
                                onClick = onSaveContextClick,
                                enabled = isSaveContextEnabled && !isCurrentStageProcessing,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.bottom_bar_action_save_context))
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.action_save))
                            }
                        }
                        AppDestinations.WORKFLOW_STAGE_IMAGES -> {
                            Button(onClick = onLaunchPickerClick, modifier = Modifier.fillMaxWidth(), enabled = !isCurrentStageProcessing) {
                                Icon(Icons.Filled.AddPhotoAlternate, null); Spacer(Modifier.size(ButtonDefaults.IconSpacing)); Text(stringResource(R.string.bottom_bar_action_add_image))
                            }
                        }
                        AppDestinations.WORKFLOW_STAGE_INFORMATION -> {
                            Button(onClick = onAnalyzeClick, modifier = Modifier.fillMaxWidth(), enabled = !isCurrentStageProcessing) {
                                Icon(Icons.Filled.AutoAwesome, null); Spacer(Modifier.size(ButtonDefaults.IconSpacing)); Text(stringResource(R.string.bottom_bar_action_analyze))
                            }
                        }
                        AppDestinations.WORKFLOW_STAGE_NARRATIVE -> {
                            // Nesta aba, o botão principal é 'Narrar', que pode ser dividido ou ter ações adicionais.
                            // Se a lógica 'Gerar Audio' está separada da 'Criar Narrativa', ambos podem ser expostos.
                            // Se 'Criar Narrativa' também gera audio, o 'onGenerateAudioClick' pode ser nulo ou o mesmo.
                            // Por ora, um único botão principal para a aba.
                            Button(onClick = onCreateNarrativeClick, enabled = !isCurrentStageProcessing, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.AutoStories, null); Spacer(Modifier.size(ButtonDefaults.IconSpacing)); Text(stringResource(R.string.bottom_bar_action_narrate))
                            }
                            // Exemplo de como ter dois botões se ambos fossem úteis:
                            // Spacer(Modifier.width(4.dp))
                            // Button(onClick = onGenerateAudioClick, enabled = !isCurrentStageProcessing, modifier = Modifier.weight(1f)) {
                            //     Icon(Icons.Filled.Mic, null); Spacer(Modifier.size(ButtonDefaults.IconSpacing)); Text(stringResource(R.string.bottom_bar_action_generate_audio))
                            // }
                        }
                        AppDestinations.WORKFLOW_STAGE_SCENES -> {
                            Button(onClick = onGenerateScenesClick, modifier = Modifier.fillMaxWidth(), enabled = !isCurrentStageProcessing) {
                                Icon(Icons.Filled.MovieCreation, null); Spacer(Modifier.size(ButtonDefaults.IconSpacing)); Text(stringResource(R.string.bottom_bar_action_generate_scenes))
                            }
                        }
                        AppDestinations.WORKFLOW_STAGE_FINALIZE -> {
                            Button(onClick = onRecordVideoClick, modifier = Modifier.fillMaxWidth(), enabled = !isCurrentStageProcessing) {
                                Icon(Icons.Filled.Videocam, null); Spacer(Modifier.size(ButtonDefaults.IconSpacing)); Text(stringResource(R.string.bottom_bar_action_record_video))
                            }
                        }
                        else -> Spacer(Modifier.fillMaxWidth()) // Espaço vazio para outras abas sem ação central
                    }
                }
            }
            Spacer(Modifier.width(8.dp))

            // Botão "Próximo" ou "Compartilhar"
            if (selectedTabIndex == workflowStages.size - 1) { // Se estiver na última aba (Finalizar)
                IconButton(
                    onClick = { onShareVideoClick?.invoke() }, // Invoca a ação de compartilhamento se não for nula
                    enabled = !isCurrentStageProcessing && onShareVideoClick != null // Habilita se não estiver processando e a ação estiver disponível
                ) {
                    Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.bottom_bar_action_share_video))
                }
            } else {
                Button(
                    onClick = onNextClick,
                    modifier = Modifier.weight(1f),
                    enabled = !isCurrentStageProcessing && selectedTabIndex < workflowStages.size - 1, // Habilita se não for a última aba e não estiver processando
                ) { Text(stringResource(R.string.bottom_bar_action_next)) }
            }
        }
    }
}