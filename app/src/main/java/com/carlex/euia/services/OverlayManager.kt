// File: euia/utils/OverlayManager.kt
package com.carlex.euia.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.carlex.euia.R
import com.carlex.euia.data.AppStatusDataStoreManager
import com.carlex.euia.services.OverlayService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object OverlayManager {
    private const val TAG = "OverlayManager"
    
    private var isOverlayActive = false

    fun showOverlay(context: Context, message: String, progresso: Int) {
        // --- INÍCIO DA LÓGICA DE VERIFICAÇÃO ---
        // 1. Verifica se a permissão é necessária e se foi concedida
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            // Usa runBlocking aqui porque esta função não é suspend, mas precisamos ler o DataStore.
            // É rápido o suficiente para não causar problemas.
            val userIgnored = runBlocking {
                AppStatusDataStoreManager(context).ignoreOverlayPermissionRequest.first()
            }
            // 2. Se a permissão foi negada E o usuário escolheu ignorar, não fazemos nada.
            if (userIgnored) {
                Log.w(TAG, "Permissão de sobreposição negada e ignorada pelo usuário. O serviço de overlay não será iniciado.")
                return // Impede o início do serviço e o crash
            }
        }
        // --- FIM DA LÓGICA DE VERIFICAÇÃO ---

        val intent = Intent(context, OverlayService::class.java)
        
        if (!isOverlayActive) {
            intent.action = OverlayService.ACTION_SHOW_OVERLAY
            isOverlayActive = true
        } else {
            intent.action = OverlayService.ACTION_UPDATE_MESSAGE
        }
        
        intent.putExtra(OverlayService.EXTRA_OVERLAY_MESSAGE, message)
        intent.putExtra(OverlayService.EXTRA_OVERLAY_PROGRESSO, progresso.toString())
        
        // Se a permissão não foi ignorada, mas ainda não foi dada, a chamada abaixo pode falhar.
        // O ideal é que a MainActivity já tenha pedido a permissão.
        // Adicionamos um try-catch por segurança extra.
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar OverlayService. O usuário concedeu a permissão de sobreposição?", e)
            // Informa ao usuário que a funcionalidade não funcionará.
            Toast.makeText(context, R.string.overlay_service_start_failed, Toast.LENGTH_LONG).show()
        }
    }

    fun hideOverlay(context: Context) {
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_HIDE_OVERLAY
        }
        // É seguro chamar stopService mesmo que o serviço não esteja em execução.
        context.startService(intent)
        isOverlayActive = false
    }
}