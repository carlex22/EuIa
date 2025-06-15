// File: euia/ui/AppNavigationHost.kt
package com.carlex.euia.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
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
import com.carlex.euia.*
import com.carlex.euia.R
import com.carlex.euia.viewmodel.ProjectManagementViewModelFactory
import com.carlex.euia.viewmodel.*
import com.carlex.euia.utils.shareVideoFile
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigationHostComposable(
    navController: NavHostController,
    mainActivityContext: Context
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val application = LocalContext.current.applicationContext as Application

    // ViewModels
    val authViewModel: AuthViewModel = viewModel()
    val billingViewModel: BillingViewModel = viewModel()
    val currentUser by authViewModel.currentUser.collectAsState()
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

    // Lógica de redirecionamento de autenticação
    LaunchedEffect(currentUser) {
        val isAuthScreen = currentRoute == AppDestinations.LOGIN_ROUTE || currentRoute == AppDestinations.REGISTER_ROUTE
        if (currentUser == null) {
            if (!isAuthScreen) {
                navController.navigate(AppDestinations.LOGIN_ROUTE) {
                    popUpTo(navController.graph.id) { inclusive = true }
                }
            }
        } else {
            if (isAuthScreen) {
                navController.navigate(AppDestinations.PROJECT_MANAGEMENT_ROUTE) {
                    popUpTo(navController.graph.id) { inclusive = true }
                }
            }
        }
    }

    // Lógica do botão Voltar
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
            DrawerMenuItem(mainActivityContext.getString(R.string.drawer_menu_logout), iconImageVector = Icons.Filled.ExitToApp, route = "logout_action")
        )
    }

    val showAppBars = currentRoute != AppDestinations.LOGIN_ROUTE && currentRoute != AppDestinations.REGISTER_ROUTE

    ModalNavigationDrawer(
        drawerState = drawerState,
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
                                        navController.navigate(item.route) {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
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
                                item.iconResId?.let { Icon(painter = painterResource(id = it), contentDescription = item.title) }
                                    ?: item.iconImageVector?.let { Icon(imageVector = it, contentDescription = item.title) }
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
                                        isWorkflowScreen && selectedWorkflowTabIndex < workflowStages.size -> workflowStages[selectedWorkflowTabIndex].title
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
            NavHost(
                navController = navController,
                startDestination = if (currentUser != null) AppDestinations.PROJECT_MANAGEMENT_ROUTE else AppDestinations.LOGIN_ROUTE,
                modifier = Modifier.padding(scaffoldInnerPadding)
            ) {
                composable(AppDestinations.LOGIN_ROUTE) { LoginScreen(navController = navController, authViewModel = authViewModel) }
                composable(AppDestinations.REGISTER_ROUTE) { RegistrationScreen(navController = navController, authViewModel = authViewModel) }
                composable(AppDestinations.PREMIUM_OFFER_ROUTE) { PremiumOfferScreen(billingViewModel = billingViewModel) }
                composable(AppDestinations.VIDEO_CREATION_WORKFLOW) {
                    VideoCreationWorkflowScreen(
                        navController = navController,
                        snackbarHostState = globalSnackbarHostState,
                        innerPadding = PaddingValues(0.dp),
                        videoWorkflowViewModel = videoWorkflowViewModel,
                        audioViewModel = audioViewModel,
                        videoInfoViewModel = videoInfoViewModel,
                        refImageInfoViewModel = refImageInfoViewModel,
                        videoProjectViewModel = videoProjectViewModel,
                        videoGeneratorViewModel = videoGeneratorViewModel,
                        generatedVideoPath = videoGeneratorViewModel.generatedVideoPath.collectAsState().value
                    )
                }
                composable(AppDestinations.PROJECT_MANAGEMENT_ROUTE) {
                    val factory = ProjectManagementViewModelFactory(application)
                    val projectManagementViewModelInstance: ProjectManagementViewModel = viewModel(factory = factory)
                    ProjectManagementScreen(navController = navController, projectManagementViewModel = projectManagementViewModelInstance)
                }
                composable(AppDestinations.USERINFO_ROUTE) { UserInfoScreen(navController = navController) }
                composable(AppDestinations.SETTINGS_ROUTE) { VideoPreferencesScreen(navController = navController) }
                composable(AppDestinations.ABOUT_ROUTE) { AboutScreen(onNavigateBack = { navController.popBackStack() }) }
                composable(AppDestinations.IMPORT_DATA_URL_ROUTE) { Text(stringResource(R.string.placeholder_import_data_url)) }
                composable(AppDestinations.CHAT_ROUTE) { Text(stringResource(R.string.placeholder_chat_screen)) }
            }
        }
    }
}


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
                LinearProgressIndicator(progress = { currentStageNumericProgress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        } else {
            Divider()
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onBackClick,
                enabled = selectedTabIndex > 0 && !isCurrentStageProcessing
            ) { Text(stringResource(R.string.bottom_bar_action_back)) }

            Spacer(Modifier.width(8.dp))

            Box(modifier = Modifier.weight(2f)) {
                val currentStageId = workflowStages.getOrNull(selectedTabIndex)?.identifier
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // --- INÍCIO DA MODIFICAÇÃO: Lógica `enabled` ---
                    // A variável `isCurrentStageProcessing` agora controla o estado `enabled` de todos os botões de ação.
                    when (currentStageId) {
                        AppDestinations.WORKFLOW_STAGE_CONTEXT -> {
                            Button(onClick = onSaveContextClick, enabled = isSaveContextEnabled && !isCurrentStageProcessing) {
                                Icon(Icons.Filled.Save, null); Spacer(Modifier.size(ButtonDefaults.IconSpacing)); Text(stringResource(R.string.action_save))
                            }
                        }
                        AppDestinations.WORKFLOW_STAGE_IMAGES -> {
                            Button(onClick = onLaunchPickerClick, enabled = !isCurrentStageProcessing) {
                                Icon(Icons.Filled.AddPhotoAlternate, null); Spacer(Modifier.size(ButtonDefaults.IconSpacing)); Text(stringResource(R.string.bottom_bar_action_add_image))
                            }
                        }
                        AppDestinations.WORKFLOW_STAGE_INFORMATION -> {
                            Button(onClick = onAnalyzeClick, enabled = !isCurrentStageProcessing) {
                                Icon(Icons.Filled.AutoAwesome, null); Spacer(Modifier.size(ButtonDefaults.IconSpacing)); Text(stringResource(R.string.bottom_bar_action_analyze))
                            }
                        }
                        AppDestinations.WORKFLOW_STAGE_NARRATIVE -> {
                            Button(onClick = onCreateNarrativeClick, enabled = !isCurrentStageProcessing) {
                                Icon(Icons.Filled.AutoStories, null); Spacer(Modifier.size(ButtonDefaults.IconSpacing)); Text(stringResource(R.string.bottom_bar_action_narrate))
                            }
                        }
                        AppDestinations.WORKFLOW_STAGE_SCENES -> {
                            Button(onClick = onGenerateScenesClick, enabled = !isCurrentStageProcessing) {
                                Icon(Icons.Filled.MovieCreation, null); Spacer(Modifier.size(ButtonDefaults.IconSpacing)); Text(stringResource(R.string.bottom_bar_action_generate_scenes))
                            }
                        }
                        AppDestinations.WORKFLOW_STAGE_FINALIZE -> {
                            Button(onClick = onRecordVideoClick, enabled = !isCurrentStageProcessing) {
                                Icon(Icons.Filled.Videocam, null); Spacer(Modifier.size(ButtonDefaults.IconSpacing)); Text(stringResource(R.string.bottom_bar_action_record_video))
                            }
                        }
                        else -> Spacer(Modifier.fillMaxWidth())
                    }
                    // --- FIM DA MODIFICAÇÃO ---
                }
            }
            Spacer(Modifier.width(8.dp))

            if (selectedTabIndex == workflowStages.size - 1) {
                IconButton(
                    onClick = { onShareVideoClick?.invoke() },
                    enabled = !isCurrentStageProcessing && onShareVideoClick != null
                ) {
                    Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.bottom_bar_action_share_video))
                }
            } else {
                Button(
                    onClick = onNextClick,
                    enabled = !isCurrentStageProcessing && selectedTabIndex < workflowStages.size - 1,
                ) { Text(stringResource(R.string.bottom_bar_action_next)) }
            }
        }
    }
}