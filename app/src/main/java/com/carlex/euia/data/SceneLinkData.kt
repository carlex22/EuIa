// File: euia/data/SceneLinkData.kt
package com.carlex.euia.data

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class SceneLinkData(
    val id: String = UUID.randomUUID().toString(),
    val cena: String? = null,
    val tempoInicio: Double? = null,
    val tempoFim: Double? = null,
    val imagemReferenciaPath: String, // Caminho da imagem de referência original (thumbnail ou imagem estática)
    val descricaoReferencia: String,
    val promptGeracao: String? = null, // Prompt para gerar imagem ESTÁTICA
    var imagemGeradaPath: String? = null, // Caminho da IMAGEM ESTÁTICA gerada OU do VÍDEO gerado
    var videoPreviewPath: String? = null, // Caminho da IMAGEM ESTÁTICA gerada OU do VÍDEO gerado
    val previewQueuePosition: Int? = -1,



    val similaridade: Int? = null,
    val aprovado: Boolean = false,
    val exibirProduto: Boolean? = null,
    
    // Campos específicos para geração de imagem
    val isGenerating: Boolean = false,
    val generationAttempt: Int = 0,
    var generationErrorMessage: String? = null, // Tornar var para permitir atualização pelo worker

    // Campos específicos para troca de roupa
    val isChangingClothes: Boolean = false,
    val clothesChangeAttempt: Int = 0,

    // Novos campos para vídeo
    val promptVideo: String? = null,       // Prompt usado para gerar o vídeo
    var pathThumb: String? = null,         // Caminho do thumbnail GERADO para o vídeo da cena
    var audioPathSnippet: String? = null,  // Caminho do trecho de áudio específico desta cena
    val isGeneratingVideo: Boolean = false, // Estado de geração do VÍDEO para esta cena
    val isClone: Boolean = false // Indica se esta cena é uma duplicata inserida para ajuste de tempo
)

 