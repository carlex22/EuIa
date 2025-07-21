// File: euia/viewmodel/helper/PixabayHelper.kt
package com.carlex.euia.viewmodel.helper

import android.content.Context
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.carlex.euia.api.PixabayApiClient
import com.carlex.euia.data.VideoPreferencesDataStoreManager
import com.carlex.euia.utils.WorkerTags
import com.carlex.euia.worker.ImageDownloadWorker
import com.carlex.euia.worker.VideoDownloadWorker
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

private const val TAG = "PixabayHelper"

/**
 * Define o tipo de um asset da Pixabay para diferenciação.
 */
enum class PixabayAssetType { VIDEO, IMAGE }

/**
 * Estrutura de dados unificada para representar tanto vídeos quanto imagens
 * retornados pela API da Pixabay, facilitando a exibição em uma única lista.
 *
 * @param type O tipo do asset (VIDEO ou IMAGE).
 * @param thumbnailUrl A URL para a imagem de pré-visualização.
 * @param downloadUrl A URL para o download do arquivo de mídia em alta qualidade.
 * @param tags As tags associadas à mídia.
 * @param id Um identificador único para o asset, usando a URL da thumbnail.
 */
data class PixabayAsset(
    val type: PixabayAssetType,
    val thumbnailUrl: String,
    val downloadUrl: String,
    val tags: String,
    val id: String = thumbnailUrl
)

/**
 * Classe auxiliar dedicada a gerenciar todas as interações com a API Pixabay.
 * Encapsula a lógica de busca, unificação de resultados e enfileiramento de downloads,
 * desacoplando essa responsabilidade do ViewModel.
 *
 * @param context O contexto da aplicação.
 * @param workManager A instância do WorkManager para enfileirar tarefas de download.
 * @param videoPreferencesDataStore O DataStore para obter o diretório do projeto atual.
 */
class PixabayHelper(
    private val context: Context,
    private val workManager: WorkManager,
    private val videoPreferencesDataStore: VideoPreferencesDataStoreManager
) {
    private val _isSearching = MutableStateFlow(false)
    val isSearching = _isSearching.asStateFlow()

    private val _searchResults = MutableStateFlow<List<PixabayAsset>>(emptyList())
    val searchResults = _searchResults.asStateFlow()

    /**
     * Busca vídeos e imagens na Pixabay com a query fornecida, unifica os resultados
     * de forma intercalada, embaralha e atualiza o StateFlow 'searchResults'.
     *
     * @param query O termo de busca a ser enviado para a API.
     */
    suspend fun search(query: String) {
        if (_isSearching.value || query.isBlank()) return
        _isSearching.value = true
        _searchResults.value = emptyList()

        coroutineScope {
            val videosDeferred = async { PixabayApiClient.searchVideos(query) }
            val imagesDeferred = async { PixabayApiClient.searchImages(query) }

            val videoResult = videosDeferred.await()
            val imageResult = imagesDeferred.await()

            val unifiedList = mutableListOf<PixabayAsset>()

            val videos = videoResult.getOrNull() ?: emptyList()
            val images = imageResult.getOrNull() ?: emptyList()

            // Intercala os resultados de vídeos e imagens para maior variedade
            val maxSize = maxOf(videos.size, images.size)
            for (i in 0 until maxSize) {
                if (i < images.size) {
                    val image = images[i]
                    unifiedList.add(PixabayAsset(
                        type = PixabayAssetType.IMAGE,
                        thumbnailUrl = image.previewURL,
                        downloadUrl = image.largeImageURL,
                        tags = image.tags
                    ))
                }
                if (i < videos.size) {
                    val video = videos[i]
                    unifiedList.add(PixabayAsset(
                        type = PixabayAssetType.VIDEO,
                        thumbnailUrl = video.videoFiles.small.thumbnail,
                        downloadUrl = video.videoFiles.small.url,
                        tags = video.tags
                    ))
                }
            }

            _searchResults.value = unifiedList
            Log.d(TAG, "Busca por '$query' retornou ${unifiedList.size} assets unificados.")
        }
        _isSearching.value = false
    }

    /**
     * Enfileira o worker apropriado (VideoDownloadWorker ou ImageDownloadWorker)
     * para baixar o asset selecionado e associá-lo à cena especificada.
     *
     * @param sceneId O ID da cena que receberá o asset baixado.
     * @param asset O objeto PixabayAsset selecionado pelo usuário.
     */
    suspend fun downloadAndAssignAssetToScene(sceneId: String, asset: PixabayAsset) {
        val projectDirName = videoPreferencesDataStore.videoProjectDir.first()

        val workRequest = if (asset.type == PixabayAssetType.VIDEO) {
            OneTimeWorkRequestBuilder<VideoDownloadWorker>()
                .setInputData(workDataOf(
                    VideoDownloadWorker.KEY_SCENE_ID to sceneId,
                    VideoDownloadWorker.KEY_VIDEO_URL to asset.downloadUrl,
                    VideoDownloadWorker.KEY_PROJECT_DIR_NAME to projectDirName
                ))
                .addTag(WorkerTags.VIDEO_PROCESSING)
                .addTag("video_download_${sceneId}")
                .build()
        } else {
            OneTimeWorkRequestBuilder<ImageDownloadWorker>()
                .setInputData(workDataOf(
                    ImageDownloadWorker.KEY_SCENE_ID to sceneId,
                    ImageDownloadWorker.KEY_IMAGE_URL to asset.downloadUrl,
                    ImageDownloadWorker.KEY_PROJECT_DIR_NAME to projectDirName
                ))
                .addTag(WorkerTags.IMAGE_PROCESSING_WORK)
                .addTag("image_download_${sceneId}")
                .build()
        }
        workManager.enqueue(workRequest)
        Log.d(TAG, "Worker de download enfileirado para cena $sceneId, asset: ${asset.downloadUrl}")
    }

    /**
     * Limpa os resultados da busca, ideal para ser chamado ao fechar o diálogo.
     */
    fun clearSearchResults() {
        _searchResults.value = emptyList()
    }
}