// File: euia/MainActivity.kt
package com.carlex.euia

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.navigation.compose.*
import com.carlex.euia.api.*
import com.carlex.euia.ui.AppNavigationHostComposable
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {
    
    private val showNotificationPermissionDialog = mutableStateOf(false)
    private val showOverlayPermissionDialog = mutableStateOf(false)

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d("MainActivity", "Permissão POST_NOTIFICATIONS concedida.")
            } else {
                Log.w("MainActivity", "Permissão POST_NOTIFICATIONS negada.")
                Toast.makeText(this, R.string.notification_permission_denied_feedback, Toast.LENGTH_LONG).show()
            }
        }

    // CORREÇÃO: Aguardar Firebase estar pronto
    private suspend fun ensureFirebaseInitialized() {
        withContext(Dispatchers.IO) {
            try {
                FirebaseApp.getInstance()
                Log.d("MainActivity", "Firebase já inicializado.")
            } catch (e: IllegalStateException) {
                Log.w("MainActivity", "Firebase não inicializado ainda, aguardando...")
                delay(200) // Aguarda mais tempo
                try {
                    FirebaseApp.getInstance()
                } catch (e2: IllegalStateException) {
                    Log.e("MainActivity", "Firebase não pôde ser inicializado: ${e2.message}")
                }
            }
        }
    }

    private fun checkAndRequestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            showOverlayPermissionDialog.value = true
        }
    }

    private fun launchOverlayPermissionIntent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
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
        GeminiTextAndVisionStandardApi.setApplicationContext(application)
        GeminiTextAndVisionProApi.setApplicationContext(application)
        GeminiTextAndVisionProRestApi.setApplicationContext(application)

        askNotificationPermission()
        checkAndRequestOverlayPermission()

        // CORREÇÃO: Aguardar Firebase antes de configurar UI
        lifecycleScope.launch {
            ensureFirebaseInitialized()
            
            // CORREÇÃO: Aguardar um pouco mais para garantir que tudo está pronto
            delay(300)
            
            setContent {
                MaterialTheme {
                    val navController = rememberNavController()
                    
                    // CORREÇÃO: Aguardar mais um frame
                    LaunchedEffect(Unit) {
                        delay(100)
                    }
                    
                    AppNavigationHostComposable(
                        navController = navController,
                        mainActivityContext = this@MainActivity
                    )

                    // Diálogos de permissão permanecem iguais
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

                    if (showOverlayPermissionDialog.value) {
                        AlertDialog(
                            onDismissRequest = { showOverlayPermissionDialog.value = false },
                            title = { Text(stringResource(R.string.overlay_permission_dialog_title)) },
                            text = { Text(stringResource(R.string.overlay_permission_dialog_message)) },
                            confirmButton = {
                                Button(onClick = {
                                    showOverlayPermissionDialog.value = false
                                    launchOverlayPermissionIntent()
                                }) { Text(stringResource(R.string.overlay_permission_dialog_confirm_button)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showOverlayPermissionDialog.value = false }) {
                                    Text(stringResource(R.string.overlay_permission_dialog_dismiss_button))
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
