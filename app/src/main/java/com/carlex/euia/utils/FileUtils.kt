// File: euia/utils/FileUtils.kt
package com.carlex.euia.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Um Composable "remember" que verifica a existência de um arquivo de forma assíncrona
 * e retorna o resultado como um State<Boolean>.
 *
 * Ele utiliza `rememberFile` por baixo dos panos para garantir que todas as operações
 * de I/O ocorram em uma thread de background, evitando bloquear a UI e violar o StrictMode.
 * A verificação é refeita sempre que o `filePath` muda.
 *
 * O estado inicial é `false`, e ele será atualizado para `true` assim que a verificação
 * assíncrona for concluída com sucesso e o arquivo for encontrado.
 *
 * @param filePath O caminho absoluto do arquivo a ser verificado.
 * @return Um `State<Boolean>` que será `true` se o arquivo existir, `false` caso contrário ou enquanto a verificação estiver pendente.
 */
@Composable
fun rememberFileExistence(filePath: String): State<Boolean> {
    // Utiliza a função 'rememberFile' para obter o estado do arquivo.
    val file by rememberFile(filePath = filePath)
    // Deriva o estado de existência a partir do estado do arquivo.
    // 'produceState' garante que o valor booleano seja atualizado quando 'file' mudar.
    return produceState(initialValue = false, key1 = file) {
        value = file != null
    }
}

/**
 * Um Composable "remember" que cria um objeto `File` de forma assíncrona e segura,
 * retornando-o como um `State<File?>` apenas se o arquivo existir.
 *
 * Ele garante que a criação do objeto `File` e a verificação `exists()` ocorram em uma
 * thread de background (`Dispatchers.IO`), prevenindo violações do `StrictMode`.
 *
 * O estado inicial é `null` e será atualizado para o objeto `File` se o arquivo for
 * encontrado e válido. Se o caminho for inválido ou o arquivo não existir, o estado
 * permanecerá `null`.
 *
 * @param filePath O caminho absoluto do arquivo a ser lembrado.
 * @return Um `State<File?>` que conterá o objeto `File` se ele existir, ou `null` caso contrário.
 */
@Composable
fun rememberFile(filePath: String): State<File?> {
    // produceState é um builder de State que pode lançar uma corrotina para produzir seus valores.
    // O valor inicial é 'null'. O bloco de código é uma corrotina.
    // Ele será relançado automaticamente sempre que a 'key' (filePath) mudar.
    return produceState<File?>(initialValue = null, key1 = filePath) {
        // A verificação e criação do arquivo são feitas em uma thread de I/O.
        value = withContext(Dispatchers.IO) {
            if (filePath.isBlank()) {
                null
            } else {
                try {
                    val file = File(filePath)
                    if (file.exists()) {
                        file
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    // Em caso de qualquer erro (ex: SecurityException), retorna null.
                    null
                }
            }
        }
    }
}
