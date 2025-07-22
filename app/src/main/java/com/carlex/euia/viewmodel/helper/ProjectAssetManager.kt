// File: euia/viewmodel/helper/ProjectAssetManager.kt
package com.carlex.euia.viewmodel.helper

import android.content.Context
import android.util.Log
import com.carlex.euia.data.ProjectAsset
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import com.carlex.euia.utils.ProjectPersistenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "ProjectAssetManager"

/**
 * Gerencia a descoberta e catalogação de todos os assets de mídia
 * (imagens e vídeos) disponíveis dentro de um projeto específico.
 */
class ProjectAssetManager(
    private val context: Context,
    private val videoPreferencesDataStoreManager: VideoPreferencesDataStoreManager
) {

    /**
     * Escaneia TODOS os diretórios de mídia do projeto e retorna uma lista unificada.
     *
     * @return Uma lista de [ProjectAsset] ordenada pelo nome de exibição.
     */
    suspend fun loadAllProjectAssets(): List<ProjectAsset> = withContext(Dispatchers.IO) {
        try {
            val projectDirName = videoPreferencesDataStoreManager.videoProjectDir.first()
            if (projectDirName.isBlank()) return@withContext emptyList()

            val projectDir = ProjectPersistenceManager.getProjectDirectory(context, projectDirName)
            val assetList = mutableListOf<ProjectAsset>()
            val mediaDirs = listOf(
                "ref_images", "pixabay_images", "pixabay_videos",
                "downloaded_videos", "gemini_generated_images", "imagens_ml_originais"
            )
            
            val videoThumbDir = File(projectDir, "thumbs")

            for (subDirName in mediaDirs) {
                val dir = File(projectDir, subDirName)
                if (!dir.exists() || !dir.isDirectory) continue

                dir.listFiles()?.forEach { file ->
                    // Regra: Ignora arquivos que são thumbnails geradas
                    if (file.isFile) {
                        val extension = file.extension.lowercase()
                        val isVideo = extension == "mp4" || extension == "webm" || extension == "ts"

                        var finalThumbnailPath = file.absolutePath

                        if (isVideo) {
                            // Para vídeos, busca a thumbnail correspondente
                            val expectedThumbName = "${file.nameWithoutExtension}.webp"
                            val thumbFile = File(videoThumbDir, expectedThumbName)
                            if (thumbFile.exists()) {
                                finalThumbnailPath = thumbFile.absolutePath
                            } else {
                                Log.w(TAG, "Thumbnail '$expectedThumbName' não encontrada para o vídeo '${file.name}'. O vídeo não será listado.")
                                return@forEach // Pula para o próximo arquivo
                            }
                        }
                        
                        assetList.add(
                            ProjectAsset(
                                displayName = file.name,
                                finalAssetPath = file.absolutePath,
                                thumbnailPath = finalThumbnailPath,
                                isVideo = isVideo
                            )
                        )
                    }
                }
            }

            Log.d(TAG, "Carregados ${assetList.size} assets do projeto '$projectDirName'.")
            return@withContext assetList.sortedBy { it.displayName }

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao carregar todos os assets do projeto.", e)
            return@withContext emptyList()
        }
    }

    /**
     * Escaneia APENAS o diretório de imagens de referência (`ref_images`) e retorna uma
     * lista de assets de imagem.
     *
     * @return Uma lista de [ProjectAsset] contendo apenas as imagens de referência do usuário.
     */
    suspend fun loadReferenceImageAssets(): List<ProjectAsset> = withContext(Dispatchers.IO) {
        try {
            val projectDirName = videoPreferencesDataStoreManager.videoProjectDir.first()
            if (projectDirName.isBlank()) return@withContext emptyList()

            val projectDir = ProjectPersistenceManager.getProjectDirectory(context, projectDirName)
            val assetList = mutableListOf<ProjectAsset>()
            
            val refImagesDir = File(projectDir, "ref_images")

            if (refImagesDir.exists() && refImagesDir.isDirectory) {
                refImagesDir.listFiles()?.forEach { file ->
                    // Garante que é um arquivo, não é um vídeo e não é uma thumbnail gerada
                    val isImage = !file.isDirectory && !file.name.startsWith("thumb_", true)
                    val isNotVideo = !listOf("mp4", "webm").contains(file.extension.lowercase())
                    
                    if (isImage && isNotVideo) {
                        assetList.add(
                            ProjectAsset(
                                displayName = file.name,
                                finalAssetPath = file.absolutePath,
                                thumbnailPath = file.absolutePath, // Para imagens, a thumb é ela mesma
                                isVideo = false
                            )
                        )
                    }
                }
            }
            
            Log.d(TAG, "Carregados ${assetList.size} assets de imagem de referência.")
            return@withContext assetList.sortedBy { it.displayName }

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao carregar assets de imagem de referência.", e)
            return@withContext emptyList()
        }
    }
}