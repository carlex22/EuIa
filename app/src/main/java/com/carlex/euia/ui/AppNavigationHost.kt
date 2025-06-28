// File: euia/ui/AppNavigationHost.kt
package com.carlex.euia.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
// CORREÇÃO: Removido import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.carlex.euia.*
import com.carlex.euia.R
import com.carlex.euia.viewmodel.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigationHostComposable(
    navController: NavHostController,
    mainActivityContext: Context
) {
    // CORREÇÃO: Estado para controlar quando o NavController está pronto
    var isNavControllerReady by remember { mutableStateOf(false) }
    
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val application = LocalContext.current.applicationContext as Application

    // ViewModels (permanecem iguais)
    val authViewModel: AuthViewModel = viewModel()
    val billingViewModel: BillingViewModel = viewModel()
    val videoWorkflowViewModel: VideoWorkflowViewModel = viewModel()
    val audioViewModel: AudioViewModel = viewModel()
    val videoInfoViewModel: VideoViewModel = viewModel()
    val refImageInfoViewModel: RefImageViewModel = viewModel()
    val videoProjectViewModel: VideoProjectViewModel = viewModel()
    val videoGeneratorViewModel: VideoGeneratorViewModel = viewModel()

    // Estados coletados (permanecem iguais)
    val currentUser by authViewModel.currentUser.collectAsState()
    val userProfile by authViewModel.userProfile.collectAsState()
    val selectedWorkflowTabIndex by videoWorkflowViewModel.selectedWorkflowTabIndex.collectAsState()
    val workflowStages = videoWorkflowViewModel.workflowStages
    val isWorkflowScreen = currentRoute == AppDestinations.VIDEO_CREATION_WORKFLOW
    val isCurrentStageProcessing by videoWorkflowViewModel.isCurrentStageProcessing.collectAsState()
    val currentStageProgressText by videoWorkflowViewModel.currentStageProgressText.collectAsState()
    val currentStageNumericProgress by videoWorkflowViewModel.currentStageNumericProgress.collectAsState()
    val actualSaveContextAction by videoWorkflowViewModel.actualSaveContextAction.collectAsState()
    val isSaveContextEnabled by videoWorkflowViewModel.isSaveContextEnabled.collectAsState()
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

    LaunchedEffect(imagePickerLauncher, videoWorkflowViewModel) {
        videoWorkflowViewModel.setLaunchPickerAction { imagePickerLauncher.launch("image/*") }
    }

    // CORREÇÃO: Aguarda NavController estar pronto
    LaunchedEffect(navController) {
        kotlinx.coroutines.delay(200)
        isNavControllerReady = true
    }

    // CORREÇÃO: Função de navegação segura sem usar graph
    fun safeNavigate(route: String) {
        if (!isNavControllerReady) return
        
        try {
            navController.navigate(route) {
                launchSingleTop = true
                restoreState = true
            }
        } catch (e: Exception) {
            Log.e("Navigation", "Erro na navegação para $route: ${e.message}")
        }
    }

    // CORREÇÃO: Lógica de redirecionamento segura
    LaunchedEffect(currentUser, isNavControllerReady) {
        if (!isNavControllerReady) return@LaunchedEffect
        
        val isAuthScreen = currentRoute == AppDestinations.LOGIN_ROUTE || currentRoute == AppDestinations.REGISTER_ROUTE
        
        if (currentUser == null) {
            if (!isAuthScreen) {
                safeNavigate(AppDestinations.LOGIN_ROUTE)
            }
        } else {
            if (isAuthScreen) {
                safeNavigate(AppDestinations.PROJECT_MANAGEMENT_ROUTE)
            }
        }
    }

    // Lógica do botão Voltar (permanece igual)
    val backPressedDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val backCallback = remember {
        object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentRoute == AppDestinations.LOGIN_ROUTE || currentRoute == AppDestinations.REGISTER_ROUTE) {
                    isEnabled = false
                    backPressedDispatcher?.onBackPressed()
                    return
                }

                if (drawerState.isOpen) {
                    scope.launch { drawerState.close() }
                    return
                }

                val isCurrentlyOnContextTab = isWorkflowScreen && selectedWorkflowTabIndex == 0 && !isCurrentStageProcessing
                if (isCurrentlyOnContextTab && isContextScreenDirty) {
                    videoWorkflowViewModel.attemptToChangeTab(-1)
                } else if (isWorkflowScreen && selectedWorkflowTabIndex > 0 && !isCurrentStageProcessing) {
                    videoWorkflowViewModel.updateSelectedTabIndex(selectedWorkflowTabIndex - 1)
                } else {
                    if (isWorkflowScreen && selectedWorkflowTabIndex == 0 && !isCurrentStageProcessing) {
                        videoWorkflowViewModel.triggerProjectSave()
                    }
                    isEnabled = false
                    backPressedDispatcher?.onBackPressed()
                }
            }
        }
    }

    // LaunchedEffect para back button (permanece igual)
    LaunchedEffect(
        isWorkflowScreen, selectedWorkflowTabIndex, isCurrentStageProcessing,
        drawerState.isOpen, navBackStackEntry, isContextScreenDirty, pendingNavigationAction,
        currentRoute
    ) {
        val canGoToPreviousTab = isWorkflowScreen && selectedWorkflowTabIndex > 0 && !isCurrentStageProcessing
        val onFirstWorkflowTab = isWorkflowScreen && selectedWorkflowTabIndex == 0 && !isCurrentStageProcessing
        val canPopRegularBackStack = navController.previousBackStackEntry != null && !isWorkflowScreen
        val isAuthScreen = currentRoute == AppDestinations.LOGIN_ROUTE || currentRoute == AppDestinations.REGISTER_ROUTE

        backCallback.isEnabled = !isAuthScreen && (
            pendingNavigationAction != null || drawerState.isOpen || canGoToPreviousTab ||
            onFirstWorkflowTab || canPopRegularBackStack
        )
    }

    DisposableEffect(backPressedDispatcher, backCallback) {
        backPressedDispatcher?.addCallback(backCallback)
        onDispose { backCallback.remove() }
    }

    // Dialog permanece igual
    if (pendingNavigationAction != null) {
        AlertDialog(
            onDismissRequest = { videoWorkflowViewModel.dismissExitContextDialog() },
            title = { Text(stringResource(R.string.dialog_title_unsaved_changes)) },
            text = { Text(stringResource(R.string.dialog_message_unsaved_changes_context)) },
            confirmButton = {
                Button(onClick = {
                    actualSaveContextAction?.invoke()
                    videoWorkflowViewModel.markContextAsSaved()
                    videoWorkflowViewModel.confirmExitContextDialogAction()
                }) { Text(stringResource(R.string.dialog_action_save_and_proceed)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    videoWorkflowViewModel.markContextAsSaved()
                    videoWorkflowViewModel.confirmExitContextDialogAction()
                }) { Text(stringResource(R.string.dialog_action_discard_and_proceed)) }
            }
        )
    }

    val adminUid = "oKfJVSidGvgYgdQQZnTi3xKpYVk1"
    val drawerMenuItems = remember(currentUser, mainActivityContext) {
        val menuItems = mutableListOf(
            DrawerMenuItem(mainActivityContext.getString(R.string.drawer_menu_video_edit_start), iconImageVector = Icons.Filled.VideoCall, route = AppDestinations.VIDEO_CREATION_WORKFLOW),
            DrawerMenuItem(title = "", isDivider = true, route = ""),
            DrawerMenuItem(mainActivityContext.getString(R.string.drawer_menu_project_management), iconImageVector = Icons.Filled.FolderOpen, route = AppDestinations.PROJECT_MANAGEMENT_ROUTE),
            DrawerMenuItem(mainActivityContext.getString(R.string.drawer_menu_user_profile), iconResId = R.drawable.ic_perfil, route = AppDestinations.USERINFO_ROUTE),
            DrawerMenuItem(mainActivityContext.getString(R.string.drawer_menu_premium_features), iconImageVector = Icons.Filled.Stars, route = AppDestinations.PREMIUM_OFFER_ROUTE)
        )

        if (currentUser?.uid == adminUid) {
            menuItems.add(DrawerMenuItem(
                title = "Admin: Add API Key",
                iconImageVector = Icons.Filled.VpnKey,
                route = AppDestinations.ADD_GEMINI_API_KEY_ROUTE
            ))
        }

        menuItems.addAll(listOf(
            DrawerMenuItem(title = "", isDivider = true, route = ""),
            DrawerMenuItem(mainActivityContext.getString(R.string.drawer_menu_settings), iconImageVector = Icons.Filled.Settings, route = AppDestinations.SETTINGS_ROUTE),
            DrawerMenuItem(title = "", isDivider = true, route = ""),
            DrawerMenuItem(mainActivityContext.getString(R.string.drawer_menu_about_app), iconResId = R.drawable.ic_info, route = AppDestinations.ABOUT_ROUTE),
            DrawerMenuItem(title = "", isDivider = true, route = ""),
            DrawerMenuItem(mainActivityContext.getString(R.string.drawer_menu_logout), iconImageVector = Icons.Filled.ExitToApp, route = "logout_action")
        ))
        menuItems
    }

    val showAppBars = currentRoute != AppDestinations.LOGIN_ROUTE && currentRoute != AppDestinations.REGISTER_ROUTE

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = showAppBars && drawerState.isOpen,
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
                                if (item.route == "logout_action") {
                                    scope.launch { drawerState.close() }
                                    authViewModel.logout()
                                    return@NavigationDrawerItem
                                }

                                val performNavigationLambda = {
                                    if (currentRoute != item.route) {
                                        if (currentRoute == AppDestinations.VIDEO_CREATION_WORKFLOW && item.route != AppDestinations.VIDEO_CREATION_WORKFLOW) {
                                            videoWorkflowViewModel.triggerProjectSave()
                                        }
                                        // CORREÇÃO: Usa navegação segura sem graph
                                        safeNavigate(item.route)
                                    }
                                }

                                val isOnContextTabAndDirty = currentRoute == AppDestinations.VIDEO_CREATION_WORKFLOW &&
                                    selectedWorkflowTabIndex == 0 && isContextScreenDirty &&
                                    item.route != AppDestinations.VIDEO_CREATION_WORKFLOW

                                scope.launch { drawerState.close() }
                                if (isOnContextTabAndDirty) {
                                    videoWorkflowViewModel.setPendingNavigationAction(performNavigationLambda)
                                } else {
                                    performNavigationLambda()
                                }
                            },
                            icon = {
                                item.iconResId?.let { Icon(painterResource(id = it), item.title) }
                                    ?: item.iconImageVector?.let { Icon(it, item.title) }
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
                if (showAppBars) {
                    Column {
                        TopAppBar(
                            title = {
                                Text(
                                    when {
                                        currentRoute == AppDestinations.PROJECT_MANAGEMENT_ROUTE -> stringResource(R.string.top_bar_title_project_management)
                                        currentRoute == AppDestinations.ADD_GEMINI_API_KEY_ROUTE -> "Admin: Adicionar Chave"
                                        isWorkflowScreen && selectedWorkflowTabIndex < workflowStages.size -> workflowStages[selectedWorkflowTabIndex].title
                                        else -> stringResource(R.string.top_bar_title_default)
                                    }
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Filled.Menu, stringResource(R.string.content_desc_open_menu))
                                }
                            },
                            actions = {
                                userProfile?.let { profile ->
                                    if (!profile.isPremium) {
                                        CreditCounter(credits = profile.creditos)
                                    }
                                }
                            }
                        )
                        if (isWorkflowScreen) {
                            ScrollableTabRow(selectedTabIndex = selectedWorkflowTabIndex, edgePadding = 0.dp) {
                                workflowStages.forEachIndexed { index, stage ->
                                    Tab(
                                        selected = selectedWorkflowTabIndex == index,
                                        onClick = { videoWorkflowViewModel.attemptToChangeTab(index) },
                                        text = { Text(stage.title) },
                                        enabled = !isCurrentStageProcessing
                                    )
                                }
                            }
                        }
                    }
                }
            },
            bottomBar = {
                if (isWorkflowScreen) {
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
                        onShareVideoClick = currentShareVideoActionFromVM,
                        onBackClick = {
                            if (selectedWorkflowTabIndex > 0 && !isCurrentStageProcessing) {
                                videoWorkflowViewModel.attemptToChangeTab(selectedWorkflowTabIndex - 1)
                            }
                        },
                        onNextClick = {
                            if (selectedWorkflowTabIndex < workflowStages.size - 1 && !isCurrentStageProcessing) {
                                videoWorkflowViewModel.attemptToChangeTab(selectedWorkflowTabIndex + 1)
                            }
                        }
                    )
                }
            },
            snackbarHost = { SnackbarHost(globalSnackbarHostState) }
        ) { scaffoldInnerPadding ->
            
            // CORREÇÃO: NavHost só é criado quando o NavController está pronto
            if (isNavControllerReady) {
                NavHost(
                    navController = navController,
                    startDestination = if (currentUser != null) AppDestinations.PROJECT_MANAGEMENT_ROUTE else AppDestinations.LOGIN_ROUTE,
                    modifier = Modifier.padding(scaffoldInnerPadding)
                ) {
                    composable(AppDestinations.LOGIN_ROUTE) { LoginScreen(navController, authViewModel) }
                    composable(AppDestinations.REGISTER_ROUTE) { RegistrationScreen(navController, authViewModel) }
                    composable(AppDestinations.PREMIUM_OFFER_ROUTE) { PremiumOfferScreen(billingViewModel) }
                    composable(AppDestinations.ADD_GEMINI_API_KEY_ROUTE) {
                        if (currentUser?.uid == adminUid) {
                            AddGeminiApiKeyScreen(viewModel())
                        } else {
                            LaunchedEffect(Unit) {
                                Log.w("NavHost", "Acesso não autorizado à rota de admin negado. Voltando...")
                                navController.popBackStack()
                            }
                            Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Acesso Negado.") }
                        }
                    }
                    composable(AppDestinations.VIDEO_CREATION_WORKFLOW) {
                       /* VideoCreationWorkflowScreen(
                            navController, globalSnackbarHostState, PaddingValues(0.dp),
                            videoWorkflowViewModel, audioViewModel, videoInfoViewModel,
                            refImageInfoViewModel, videoProjectViewModel, videoGeneratorViewModel,
                            videoGeneratorViewModel.generatedVideoPath.collectAsState().value
                        )*/
                        
                        VideoCreationWorkflowScreen(
                            navController = navController,
                            snackbarHostState = remember { SnackbarHostState() }, // Pode usar um global se preferir
                            innerPadding = PaddingValues(0.dp), // O padding principal já foi aplicado
                            videoWorkflowViewModel = videoWorkflowViewModel,
                            audioViewModel = audioViewModel,
                            videoInfoViewModel = videoInfoViewModel,
                            refImageInfoViewModel = refImageInfoViewModel,
                            videoProjectViewModel = videoProjectViewModel,
                            videoGeneratorViewModel = videoGeneratorViewModel,
                            authViewModel = authViewModel, // Passando o AuthViewModel
                            generatedVideoPath = videoGeneratorViewModel.generatedVideoPath.collectAsState().value
                        )
                    }
                    
                    
                      
                    
                    
                    composable(AppDestinations.PROJECT_MANAGEMENT_ROUTE) {
                        val factory = ProjectManagementViewModelFactory(application)
                        val projectManagementViewModelInstance: ProjectManagementViewModel = viewModel(factory = factory)
                        ProjectManagementScreen(navController, projectManagementViewModelInstance)
                    }
                    composable(AppDestinations.USERINFO_ROUTE) { UserInfoScreen(navController) }
                    composable(AppDestinations.SETTINGS_ROUTE) { VideoPreferencesScreen(navController) }
                    composable(AppDestinations.ABOUT_ROUTE) { AboutScreen { navController.popBackStack() } }
                    composable(AppDestinations.IMPORT_DATA_URL_ROUTE) { Text(stringResource(R.string.placeholder_import_data_url)) }
                    composable(AppDestinations.CHAT_ROUTE) { Text(stringResource(R.string.placeholder_chat_screen)) }
                }
            } else {
                // Tela de loading
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

// WorkflowBottomBar permanece igual, mas adicione WORKFLOW_STAGE_AUDIO caso falte
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
        if (isCurrentStageProcessing) {
            if (currentStageNumericProgress > 0f && workflowStages.getOrNull(selectedTabIndex)?.identifier == AppDestinations.WORKFLOW_STAGE_FINALIZE) {
                LinearProgressIndicator({ currentStageNumericProgress }, Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        } else {
            Divider()
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            Arrangement.SpaceBetween, Alignment.CenterVertically
        ) {
            Button(onClick = onBackClick, enabled = selectedTabIndex > 0 && !isCurrentStageProcessing) {
                Text(stringResource(R.string.bottom_bar_action_back))
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(2f)) {
                val currentStageId = workflowStages.getOrNull(selectedTabIndex)?.identifier
                if (isCurrentStageProcessing) {
                    Text(currentStageProgressText, Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                } else {
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), Alignment.CenterVertically) {
                        when (currentStageId) {
                            AppDestinations.WORKFLOW_STAGE_CONTEXT -> Button(onSaveContextClick, enabled = isSaveContextEnabled) {
                                Icon(Icons.Filled.Save, null)
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.action_save))
                            }
                            AppDestinations.WORKFLOW_STAGE_IMAGES -> Button(onLaunchPickerClick) {
                                Icon(Icons.Filled.AddPhotoAlternate, null)
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.bottom_bar_action_add_image))
                            }
                            AppDestinations.WORKFLOW_STAGE_INFORMATION -> Button(onAnalyzeClick) {
                                Icon(Icons.Filled.AutoAwesome, null)
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.bottom_bar_action_analyze))
                            }
                            AppDestinations.WORKFLOW_STAGE_NARRATIVE -> Button(onCreateNarrativeClick) {
                                Icon(Icons.Filled.AutoStories, null)
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.bottom_bar_action_narrate))
                            }
                            AppDestinations.WORKFLOW_STAGE_AUDIO -> Button(onGenerateAudioClick) {
                                Icon(Icons.Filled.GraphicEq, null)
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.bottom_bar_action_generate_audio))
                            }
                            AppDestinations.WORKFLOW_STAGE_SCENES -> Button(onGenerateScenesClick) {
                                Icon(Icons.Filled.MovieCreation, null)
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.bottom_bar_action_generate_scenes))
                            }
                            AppDestinations.WORKFLOW_STAGE_FINALIZE -> Button(onRecordVideoClick) {
                                Icon(Icons.Filled.Videocam, null)
                                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                Text(stringResource(R.string.bottom_bar_action_record_video))
                            }
                            else -> Spacer(Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            if (selectedTabIndex == workflowStages.size - 1) {
                IconButton(onClick = { onShareVideoClick?.invoke() }, enabled = !isCurrentStageProcessing && onShareVideoClick != null) {
                    Icon(Icons.Filled.Share, stringResource(R.string.bottom_bar_action_share_video))
                }
            } else {
                Button(onClick = onNextClick, enabled = !isCurrentStageProcessing) {
                    Text(stringResource(R.string.bottom_bar_action_next))
                }
            }
        }
    }
}

@Composable
private fun CreditCounter(credits: Long) {
    Row(
        modifier = Modifier
            .padding(end = 8.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.MonetizationOn,
            contentDescription = stringResource(R.string.content_desc_credits_icon),
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = credits.toString(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold
        )
    }
}
