package com.carlex.euia.utils

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.carlex.euia.R
import com.carlex.euia.MainActivity
import com.carlex.euia.services.OverlayService

object OverlayManager {
    private const val TAG = "OverlayManager"
    
    // **CORRIGIDO: Usar flag estática para controlar estado**
    private var isOverlayActive = false

    fun showOverlay(context: Context, message: String, progresso: Int) {
       // Log.w(TAG, "showOverlay message: $message, progresso: $progresso")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Permissão DRAW_OVER_OTHER_APPS não concedida. Solicitando.")
            requestOverlayPermission(context)
            return
        }

        val intent = Intent(context, OverlayService::class.java)
        
        // **CORRIGIDO: Usar flag local para decidir ação**
        if (!isOverlayActive) {
            //Log.d(TAG, "Criando novo overlay")
            intent.action = OverlayService.ACTION_SHOW_OVERLAY
            isOverlayActive = true
        } else {
            //Log.d(TAG, "Atualizando overlay existente")
            intent.action = OverlayService.ACTION_UPDATE_MESSAGE
        }
        
        intent.putExtra(OverlayService.EXTRA_OVERLAY_MESSAGE, message)
        intent.putExtra(OverlayService.EXTRA_OVERLAY_PROGRESSO, progresso.toString())
        
        context.startService(intent)
        //Log.i(TAG, "Comando para overlay enviado: ${intent.action}")
    }

    fun hideOverlay(context: Context) {
        //Log.d(TAG, "Escondendo overlay")
        
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_HIDE_OVERLAY
        }

        context.startService(intent)
        isOverlayActive = false // **CORRIGIDO: Resetar flag**
        //Log.i(TAG, "Comando para esconder overlay enviado.")
    }

    private fun requestOverlayPermission(context: Context) {
        if (context is MainActivity) {
            AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.overlay_permission_dialog_title))
                .setMessage(context.getString(R.string.overlay_permission_dialog_message))
                .setPositiveButton(context.getString(R.string.overlay_permission_dialog_confirm_button)) { dialog, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                    dialog.dismiss()
                }
                .setNegativeButton(context.getString(R.string.overlay_permission_dialog_dismiss_button)) { dialog, _ ->
                    Toast.makeText(context, context.getString(R.string.overlay_permission_denied_feedback), Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                }
                .create()
                .show()
        } else {
            Log.e(TAG, "Não é possível solicitar permissão DRAW_OVER_OTHER_APPS de um contexto que não seja uma Activity. Contexto recebido: ${context.javaClass.name}")
            Toast.makeText(context, context.getString(R.string.overlay_permission_denied_feedback), Toast.LENGTH_LONG).show()
        }
    }
}
