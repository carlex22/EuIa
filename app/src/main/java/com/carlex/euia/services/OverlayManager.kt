// File: euia/utils/OverlayManager.kt
package com.carlex.euia.utils

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.carlex.euia.R
import com.carlex.euia.MainActivity // Importar MainActivity corretamente
import com.carlex.euia.services.OverlayService // Importar o serviço que vamos criar

object OverlayManager {
    private const val TAG = "OverlayManager"
    private var isOverlayShowing = false
    private var isMonitoringWork = false
    private var workObserver: Observer<List<WorkInfo>>? = null
    private var currentContext: Context? = null // Para reter o contexto da aplicação

    // Método para iniciar o monitoramento dos Workers
    fun monitorAndShowOverlayIfNeeded(context: Context) {
        if (isMonitoringWork) {
            Log.d(TAG, "Já está monitorando trabalhos. Ignorando nova chamada.")
            return
        }
        currentContext = context.applicationContext // Use o contexto da aplicação para evitar vazamentos
        val workManager = WorkManager.getInstance(currentContext!!)

        // Crie um Observer se ainda não existir
        if (workObserver == null) {
            workObserver = Observer { workInfos ->
                val isVideoRenderWorkerRunning = workInfos.any {
                    it.tags.contains(WorkerTags.VIDEO_RENDER) &&
                            (it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING)
                }

                if (isVideoRenderWorkerRunning) {
                    Log.d(TAG, "VideoRenderWorker está ativo. Tentando mostrar overlay.")
                    showOverlay(currentContext!!, currentContext!!.getString(R.string.overlay_render_in_progress))
                } else {
                    Log.d(TAG, "Nenhum VideoRenderWorker ativo. Escondendo overlay.")
                    hideOverlay(currentContext!!)
                }
            }
        }

        // Adicione o observador ao LiveData de WorkInfo.
        // O observeForever é necessário porque o monitoramento pode continuar mesmo sem uma Activity em primeiro plano.
        workManager.getWorkInfosByTagLiveData(WorkerTags.VIDEO_RENDER).observeForever(workObserver!!)
        isMonitoringWork = true
        Log.d(TAG, "Iniciou o monitoramento do VideoRenderWorker para o overlay.")
    }

    // Método para parar o monitoramento (chame em MyApplication.onTerminate ou similar se necessário)
    fun stopMonitoringWork() {
        if (isMonitoringWork && workObserver != null && currentContext != null) {
            WorkManager.getInstance(currentContext!!).getWorkInfosByTagLiveData(WorkerTags.VIDEO_RENDER).removeObserver(workObserver!!)
            isMonitoringWork = false
            currentContext = null
            workObserver = null
            Log.d(TAG, "Parou o monitoramento do VideoRenderWorker.")
        }
    }

    fun showOverlay(context: Context, message: String) {
        if (isOverlayShowing) {
            Log.d(TAG, "Overlay já está visível. Atualizando mensagem.")
            // Você pode querer enviar uma broadcast para o OverlayService para atualizar a mensagem
            val intent = Intent(context, OverlayService::class.java).apply {
                action = OverlayService.ACTION_UPDATE_MESSAGE
                putExtra(OverlayService.EXTRA_OVERLAY_MESSAGE, message)
            }
            context.startService(intent)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Permissão DRAW_OVER_OTHER_APPS não concedida. Solicitando.")
            requestOverlayPermission(context)
            return
        }

        // Se a permissão for concedida ou não for necessária
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW_OVERLAY
            putExtra(OverlayService.EXTRA_OVERLAY_MESSAGE, message)
        }
        context.startService(intent)
        isOverlayShowing = true
        Log.i(TAG, "Comando para mostrar overlay enviado.")
    }

    fun hideOverlay(context: Context) {
        if (!isOverlayShowing) {
            Log.d(TAG, "Overlay não está visível. Nada para esconder.")
            return
        }
        val intent = Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_HIDE_OVERLAY
        }
        context.startService(intent)
        isOverlayShowing = false
        Log.i(TAG, "Comando para esconder overlay enviado.")
    }

    private fun requestOverlayPermission(context: Context) {
        // Use um AlertDialog simples para informar o usuário. Isso não pode ser um Compose Dialog.
        // Esta lógica deve ser chamada de uma Activity, ou você terá problemas de contexto.
        // O contexto passado aqui é geralmente o `applicationContext`, por isso precisamos do cast seguro para Activity.
        if (context is MainActivity) { // Verifique se o contexto é uma Activity
            AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.overlay_permission_dialog_title))
                .setMessage(context.getString(R.string.overlay_permission_dialog_message))
                .setPositiveButton(context.getString(R.string.overlay_permission_dialog_confirm_button)) { dialog, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"))
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