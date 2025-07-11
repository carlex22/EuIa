// File: euia/startup/FirebaseInitializer.kt
package com.carlex.euia.startup

import android.content.Context
import android.util.Log
import androidx.startup.Initializer
import androidx.work.WorkManagerInitializer
import com.carlex.euia.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class FirebaseInitializer : Initializer<FirebaseApp> {

    override fun create(context: Context): FirebaseApp {
        Log.d("FirebaseInitializer", "Iniciando 'create'...")

        return runBlocking(Dispatchers.IO) {
            Log.d("FirebaseInitializer", "Dentro do runBlocking(Dispatchers.IO). Inicializando Firebase...")

            val app = FirebaseApp.initializeApp(context) ?: FirebaseApp.getInstance()

            val firebaseAppCheck = FirebaseAppCheck.getInstance()

            // <<< CORREÇÃO PRINCIPAL COM REFLEXÃO >>>
            if (BuildConfig.DEBUG) {
                Log.i("FirebaseInitializer", "Build de DEBUG detectado. Tentando instalar provedor de depuração via reflexão.")
                try {
                    // 1. Encontrar a classe pelo nome (string)
                    val debugFactoryClass = Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory")
                    // 2. Obter o método estático 'getInstance'
                    val getInstanceMethod = debugFactoryClass.getMethod("getInstance")
                    // 3. Invocar o método para obter a instância da fábrica
                    val debugAppCheckProviderFactory = getInstanceMethod.invoke(null) as AppCheckProviderFactory
                    // 4. Instalar a fábrica
                    firebaseAppCheck.installAppCheckProviderFactory(debugAppCheckProviderFactory)
                    Log.i("FirebaseInitializer", "DebugAppCheckProviderFactory instalado com sucesso via reflexão.")
                } catch (e: Exception) {
                    Log.e("FirebaseInitializer", "Falha ao instalar o DebugAppCheckProviderFactory via reflexão.", e)
                    // Como fallback em caso de erro, podemos instalar o Play Integrity para que o app não quebre
                    installPlayIntegrity(firebaseAppCheck)
                }
            } else {
                installPlayIntegrity(firebaseAppCheck)
            }

            Log.d("FirebaseInitializer", "Inicialização do Firebase e App Check concluída.")
            app
        }
    }
    
    // Função auxiliar para evitar duplicação de código
    private fun installPlayIntegrity(firebaseAppCheck: FirebaseAppCheck) {
        Log.i("FirebaseInitializer", "Instalando PlayIntegrityAppCheckProviderFactory.")
        firebaseAppCheck.installAppCheckProviderFactory(
            PlayIntegrityAppCheckProviderFactory.getInstance()
        )
    }

    override fun dependencies(): List<Class<out Initializer<*>>> {
        return listOf(WorkManagerInitializer::class.java)
    }
}