// File: euia/data/ChaveApiInfo.kt
package com.carlex.euia.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.PropertyName

/**
 * Representa a estrutura de um documento na coleção de chaves de API no Firestore.
 * Agora suporta múltiplos tipos de uso (audio, img, text) dentro do mesmo documento.
 */
data class ChaveApiInfo(
    // A chave de API em si, compartilhada por todos os tipos
    val apikey: String = "",

    // Campos para o tipo "audio"
    @get:PropertyName("em_uso_audio")
    @set:PropertyName("em_uso_audio")
    var emUsoAudio: Boolean = false,

    @get:PropertyName("bloqueada_em_audio")
    @set:PropertyName("bloqueada_em_audio")
    var bloqueadaEmAudio: Timestamp? = null,

    // Campos para o tipo "img" (imagem)
    @get:PropertyName("em_uso_img")
    @set:PropertyName("em_uso_img")
    var emUsoImg: Boolean = false,

    @get:PropertyName("bloqueada_em_img")
    @set:PropertyName("bloqueada_em_img")
    var bloqueadaEmImg: Timestamp? = null,

    // Campos para o tipo "text" (texto)
    @get:PropertyName("em_uso_text")
    @set:PropertyName("em_uso_text")
    var emUsoText: Boolean = false,

    @get:PropertyName("bloqueada_em_text")
    @set:PropertyName("bloqueada_em_text")
    var bloqueadaEmText: Timestamp? = null
)