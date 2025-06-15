// File: utils/ShareUtils.kt (ou AppNavigationModel.kt)
package com.carlex.euia.utils // Ou com.carlex.euia se estiver em AppNavigationModel.kt

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

fun shareVideoFile( // <<< --- É UMA FUNÇÃO, NÃO UMA CLASSE --- >>>
    context: Context,
    filePath: String
) {
    if (filePath.isBlank()) {
        Toast.makeText(context, "Nenhum vídeo para compartilhar.", Toast.LENGTH_SHORT).show()
        return
    }
    val file = File(filePath)
    if (!file.exists()) {
        Toast.makeText(context, "Arquivo de vídeo não encontrado.", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider", // Garante que a authority está correta
            file
        )
        val shareIntent: Intent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, uri)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Compartilhar vídeo via"))
    } catch (e: Exception) {
        Log.e("ShareUtils", "Erro ao compartilhar vídeo: ${e.message}", e) // Tag ajustada
        Toast.makeText(context, "Erro ao compartilhar vídeo.", Toast.LENGTH_SHORT).show()
    }
}