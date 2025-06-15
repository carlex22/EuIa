package com.carlex.euia.prompts

/**
 * Cria um prompt para a Gemini extrair informações de pré-contexto de uma URL genérica,
 * e sugerir conteúdo para um vídeo, levando em consideração os dados da Persona do usuário.
 * NÃO pede URLs de imagem nesta fase.
 *
 * @property contentUrl A URL do conteúdo a ser analisado.
 * @property currentUserNameCompany Nome/Empresa atual do usuário (pode estar vazio).
 * @property currentUserProfessionSegment Profissão/Segmento atual do usuário (pode estar vazio).
 * @property currentUserAddress Endereço/Localização atual do usuário (pode estar vazio).
 * @property currentUserLanguageTone Tom de linguagem atual do usuário (pode estar vazio).
 * @property currentUserTargetAudience Público-alvo atual do usuário (pode estar vazio).
 */
class ExtractInfoFromUrlPrompt(
    private val contentUrl: String,
    private val currentUserNameCompany: String,
    private val currentUserProfessionSegment: String,
    private val currentUserAddress: String,
    private val currentUserLanguageTone: String,
    private val currentUserTargetAudience: String
) {

    private val userInfoHint: String = buildString {
        appendLine("Dados cliente solicitante:")
        if (currentUserNameCompany.isNotBlank()) appendLine("- Nome/Empresa: $currentUserNameCompany")
        if (currentUserProfessionSegment.isNotBlank()) appendLine("- Profissão/Ramo Atuação: $currentUserProfessionSegment")
        if (currentUserAddress.isNotBlank()) appendLine("- Endereço: $currentUserAddress")
    }.trim()

    val prompt: String = """
    Voce e um roteirista criador de videos para internet. Foi conttatado para criar um video
    
    $userInfoHint
    
    Seu objetivo e analisar uma url ou frase para um elaborar sugestoes para pre-contexto de um video sobre que fale ou aborde o tema/produto/objeto informado
    Analise o conteúdo da seguinte URL para obter um contexto para a criação do vídeo do seu cliente:
   
    URL: "$contentUrl"

    Sua tarefa é extrair e gerar as seguintes informações, retornando-as como um ÚNICO objeto JSON.
    A resposta deve ser exclusivamente o objeto JSON, sem nenhum texto adicional ou marcadores como ```json.

    Campos a serem preenchidos no JSON:
    1.  "suggested_title": String
        * Com base no conteúdo da URL sugira um título OTIMIZADO e ATRAENTE.
        * Deve ser relevante e remover informações desnecessárias (códigos de referência, etc.), se aplicável.
        
    2.  "main_summary": String
        * Gere um resumo principal conciso do conteúdo da URL.
        
    
    3.  "suggested_language_tone": String
        * Com base no conteúdo da URL sugira o TOM DE LINGUAGEM mais apropriado para o narrador gravar o audio do video, 3 tokens no maximo

    4.  "suggested_target_audience": String
        * Com base no conteúdo da URL sugira o PÚBLICO-ALVO ideal para que o vídeo deve tentar alcancar e se comunicar, 6 tokens no maximo
        
    5.  "video_objective_introduction": String
        * Considerando o conteúdo e o perfil do solicitante, sugira um objetivo para a INTRODUÇÃO do vídeo, pode ser aoeesentando ums duvida/problema/desafio algo ligado com o que o contexto iea resolver, 18 tokens no maximo

    6.  "video_objective_content": String
        * Sugira um objetivo e o desenvilvimento do CONTEÚDO PRINCIPAL do vídeo e o resumo completo, deve ser alinhado com os interesses do público-alvo e o tom de linguagem.

    7.  "video_objective_outcome": String
        * Sugira um objetivo o desfecho do video. pode ser uma chamada para acao/curtida/compra/compartilhar/comentar algo relacinado para fazer ousuario interagur com o que foi apresentado.  25 tokens no maximo

        
    formato de resposta esperada:
    {
      "suggested_title": "string",
      "main_summary": "string",
      "video_objective_introduction": "string",
      "video_objective_content": "string",
      "video_objective_outcome": "string",
      "suggested_language_tone": "string",
      "suggested_target_audience": "strin"
    }
    """.trimIndent()
}