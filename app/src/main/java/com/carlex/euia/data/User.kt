/* euia/data/User.kt
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

    val creditos: Long = 0,

    // <<< CORREÇÃO PRINCIPAL AQUI >>>>
    // Adiciona as anotações para mapear o nome do campo no Firestore
    // com a propriedade da data class.
    @get:PropertyName("data_expira_credito")
    @set:PropertyName("data_expira_credito")
    var dataExpiraCredito: Timestamp? = null,

    val experiencia_usuario: Long = 0
)*/
// euia/data/User.kt
package com.carlex.euia.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.PropertyName

data class User(
    val uid: String = "",
    val email: String = "",
    val name: String? = null,

    // <<<< CORREÇÃO 1: Adicionadas anotações para mapear 'isPremium' corretamente. >>>>
    @get:PropertyName("isPremium")
    @set:PropertyName("isPremium")
    var isPremium: Boolean = false,

    val subscriptionEndDate: Long? = null,

    val creditos: Long = 0,

    // <<<< CORREÇÃO 2: Adicionadas anotações para mapear 'data_expira_credito' corretamente. >>>>
    @get:PropertyName("data_expira_credito")
    @set:PropertyName("data_expira_credito")
    var dataExpiraCredito: Timestamp? = null,

    val experiencia_usuario: Long = 0
)