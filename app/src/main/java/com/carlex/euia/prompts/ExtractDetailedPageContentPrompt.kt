package com.carlex.euia.prompts

/**
 * Cria um prompt para a Gemini analisar profundamente uma URL e extrair
 * informações detalhadas do conteúdo principal como pares chave-valor.
 * NÃO pede URLs de imagem.
 *
 * @param pageUrl A URL da página a ser analisada.
 * @param providedTitle Título já conhecido/sugerido da página (para dar contexto à Gemini).
 * @param initialSummary Resumo inicial já conhecido da página (para dar contexto à Gemini).
 */
class ExtractDetailedPageContentAsKeyValuesPrompt(
    private val pageUrl: String,
    private val providedTitle: String,
    private val initialSummary: String
) {
    private val contextHint: String = buildString {
        appendLine("Informações de contexto para ajudar na sua análise (o conteúdo principal está na URL abaixo):")
        if (providedTitle.isNotBlank()) appendLine("- Título principal identificado anteriormente para esta página: \"$providedTitle\"")
        if (initialSummary.isNotBlank()) appendLine("- Resumo inicial gerado para esta página: \"$initialSummary\"")
    }.trim()

    val prompt: String = """
    Você é um pesquisador e redator de conteúdo especialista. Sua tarefa é expandir um conceito ou dica curta, fornecendo uma explicação mais profunda e prática.
    
    Analise PROFUNDAMENTE o conteúdo textual principal da seguinte URL para extrair informações detalhadas e relevantes, essas informacies serao usadas para recriar o contexto e descrever esse cinteudo em um blog ou video:
    URL: "$pageUrl"

    $contextHint
    
    **Sua Missão:**
    1.  **Pesquise e Aprofunde:** Usando a URL de contexto e seu conhecimento geral da internet, explique o **'porquê'** por trás deste conceito. Por que ele é importante? Qual a psicologia ou estratégia envolvida?
    2.  **Dê um Exemplo Prático:** Ilustre o conceito com um exemplo claro e conciso de como ele poderia ser aplicado na prática, especialmente no contexto de criação de vídeos para redes sociais.
    3.  **Formato da Resposta:** Sua resposta deve ser um único parágrafo de texto fluido e bem escrito, contendo de 4 a 6 frases. Não adicione títulos, marcadores ou qualquer formatação. A resposta deve ser o texto que substituirá diretamente o conceito original.

    Sua tarefa é retornar um ÚNICO objeto JSON com o seguinte campo:
    1.  "detailed_key_values": Array<Object>
        * Extraia o máximo de informações relevantes, fatos, dados e pontos chave do corpo principal do conteúdo textual da página.
        * Estruture essas informações como um ARRAY JSON de objetos. Cada objeto deve ter duas chaves: "chave" e "valor".
        * A "chave" deve ser uma string descritiva e concisa em formato camelCase (ex: "autorPrincipal", "dataPublicacao", "pontoChave1", "argumentoCentral", "nomeProduto", "caracteristicaDistintiva", "especificacaoImportante"). Crie chaves que representem bem a natureza da informação.
        * O "valor" deve ser a informação textual correspondente como uma string. Tente manter os valores factuais e não excessivamente longos, a menos que crucial para a informação.
        * Se a página for sobre um produto, foque em extrair características textuais, especificações, materiais descritos, etc.
        * Se for um artigo, notícia ou post de blog, extraia autor, data (se disponível no texto), seções principais, argumentos, conclusões, etc.
        * O objetivo é capturar a essência e os detalhes significativos do conteúdo textual da página em um formato estruturado para ser usado posteriormente na criação de uma narrativa de vídeo.
        * EVITE extrair informações de navegação do site (menus, rodapés, anúncios, links "leia também") ou metadados que não sejam parte do conteúdo principal. Foque no texto que um leitor consumiria.
        * Se nenhuma informação textual estruturável relevante for encontrada no corpo principal, retorne um array vazio: [].

        Para cada tópico ou dica que você identificar, extraia não apenas o título, mas também o parágrafo explicativo associado a ele. Combine o título e a explicação no campo 'valor''

    A resposta deve ser exclusivamente o objeto JSON, sem nenhum texto adicional ou marcadores como ```json.

    Exemplo de formato de resposta esperada (para um artigo de notícias):
    {
      "detailed_key_values": [
        {"chave": "eventoPrincipal", "valor": "Lançamento do novo satélite de observação terrestre"},
        {"chave": "dataEvento", "valor": "15 de maio de 2025"},
        {"chave": "localLancamento", "valor": "Cabo Canaveral, Flórida"},
        {"chave": "objetivoMissao", "valor": "Monitorar mudanças climáticas e desmatamento com maior precisão."},
        {"chave": "tecnologiaDestaque", "valor": "Sensores hiperespectrais de nova geração."}
      ]
    }
    
    Exemplo de formato de resposta esperada (para uma página de produto):
    {
      "detailed_key_values": [
        {"chave": "nomeProdutoAnunciado", "valor": "Fone de Ouvido Sem Fio Endurance Pro"},
        {"chave": "marcaFabricante", "valor": "AudioMax"},
        {"chave": "autonomiaBateria", "valor": "Até 40 horas com o estojo de carregamento"},
        {"chave": "cancelamentoRuido", "valor": "Ativo Híbrido com modo Transparência"},
        {"chave": "resistenciaAgua", "valor": "IPX7 (resistente à imersão)"},
        {"chave": "coresDisponiveisTexto", "valor": "Preto Meia-Noite, Branco Neve, Azul Oceano"}
      ]
    }
    """.trimIndent()
}