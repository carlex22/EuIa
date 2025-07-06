package com.carlex.euia.prompts

/**
 * Cria um prompt para a Gemini refinar um título de produto/serviço,
 * removendo códigos de referência e informações desnecessárias.
 *
 * @property originalTitle O título original extraído.
 */
class RefineTitlePrompt(private val originalTitle: String) {

    /**
     * O prompt formatado para ser enviado ao modelo Gemini.
     */
    val prompt: String = """
        Analise o seguinte título de produto ou serviço:
        "${originalTitle.replace("\"", "\\\"")}"

        Sua tarefa é refinar este título para que seja mais limpo e focado no nome principal do produto/serviço.
        Por favor, siga estas instruções:
        1.  Remova quaisquer códigos de referência, números de modelo, SKUs, ou identificadores numéricos/alfanuméricos que pareçam ser internos ou não essenciais para um consumidor entender o que é o produto. Por exemplo, "40800010" em "Jaqueta Alpelo Bicolor Padding Com Pele Sintética 40800010" deve ser removido.
        2.  Remova termos genéricos excessivos ou jargões que não agregam valor ao nome principal.
        3.  Mantenha a marca (se identificável e relevante, como "Alpelo") e as características descritivas principais do produto (ex: "Jaqueta Bicolor Padding Com Pele Sintética").
        4.  O título refinado deve ser conciso e soar natural.
        5.  Não adicione informações que não estejam implícitas no título original.
        6.  Se o título original já parecer limpo e sem referências óbvias, retorne-o como está ou com pequenas melhorias de fluidez.
        7.  Sua resposta deve ser APENAS a string do título refinado, sem nenhum texto adicional, aspas externas desnecessárias, ou marcadores de bloco de código.

        Título refinado:
    """.trimIndent()
}