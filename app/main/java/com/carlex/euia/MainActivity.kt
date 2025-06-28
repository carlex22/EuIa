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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.carlex.euia.ui.AppNavigationHostComposable
import com.carlex.euia.utils.ProjectPersistenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    // Launcher para a permissão de notificações (se você precisar reativá-la)
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d("MainActivity", "Permissão POST_NOTIFICATIONS concedida.")
            } else {
                Log.w("MainActivity", "Permissão POST_NOTIFICATIONS negada.")
            }
        }

    // <<<<< NOVO LAUNCHER PARA A PERMISSÃO DE SOBREPOSIÇÃO >>>>>
    private val overlayPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Após o usuário retornar da tela de configurações, verificamos novamente
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, R.string.overlay_permission_granted, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.overlay_permission_denied, Toast.LENGTH_LONG).show()
                }
            }
        }

    // <<<<< NOVA FUNÇÃO PARA VERIFICAR E SOLICITAR A PERMISSÃO DE SOBREPOSIÇÃO >>>>>
    private fun checkAndRequestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Log.d("MainActivity", "Permissão de sobreposição não concedida. Solicitando ao usuário.")
                // Cria um Intent para abrir a tela de configurações específica do app
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                // Lança a activity de configurações esperando um resultado
                overlayPermissionLauncher.launch(intent)
            } else {
                Log.d("MainActivity", "Permissão de sobreposição já concedida.")
            }
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
                    // Aqui você poderia mostrar um diálogo explicando por que precisa da permissão
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
        
        // Solicita a permissão de sobreposição quando o app é iniciado
        checkAndRequestOverlayPermission()

        // Opcional: Descomente a linha abaixo para também pedir permissão de notificação
        // askNotificationPermission()

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
        // A lógica de salvar o projeto já está sendo feita de forma mais robusta no AppLifecycleObserver
        // Manter esta aqui pode ser redundante, mas não prejudicial.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ProjectPersistenceManager.saveProjectState(applicationContext)
                Log.i("MainActivity", "Estado do projeto salvo com sucesso no onStop da MainActivity.")
            } catch (e: Exception) {
                Log.e("MainActivity", "Erro ao salvar estado do projeto no onStop da MainActivity: ${e.message}", e)
            }
        }
    }

    /**
     * Salva o estado do projeto e fecha o aplicativo completamente.
     * Útil para um botão de "Salvar e Sair".
     */
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
                // Fecha todas as activities do app e encerra o processo
                finishAffinity()
            }
        }
    }
}