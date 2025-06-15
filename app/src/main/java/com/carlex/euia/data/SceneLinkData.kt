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
    val similaridade: Int? = null,
    val aprovado: Boolean = false,
    val exibirProduto: Boolean? = null,

    // Campos específicos para geração de imagem
    val isGenerating: Boolean = false,
    val generationAttempt: Int = 0,
    var generationErrorMessage: String? = null, // Tornar var para permitir atualização pelo worker

    // --- INÍCIO DA MODIFICAÇÃO: Campos para Gerenciamento de Fila ---
    var queueRequestId: String? = null,     // Armazena o ID da requisição na API de fila
    var queueStatusMessage: String? = null, // Armazena a última mensagem de status da fila (ex: "Posição 3 na fila")
    // --- FIM DA MODIFICAÇÃO ---

    // Campos específicos para troca de roupa
    val isChangingClothes: Boolean = false,
    val clothesChangeAttempt: Int = 0,

    // Novos campos para vídeo
    val promptVideo: String? = null,       // Prompt usado para gerar o vídeo
    var pathThumb: String? = null,         // Caminho do thumbnail GERADO para o vídeo da cena
    var audioPathSnippet: String? = null,  // Caminho do trecho de áudio específico desta cena
    val isGeneratingVideo: Boolean = false // Estado de geração do VÍDEO para esta cena
)