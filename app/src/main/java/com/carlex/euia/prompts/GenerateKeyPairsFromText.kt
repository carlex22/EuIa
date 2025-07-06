package com.carlex.euia.prompts

/**
 * Cria um prompt para o Gemini instruindo-o a extrair pares chave-valor de um texto,
 * retornando um ARRAY de objetos, onde cada objeto tem "chave" e "valor".
 *
 * @property textContent O texto a ser analisado pelo Gemini.
 */
class GenerateKeyPairsFromText(private val textContent: String) {

    /**
     * O prompt formatado para ser enviado ao modelo Gemini.
     */
    val prompt: String = """
        Analise o seguinte texto de descrição de um produto ou serviço.
        Sua tarefa é extrair informações relevantes e estruturá-las como um ARRAY JSON de objetos.
        Cada objeto no array deve ter exatamente duas chaves: "chave" e "valor".
        - O valor da "chave" deve ser uma string descritiva em formato camelCase (ex: "nomeProduto", "corPrincipal").
        - O valor do "valor" deve ser uma string contendo a informação extraída do texto.

        **Instruções Adicionais:**
        1.  Se uma informação para uma categoria sugerida (veja abaixo) não for encontrada, NÃO crie um objeto para ela no array.
        2.  Se múltiplas informações distintas se aplicarem a uma mesma categoria conceitual (ex: múltiplas características ou cores listadas individualmente), você pode criar múltiplos objetos com a mesma "chave" conceitual (ex: `{"chave": "caracteristica", "valor": "Resistente à água"}, {"chave": "caracteristica", "valor": "GPS integrado"}`) OU, se apropriado, concatenar os valores em uma única string separada por vírgula para um único objeto (ex: `{"chave": "coresDisponiveis", "valor": "azul, preto, verde"}`). Use o bom senso: se são itens distintos de uma lista, múltiplos objetos podem ser melhores; se são variações da mesma característica, concatenar pode ser mais limpo.
        3.  Priorize as categorias de chaves sugeridas abaixo. Você pode adicionar outras chaves se encontrar informações relevantes não cobertas, mantendo o formato `{"chave": "suaNovaChave", "valor": "..."}`.

     
        **Importante:**
        * Se o texto for muito curto, genérico, ou não contiver informações que se encaixem razoavelmente, retorne um ARRAY JSON VAZIO: `[]`.
        * Não invente informações. Extraia apenas o que está explicitamente ou fortemente implícito no texto.
        * Seja conciso nos valores, mas capture a essência da informação. Aspas dentro dos valores devem ser escapadas corretamente (ex: "Tela de 10\" polegadas" deve se tornar "Tela de 10\\\" polegadas"). O Gemini geralmente lida com isso, mas esteja ciente.

        **Texto para análise:**
        "${textContent.replace("\"", "\\\"")}"

        **Array JSON resultante 
        A sua resposta final, deve conter somente uma lista JSON contendo:
        [{"chave": "string", "valor": "string"}]

    """.trimIndent()
}