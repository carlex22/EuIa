// File: euia/MainActivity.kt
package com.carlex.euia

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import com.carlex.euia.api.*
import com.carlex.euia.ui.AppNavigationHostComposable
import com.carlex.euia.viewmodel.PermissionViewModel
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    
    private val showNotificationPermissionDialog = mutableStateOf(false) 

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d("MainActivity", "Permissão POST_NOTIFICATIONS concedida.")
            } else {
                Log.w("MainActivity", "Permissão POST_NOTIFICATIONS negada.")
                Toast.makeText(this, R.string.notification_permission_denied_feedback, Toast.LENGTH_LONG).show()
            }
        }

    private suspend fun ensureFirebaseInitialized() {
        withContext(Dispatchers.IO) {
            try {
                FirebaseApp.getInstance()
                Log.d("MainActivity", "Firebase já inicializado.")
            } catch (e: IllegalStateException) {
                Log.w("MainActivity", "Firebase não inicializado ainda, aguardando...")
                delay(200) 
                try {
                    FirebaseApp.getInstance()
                } catch (e2: IllegalStateException) {
                    Log.e("MainActivity", "Firebase não pôde ser inicializado: ${e2.message}")
                }
            }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d("MainActivity", "Permissão de notificação já concedida.")
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    showNotificationPermissionDialog.value = true
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.i("MainActivity", "Injetando contexto da aplicação nas classes de API.")
        GeminiTextAndVisionProApi.setApplicationContext(application)
        GeminiTextAndVisionProRestApi.setApplicationContext(application)

        askNotificationPermission()
        
        lifecycleScope.launch {
            ensureFirebaseInitialized()
            delay(300)
            
            setContent {
                val permissionViewModel: PermissionViewModel = viewModel()
                val showOverlayDialog by permissionViewModel.showInitialOverlayDialog.collectAsState()
                val showZombieWarningDialog by permissionViewModel.showZombieWorkerDialog.collectAsState()

                LaunchedEffect(Unit) {
                    permissionViewModel.checkOverlayPermissionOnStartup(this@MainActivity)
                    permissionViewModel.ZombieWorkerDetected(this@MainActivity)
                }

                MaterialTheme {
                    val navController = rememberNavController()
                    AppNavigationHostComposable(
                        navController = navController,
                        mainActivityContext = this@MainActivity
                    )

                    // Diálogo inicial para permissão de sobreposição
                    if (showOverlayDialog) {
                        AlertDialog(
                            onDismissRequest = { /* Não permite fechar clicando fora */ },
                            title = { Text(stringResource(R.string.overlay_permission_dialog_title)) },
                            text = { Text(stringResource(R.string.overlay_permission_dialog_message)) },
                            confirmButton = {
                                Button(onClick = { permissionViewModel.onAuthorizeClicked(this@MainActivity) }) {
                                    Text(stringResource(R.string.overlay_permission_dialog_confirm_button))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { permissionViewModel.onIgnoreInitialDialogClicked() }) {
                                    Text(stringResource(R.string.overlay_permission_dialog_ignore_button))
                                }
                            }
                        )
                    }
                    
                    // Diálogo de aviso sobre "workers zumbis"
                    if (showZombieWarningDialog) {
                        AlertDialog(
                            onDismissRequest = { permissionViewModel.onDismissZombieWarningDialog() },
                            title = { Text(stringResource(R.string.zombie_worker_dialog_title)) },
                            text = { Text(stringResource(R.string.zombie_worker_dialog_message)) },
                            confirmButton = {
                                Button(onClick = { permissionViewModel.onAuthorizeClicked(this@MainActivity) }) {
                                    Text(stringResource(R.string.overlay_permission_dialog_confirm_button))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { permissionViewModel.onIgnoreZombieWarningClicked() }) {
                                    Text(stringResource(R.string.overlay_permission_dialog_ignore_button)) // Reutilizando a string "Ignorar"
                                }
                            }
                        )
                    }

                    // Diálogo de permissão de notificação (sem alterações)
                    if (showNotificationPermissionDialog.value) {
                        AlertDialog(
                            onDismissRequest = { showNotificationPermissionDialog.value = false },
                            title = { Text(stringResource(R.string.notification_permission_dialog_title)) },
                            text = { Text(stringResource(R.string.notification_permission_dialog_message)) },
                            confirmButton = {
                                Button(onClick = {
                                    showNotificationPermissionDialog.value = false
                                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }) { Text(stringResource(R.string.notification_permission_dialog_confirm_button)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showNotificationPermissionDialog.value = false }) {
                                    Text(stringResource(R.string.notification_permission_dialog_dismiss_button))
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d("MainActivity", "onStop chamado.")
    }

    override fun onStart() {
        super.onStart()
        Log.d("MainActivity", "onStart chamado.")
    }
}