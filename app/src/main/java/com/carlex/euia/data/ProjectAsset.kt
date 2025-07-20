// File: euia/data/ProjectAsset.kt
package com.carlex.euia.data

/**
 * Representa um asset (imagem ou vídeo) disponível dentro de um projeto.
 *
 * @param displayName O nome do arquivo para exibição.
 * @param finalAssetPath O caminho absoluto para o arquivo principal (o vídeo .mp4 ou a imagem .webp/.jpg).
 * @param thumbnailPath O caminho absoluto para a imagem que será mostrada na UI (a própria imagem ou a thumb do vídeo).
 * @param isVideo True se o asset for um vídeo, false caso contrário.
 */
data class ProjectAsset(
    val displayName: String,
    val finalAssetPath: String, 
    val thumbnailPath: String,
    val isVideo: Boolean
)