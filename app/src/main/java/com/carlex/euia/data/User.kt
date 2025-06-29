// euia/data/User.kt
package com.carlex.euia.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.PropertyName

data class User(
    val uid: String = "",
    val email: String = "",
    val name: String? = null,

    @get:PropertyName("isPremium")
    @set:PropertyName("isPremium")
    var isPremium: Boolean = false,

    val subscriptionEndDate: Long? = null,

    // <<< CORREÇÃO 1: Alterado o tipo de Long para String para armazenar valor CRIPTOGRAFADO (Base64) >>>
    // Adicionada anotação PropertyName para mapear o nome do campo no Firestore.
    @get:PropertyName("creditos_criptografados")
    @set:PropertyName("creditos_criptografados")
    var creditos: String? = null, // Será armazenado como String (Base64)

    // <<< CORREÇÃO 2: Adicionada anotação para mapear 'data_expira_credito' corretamente (já existia) >>>
    @get:PropertyName("data_expira_credito")
    @set:PropertyName("data_expira_credito")
    var dataExpiraCredito: Timestamp? = null, // Mantido como Timestamp por simplicidade (não criptografado)

    // <<< NOVA ADIÇÃO: Campo para armazenar a chave de validação no Firebase >>>
    // Este campo será usado para verificar a integridade da 'chave mestra' local.
    @get:PropertyName("chave_validacao_firebase")
    @set:PropertyName("chave_validacao_firebase")
    var chaveValidacaoFirebase: String? = null, // Armazena a chave de validação criptografada. É o resultado da criptografia de (UID + chave mestra), usado para verificar a integridade da chave local.

    val experiencia_usuario: Long = 0 // Mantido como Long
)