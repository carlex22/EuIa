// File: euia/prompts/ExtractInfoFromUrlPrompt.kt
package com.carlex.euia.prompts

/**
 * Cria um prompt para a Gemini extrair informações de pré-contexto de uma URL,
 * e sugerir conteúdo para um vídeo, adaptando as sugestões para um monólogo ou um diálogo.
 *
 * @property contentUrl A URL do conteúdo a ser analisado.
 * @property currentUserNameCompany Nome/Empresa atual do usuário.
 * @property currentUserProfessionSegment Profissão/Segmento atual do usuário.
 * @property currentUserAddress Endereço/Localização atual do usuário.
 * @property currentUserLanguageTone Tom de linguagem atual do usuário.
 * @property currentUserTargetAudience Público-alvo atual do usuário.
 * @property isChat Boolean que indica se a narrativa será um diálogo.
 */
class ExtractInfoFromUrlPrompt(
    private val contentUrl: String,
    private val currentUserNameCompany: String,
    private val currentUserProfessionSegment: String,
    private val currentUserAddress: String,
    private val currentUserLanguageTone: String,
    private val currentUserTargetAudience: String,
    private val isChat: Boolean // Novo parâmetro
) {

    private val userInfoHint: String = buildString {
        appendLine("Dados cliente solicitante:")
        if (currentUserNameCompany.isNotBlank()) appendLine("- Nome/Empresa: $currentUserNameCompany")
        if (currentUserProfessionSegment.isNotBlank()) appendLine("- Profissão/Ramo Atuação: $currentUserProfessionSegment")
        if (currentUserAddress.isNotBlank()) appendLine("- Endereço: $currentUserAddress")
    }.trim()

    val prompt: String

    init {
        // --- INÍCIO DA MODIFICAÇÃO: Lógica para alternar as instruções ---
        val objectiveInstructions = if (isChat) {
            """
            *   **Adapte para um DIÁLOGO.** Pense em como o conteúdo pode ser apresentado em uma conversa entre duas ou mais pessoas.

            6.  "video_objective_introduction": String
                *   Sugira um **gancho de conversa** ou uma pergunta inicial que um personagem poderia fazer para iniciar o vídeo. Ex: "Você viu essa nova tecnologia? Parece incrível!", "Será que isso realmente funciona como prometem?".

            7.  "video_objective_content": String
                *   Descreva como o **diálogo se desenrolaria**. Quem explicaria o quê? Que tipo de perguntas ou contrapontos o outro personagem faria para extrair as informações do conteúdo da URL?

            8.  "video_objective_outcome": String
                *   Sugira como os **personagens concluiriam a conversa**, fazendo uma chamada para ação conjunta ou deixando uma pergunta para o público.
            """
        } else {
            """
            *   **Adapte para um NARRADOR ÚNICO.** Pense em como o conteúdo pode ser apresentado por uma única voz, como em um documentário ou vídeo explicativo.

            6.  "video_objective_introduction": String
                *   Considerando o conteúdo e o perfil do solicitante, sugira um objetivo para a INTRODUÇÃO do vídeo, apresentando uma dúvida, problema ou desafio que o conteúdo irá resolver. Máximo de 18 tokens.

            7.  "video_objective_content": String
                *   Sugira um objetivo para o desenvolvimento do CONTEÚDO PRINCIPAL do vídeo e um resumo completo, alinhado com os interesses do público-alvo e o tom de linguagem.

            8.  "video_objective_outcome": String
                *   Sugira um objetivo para o desfecho do vídeo. Pode ser uma chamada para ação (curtir, comprar, compartilhar, comentar) para engajar o usuário. Máximo de 25 tokens.
            """
        }
        // --- FIM DA MODIFICAÇÃO ---

        prompt = """
        Você é um roteirista criador de vídeos para internet. Foi contratado para criar um vídeo.
        
        $userInfoHint
        
        Seu objetivo é analisar uma url ou frase para elaborar sugestões para o pré-contexto de um vídeo que fale ou aborde o tema/produto/objeto informado.
        Analise o conteúdo da seguinte URL para obter um contexto para a criação do vídeo do seu cliente:
       
        URL: "$contentUrl"
        
        Vamos lembra como voce mestre genial se prepara lendo seu RACIOCÍNIO anotado em seu diario secreto pessoal.
A sua receita basica secreta do sucesso, seu tempero e forma mais guardada que a da coca-cola

== ETAPA 1: FILOSOFIA → NARRATIVA ==
Analise a essência conceitual do projeto:
- Qual é o insight central que move esta narrativa?
- Que perspectiva única está sendo apresentada?
- Como transformar conceitos abstratos em história tangível?
- Qual é a "grande ideia" por trás do conteúdo?

== ETAPA 2: TEORIA → PRÁTICA ==
Conecte conceitos com aplicação real:
- Como os conceitos se manifestam na vida cotidiana?
- Que exemplos concretos ilustram a teoria?
- Quais são as implicações práticas para o público?
- Como tornar o abstrato em acionável?

== ETAPA 3: CONVERSA → ESTRUTURA ==
Transforme diálogo em roteiro estruturado:
- Qual é o gancho emocional mais forte?
- Como criar progressão lógica e envolvente?
- Que pontos de conexão ressoam com o público?
- Como equilibrar informação e entretenimento?

== ETAPA 4: APLICAÇÃO → IMPACTO ==
Defina resultado desejado:
- Que mudança de perspectiva esperamos?
- Qual ação específica o público deve tomar?
- Como medir o sucesso da narrativa?
- Qual é o legado da mensagem?
        

        Sua tarefa é extrair e gerar as seguintes informações, retornando-as como um ÚNICO objeto JSON.
        A resposta deve ser exclusivamente o objeto JSON, sem nenhum texto adicional ou marcadores como ```json.

        Campos a serem preenchidos no JSON:
        1.  "suggested_title": String
            *   Com base no conteúdo da URL, sugira um título OTIMIZADO e ATRAENTE.
            *   Deve ser relevante e remover informações desnecessárias (códigos, etc.), se aplicável.
            
        2.  "main_summary": String
            *   Gere um resumo principal conciso do conteúdo da URL.
            
        3.  "suggested_language_tone": String
            *   Sugira o TOM DE LINGUAGEM mais apropriado para o narrador. Máximo de 3 tokens.

        4.  "suggested_target_audience": String
            *   Sugira o PÚBLICO-ALVO ideal para o vídeo. Máximo de 6 tokens.
        
        --- OBJETIVOS DA NARRATIVA ---
        $objectiveInstructions

        formato de resposta esperada:
        {
          "suggested_title": "string",
          "main_summary": "string",
          "video_objective_introduction": "string",
          "video_objective_content": "string",
          "video_objective_outcome": "string",
          "suggested_language_tone": "string",
          "suggested_target_audience": "string"
        }
        """.trimIndent()
    }
}