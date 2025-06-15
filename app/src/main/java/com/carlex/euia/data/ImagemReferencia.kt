// File: euia/data/ImagemReferencia.kt
package com.carlex.euia.data

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Represents a reference image or video thumbnail.
 * - For static images: `path` contains the image path, `pathVideo` is null.
 * - For videos: `path` contains the thumbnail path, `pathVideo` contains the video file path.
 */
@Serializable
data class ImagemReferencia(
    val path: String, // Path to the displayable image (thumbnail for videos, image itself for static images)
    val descricao: String,
    val isProcessing: Boolean = false,
    val pathVideo: String? = null, // Path to the video file, if this is a video reference
    val videoDurationSeconds: Long? = null,
    val containsPeople: Boolean = false
)

/**
 * Represents a reference image/video with additional fields for processing state.
 */
@Serializable
data class ImagemReferenciaComEstadoProcessamento(
    val id: String = UUID.randomUUID().toString(),
    val path: String? = null, // Path to the displayable image (thumbnail for videos)
    val descricao: String = "",
    val isProcessing: Boolean = false,
    val processingProgress: Float = 0f,
    val processingStatus: String = "",
    val error: String? = null,
    val pathVideo: String? = null, // Path to the video file
    val videoDurationSeconds: Long? = null,
    val containsPeople: Boolean = false
)

// --- Funções de Conversão ---

fun ImagemReferencia.toImagemReferenciaComEstadoProcessamento(): ImagemReferenciaComEstadoProcessamento {
    return ImagemReferenciaComEstadoProcessamento(
        id = UUID.randomUUID().toString(),
        path = this.path,
        descricao = this.descricao,
        isProcessing = false, // Reset processing state for new ViewModel instance
        processingProgress = 0f,
        processingStatus = "",
        error = null,
        pathVideo = this.pathVideo,
        videoDurationSeconds = this.videoDurationSeconds,
        containsPeople = this.containsPeople
    )
}

fun ImagemReferenciaComEstadoProcessamento.toImagemReferencia(): ImagemReferencia {
    return ImagemReferencia(
        path = this.path ?: "",
        descricao = this.descricao,
        isProcessing = this.isProcessing, // Retain processing state if needed, or reset
        pathVideo = this.pathVideo,
        videoDurationSeconds = this.videoDurationSeconds,
        containsPeople = this.containsPeople
    )
}