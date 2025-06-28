// File: euia/startup/FirebaseInitializer.kt
package com.carlex.euia.startup

import android.content.Context
import android.util.Log
import androidx.startup.Initializer
import androidx.work.WorkManagerInitializer
import com.carlex.euia.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class FirebaseInitializer : Initializer<FirebaseApp> {
    
    override fun create(context: Context): FirebaseApp {
        Log.d("FirebaseInitializer", "Iniciando 'create'...")
        
        return runBlocking(Dispatchers.IO) {
            Log.d("FirebaseInitializer", "Dentro do runBlocking(Dispatchers.IO). Inicializando Firebase...")
            
            // CORREÇÃO: Garante que o Firebase é inicializado corretamente
            val app = FirebaseApp.initializeApp(context) ?: FirebaseApp.getInstance()
            
            // Configura o Firebase App Check
            val firebaseAppCheck = FirebaseAppCheck.getInstance()
            if (BuildConfig.DEBUG) {
                Log.i("FirebaseInitializer", "Usando DebugAppCheckProviderFactory para App Check.")
                firebaseAppCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
            } else {
                Log.i("FirebaseInitializer", "Usando PlayIntegrityAppCheckProviderFactory para App Check.")
                firebaseAppCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
            }
            
            Log.d("FirebaseInitializer", "Inicialização do Firebase concluída.")
            app
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        return listOf(WorkManagerInitializer::class.java)
    }
}
