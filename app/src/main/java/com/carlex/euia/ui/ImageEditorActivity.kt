// File: euia/ui/ImageEditorActivity.kt
package com.carlex.euia.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.carlex.euia.R
import com.yalantis.ucrop.UCrop
import ja.burhanrashid52.photoeditor.OnSaveBitmap
import ja.burhanrashid52.photoeditor.PhotoEditor
import ja.burhanrashid52.photoeditor.PhotoEditorView
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*

class ImageEditorActivity : AppCompatActivity() {

    private lateinit var photoEditor: PhotoEditor
    private lateinit var photoEditorView: PhotoEditorView
    private var sourceUri: Uri? = null

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val EXTRA_EDITED_IMAGE_PATH = "extra_edited_image_path"
        private const val TAG = "ImageEditorActivity"
    }

    private val ucropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val resultUri = UCrop.getOutput(result.data!!)
            if (resultUri != null) {
                sourceUri = resultUri
                photoEditorView.source.setImageURI(sourceUri)
                Log.d(TAG, "Recorte bem-sucedido. Nova URI: $resultUri")
            } else {
                Log.e(TAG, "uCrop retornou OK mas a URI do resultado é nula.")
            }
        } else if (result.resultCode == UCrop.RESULT_ERROR) {
            val cropError = UCrop.getError(result.data!!)
            Log.e(TAG, "Erro no recorte (uCrop): ", cropError)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_editor)

        photoEditorView = findViewById(R.id.photoEditorView)
        sourceUri = intent.getParcelableExtra(EXTRA_IMAGE_URI)

        if (sourceUri == null) {
            Log.e(TAG, "Nenhuma URI de imagem fornecida. Finalizando a activity.")
            finish()
            return
        }

        photoEditorView.source.setImageURI(sourceUri)

        photoEditor = PhotoEditor.Builder(this, photoEditorView)
            .setPinchTextScalable(true)
            .build()
        
        setupToolbar()
    }

    private fun setupToolbar() {
        val toolbar: Toolbar = findViewById(R.id.editor_toolbar)
        setSupportActionBar(toolbar)

        findViewById<View>(R.id.action_save).setOnClickListener { saveImageAndFinish() }
        findViewById<View>(R.id.action_rotate).setOnClickListener { 
            photoEditorView.rotation += 90f 
        }
        findViewById<View>(R.id.action_add_text).setOnClickListener { showAddTextDialog() }
        findViewById<View>(R.id.action_crop).setOnClickListener { launchUCrop(sourceUri!!) }
    }

    private fun launchUCrop(source: Uri) {
        val destinationFileName = "ucrop_${System.currentTimeMillis()}.jpg"
        val destinationUri = Uri.fromFile(File(cacheDir, destinationFileName))
        
        val options = UCrop.Options().apply {
            setCompressionQuality(90)
            setFreeStyleCropEnabled(true)
        }
        
        val ucropIntent = UCrop.of(source, destinationUri)
            .withOptions(options)
            .getIntent(this)

        ucropLauncher.launch(ucropIntent)
    }

    private fun showAddTextDialog() {
        val dialog = android.app.AlertDialog.Builder(this)
        val view = layoutInflater.inflate(R.layout.dialog_add_text, null)
        dialog.setView(view)
        val editText = view.findViewById<EditText>(R.id.edit_text_input)
        
        dialog.setPositiveButton("Adicionar") { d, _ ->
            val text = editText.text.toString()
            if (text.isNotEmpty()) {
                photoEditor.addText(text, Color.WHITE) 
            }
            d.dismiss()
        }
        dialog.setNegativeButton("Cancelar") { d, _ -> d.dismiss() }
        dialog.show()
    }

    // File: euia/ui/ImageEditorActivity.kt

// ... (imports e o resto da classe permanecem os mesmos) ...

    private fun saveImageAndFinish() {
        try {
            photoEditor.saveAsBitmap(object : OnSaveBitmap {
                override fun onBitmapReady(saveBitmap: Bitmap) {
                    Log.d(TAG, "onBitmapReady chamado. Rotação da View: ${photoEditorView.rotation} graus.")
                    
                    // <<< INÍCIO DA CORREÇÃO >>>
                    // Pega o bitmap renderizado e aplica a rotação da View nele manualmente.
                    val matrix = android.graphics.Matrix().apply {
                        postRotate(photoEditorView.rotation)
                    }

                    // Cria um novo bitmap com a rotação aplicada
                    val rotatedBitmap = Bitmap.createBitmap(
                        saveBitmap, 
                        0, 
                        0, 
                        saveBitmap.width, 
                        saveBitmap.height, 
                        matrix, 
                        true // 'true' para filtragem, resulta em melhor qualidade
                    )
                    
                    // Recicla o bitmap original não rotacionado, pois não precisamos mais dele
                    if (saveBitmap != rotatedBitmap) {
                        saveBitmap.recycle()
                    }
                    // <<< FIM DA CORREÇÃO >>>

                    val newFile = File(filesDir, "edited_${UUID.randomUUID()}.png")
                    try {
                        FileOutputStream(newFile).use { out ->
                            // Salva o bitmap JÁ ROTACIONADO
                            rotatedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                            Log.d(TAG, "Imagem rotacionada salva com sucesso em: ${newFile.absolutePath}")
                            
                            val resultIntent = Intent().apply {
                                putExtra(EXTRA_EDITED_IMAGE_PATH, newFile.absolutePath)
                            }
                            setResult(Activity.RESULT_OK, resultIntent)
                            finish()
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "Falha ao salvar o bitmap rotacionado no arquivo", e)
                        setResult(Activity.RESULT_CANCELED)
                        finish()
                    } finally {
                        // Garante que o bitmap final seja reciclado
                        if (!rotatedBitmap.isRecycled) {
                            rotatedBitmap.recycle()
                        }
                    }
                }
            })
        } catch (e: Exception) {
             Log.e(TAG, "Erro ao chamar saveAsBitmap", e)
             setResult(Activity.RESULT_CANCELED)
             finish()
        }
    }

}