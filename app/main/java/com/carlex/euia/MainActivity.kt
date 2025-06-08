// File: MainActivity.kt
package com.carlex.euia

import android.Manifest
import android.app.Application // Necessário para o ViewModelFactory
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
// import androidx.activity.OnBackPressedCallback // Não mais necessário aqui
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
// import androidx.annotation.DrawableRes // Não mais necessário aqui
// import androidx.compose.foundation.layout.* // Movido para AppNavigationHostComposable
// import androidx.compose.material.icons.Icons // Movido
// import androidx.compose.material.icons.filled.* // Movido
import androidx.compose.material3.*
// import androidx.compose.runtime.* // Movido
// import androidx.compose.ui.graphics.vector.ImageVector // Não mais necessário aqui
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel // Ainda pode ser usado aqui para outros ViewModels se necessário
import androidx.navigation.compose.*
import com.carlex.euia.data.*
import com.carlex.euia.ui.AppNavigationHostComposable // Importa o novo Composable
import com.carlex.euia.utils.ProjectPersistenceManager
import com.carlex.euia.viewmodel.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File



// Factory para ProjectManagementViewModel (pode continuar aqui ou ser movida para perto do ViewModel)
class ProjectManagementViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ProjectManagementViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ProjectManagementViewModel(
                application,
                VideoPreferencesDataStoreManager(application.applicationContext),
                AudioDataStoreManager(application.applicationContext),
                RefImageDataStoreManager(application.applicationContext),
                VideoDataStoreManager(application.applicationContext),
                VideoGeneratorDataStoreManager(application.applicationContext),
                VideoProjectDataStoreManager(application.applicationContext)
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class for ProjectManagementViewModelFactory")
    }
}


@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d("MainActivity", "Permissão POST_NOTIFICATIONS concedida.")
            } else {
                Log.w("MainActivity", "Permissão POST_NOTIFICATIONS negada.")
            }
        }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> {
                    Log.d("MainActivity", "Permissão POST_NOTIFICATIONS já concedida.")
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    Log.d("MainActivity", "Mostrando rationale para permissão POST_NOTIFICATIONS.")
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    Log.d("MainActivity", "Solicitando permissão POST_NOTIFICATIONS.")
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //askNotificationPermission()
        setContent {
            MaterialTheme {
                val navController = rememberNavController()
                AppNavigationHostComposable(
                    navController = navController,
                    mainActivityContext = this
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d("MainActivity", "onStop chamado, tentando salvar o estado do projeto.")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ProjectPersistenceManager.saveProjectState(applicationContext)
                Log.i("MainActivity", "Estado do projeto salvo com sucesso no onStop.")
            } catch (e: Exception) {
                Log.e("MainActivity", "Erro ao salvar estado do projeto no onStop: ${e.message}", e)
            }
        }
    }

    fun saveAndExitApp() {
        Log.d("MainActivity", "saveAndExitApp chamado, tentando salvar e sair.")
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ProjectPersistenceManager.saveProjectState(applicationContext)
                    Log.i("MainActivity", "Estado do projeto salvo com sucesso antes de sair.")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Erro ao salvar estado do projeto antes de sair: ${e.message}", e)
            } finally {
                finishAffinity()
            }
        }
    }
}