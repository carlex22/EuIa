// euia/data/User.kt
package com.carlex.euia.data

data class User(
    val uid: String = "",
    val email: String = "",
    val name: String? = null,
    val isPremium: Boolean = false,
    val subscriptionEndDate: Long? = null // Timestamp em milissegundos
    // Outros dados do perfil que você queira armazenar
)