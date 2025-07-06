// File: euia/prompts/ExtractDetailedPageContentPrompt.kt
package com.carlex.euia.prompts

/**
 * Cria um prompt para a Gemini analisar uma URL (página web ou vídeo do YouTube)
 * e extrair informações detalhadas como pares chave-valor.
 *
 * Esta classe detecta se a URL é do YouTube e gera um prompt especializado para
 * análise de vídeo ou um prompt para análise de texto de página web.
 *
 * @property contentUrl A URL do conteúdo a ser analisado.
 * @property providedTitle Título já conhecido/sugerido (para dar contexto à Gemini).
 * @property initialSummary Resumo inicial já conhecido (para dar contexto à Gemini).
 */
class ExtractDetailedPageContentAsKeyValuesPrompt(
    private val contentUrl: String,
    private val providedTitle: String,
    private val initialSummary: String
) {

    /**
     * Um booleano público que indica se a URL fornecida é do YouTube.
     * O chamador (UrlImportWorker) usará isso para decidir qual parâmetro da API preencher.
     */
    val isYoutubeUrl: Boolean

    /**
     * O prompt final e formatado, pronto para ser enviado ao modelo Gemini.
     */
    val prompt: String

    init {
        // Detecta se a URL é do YouTube no momento da inicialização
        isYoutubeUrl = contentUrl.contains("youtube.com", ignoreCase = true) || contentUrl.contains("youtu.be", ignoreCase = true)

        // Gera o prompt apropriado com base no tipo de URL
        prompt = if (isYoutubeUrl) {
            buildYoutubePrompt()
        } else {
            buildWebpagePrompt()
        }
    }

    private fun buildWebpagePrompt(): String {
        val contextHint = buildString {
            if (providedTitle.isNotBlank() || initialSummary.isNotBlank()) {
                appendLine("Informações de contexto para ajudar na sua análise (o conteúdo principal está na URL abaixo):")
                if (providedTitle.isNotBlank()) appendLine("- Título principal identificado anteriormente: \"$providedTitle\"")
                if (initialSummary.isNotBlank()) appendLine("- Resumo inicial gerado: \"$initialSummary\"")
            }
        }.trim()

        return """
        Você é um pesquisador e redator de conteúdo especialista. Sua tarefa é analisar o conteúdo textual da URL para extrair os pontos principais.

        URL para análise textual: "$contentUrl"

        $contextHint
        
        **Sua Missão:**
        1. Extraia o máximo de informações relevantes, fatos, dados e pontos chave do corpo principal do conteúdo textual da página.
        2. Estruture essas informações como um ARRAY JSON de objetos. Cada objeto deve ter duas chaves: "chave" e "valor".
        3. A "chave" deve ser descritiva em formato camelCase (ex: "pontoChave1", "argumentoCentral", "nomeProduto").
        4. O "valor" deve ser a informação textual correspondente. Combine o título e a explicação de cada tópico no campo 'valor'.
        5. Foque no conteúdo principal, ignorando menus, rodapés e anúncios.
        
        A resposta deve ser exclusivamente o objeto JSON, sem nenhum texto adicional ou marcadores.

        Exemplo de resposta:
        {
          "detailed_key_values": [
            {"chave": "nomeProdutoAnunciado", "valor": "Fone de Ouvido Sem Fio Endurance Pro. Oferece até 40 horas de bateria e cancelamento de ruído ativo."},
            {"chave": "resistenciaAgua", "valor": "Classificação IPX7, o que o torna resistente à imersão em água."}
          ]
        }
        """.trimIndent()
    }

    private fun buildYoutubePrompt(): String {
        val contextHint = buildString {
            if (providedTitle.isNotBlank() || initialSummary.isNotBlank()) {
                appendLine("Informações de contexto sobre o vídeo:")
                if (providedTitle.isNotBlank()) appendLine("- Título sugerido: \"$providedTitle\"")
                if (initialSummary.isNotBlank()) appendLine("- Resumo inicial: \"$initialSummary\"")
            }
        }.trim()

        return """
        Você é um analista de conteúdo multimídia e roteirista. Sua tarefa é analisar o conteúdo do VÍDEO na URL fornecida para extrair os pontos-chave, conceitos, dicas e informações mais importantes para a criação de um novo roteiro.

        **Sua Missão:**
        1.  **Analise o Vídeo:** "Assista" ao vídeo na URL para entender seu tema, estrutura e mensagem principal.
        2.  **Extraia os Insights:**
            *   **Priorize o conteúdo FALADO (transcrição):** A informação mais valiosa está no que é dito. Extraia os principais argumentos, dicas, passos ou conclusões.
            *   **Observe os elementos VISUAIS e TEXTUAIS:** Se o vídeo mostra gráficos, listas na tela, palavras-chave ou demonstrações visuais importantes, extraia essas informações.
            *   **Sintetize e Estruture:** Não transcreva o vídeo inteiro. Extraia os "insights" e fatos principais.
        3.  **Formato da Resposta:** Retorne um ÚNICO objeto JSON com um array de pares chave-valor.
            *   O JSON deve ter a chave "detailed_key_values".
            *   O valor deve ser um ARRAY JSON de objetos.
            *   Cada objeto deve ter duas chaves: "chave" (uma descrição curta em camelCase, ex: "dicaPrincipal", "passo1", "argumentoCentral") e "valor" (a informação extraída do vídeo).

        $contextHint
        
        A resposta deve ser exclusivamente o objeto JSON, sem nenhum texto adicional.

        Exemplo de resposta para um vídeo de receita:
        {
          "detailed_key_values": [
            {"chave": "ingredienteSecreto", "valor": "Uma pitada de páprica defumada para dar um sabor especial."},
            {"chave": "passo3", "valor": "Deixar a massa descansar por pelo menos 30 minutos na geladeira. Isso é crucial para a textura."},
            {"chave": "dicaDeOuro", "valor": "Não abra o forno nos primeiros 20 minutos para o bolo não murchar."}
          ]
        }
        """.trimIndent()
    }
}