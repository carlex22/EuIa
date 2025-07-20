// File: euia/viewmodel/PermissionViewModel.kt
package com.carlex.euia.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlex.euia.data.AppStatusDataStoreManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.carlex.euia.utils.WorkerTags

class PermissionViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStoreManager = AppStatusDataStoreManager(application)
    private val TAG = "PermissionViewModel"
    private val appContext = application.applicationContext
    var isCheck = false

    private val _showInitialOverlayDialog = MutableStateFlow(false)
    val showInitialOverlayDialog = _showInitialOverlayDialog.asStateFlow()

    private val _showZombieWorkerDialog = MutableStateFlow(false)
    val showZombieWorkerDialog = _showZombieWorkerDialog.asStateFlow()

    fun checkOverlayPermissionOnStartup(context: Context) {
        viewModelScope.launch {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                val userHasIgnored = dataStoreManager.ignoreOverlayPermissionRequest.first()
                if (!userHasIgnored) {
                    Log.d(TAG, "Permissão de overlay necessária e não ignorada. Mostrando diálogo inicial.")
                    _showInitialOverlayDialog.value = true
                } else {
                    Log.d(TAG, "Permissão de overlay necessária, mas o usuário escolheu ignorar o pedido inicial.")
                }
            }
        }
    }

    fun ZombieWorkerDetected(context: Context) {
        viewModelScope.launch {
            if (!isCheck){
                val workManager = WorkManager.getInstance(context)
                val criticalTags = listOf(
                    WorkerTags.AUDIO_NARRATIVE,
                    WorkerTags.VIDEO_PROCESSING,
                    WorkerTags.IMAGE_PROCESSING_WORK,
                    WorkerTags.VIDEO_RENDER,
                    WorkerTags.SCENE_PREVIEW_WORK,
                    WorkerTags.URL_IMPORT_WORK,
                    WorkerTags.REF_IMAGE_ANALYSIS,
                    WorkerTags.POST_PRODUCTION
                )
                
                
            
                val workQuery = WorkQuery.Builder.fromTags(criticalTags).build()
                val activeWorkInfos = workManager.getWorkInfos(workQuery).get()
                val hasZombieWorkers = activeWorkInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                val userHasIgnoredInitial = dataStoreManager.ignoreOverlayPermissionRequest.first()
                val userHasIgnoredZombieWarning = dataStoreManager.ignoreZombieWorkerWarning.first()
                var showZombieWarningDialog = false 
                val showOverlay = Settings.canDrawOverlays(context)
                if (!showOverlay && userHasIgnoredInitial && !userHasIgnoredZombieWarning && hasZombieWorkers)
                    showZombieWarningDialog = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ) {
                    if (showZombieWarningDialog) {
                        Log.w(TAG, "Workers zumbis detectados e aviso de zumbi ainda não foi ignorado. Mostrando diálogo.")
                        _showZombieWorkerDialog.value = true
                    } else {
                        Log.d(TAG, "Aviso de worker zumbi não será mostrado. IgnoradoInicial: $userHasIgnoredInitial, IgnoradoAvisoZumbi: $userHasIgnoredZombieWarning")
                    }
                }
            }
        }
        isCheck = true 
    }

    fun onAuthorizeClicked(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(intent)
        _showInitialOverlayDialog.value = false
        _showZombieWorkerDialog.value = false
    }

    fun onIgnoreInitialDialogClicked() {
        viewModelScope.launch {
            dataStoreManager.setIgnoreOverlayPermissionRequest(true)
            _showInitialOverlayDialog.value = false
            Log.i(TAG, "Usuário escolheu ignorar o pedido de permissão de overlay (1ª vez).")
        }
    }
    
    fun onIgnoreZombieWarningClicked() {
        viewModelScope.launch {
            dataStoreManager.setIgnoreZombieWorkerWarning(true)
            _showZombieWorkerDialog.value = false
            Log.i(TAG, "Usuário escolheu ignorar o aviso de worker zumbi (2ª vez/final).")
        }
    }

    fun onDismissInitialDialog() {
        _showInitialOverlayDialog.value = false
    }
    
    fun onDismissZombieWarningDialog() {
        _showZombieWorkerDialog.value = false
    }
}