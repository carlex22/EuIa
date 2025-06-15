// File: prompts/CreateAudioNarrativeChat.kt
package com.carlex.euia.prompts

class CreateAudioNarrativeChat(
    // Contexto geral do vídeo
    private val currentUserNameCompany: String,
    private val currentUserProfessionSegment: String,
    private val currentUserAddress: String,
    private val currentUserLanguageTone: String,
    private val currentUserTargetAudience: String,
    private val videoTitle: String,
    private val videoObjectiveIntroduction: String,
    private val videoObjectiveContent: String,
    private val videoObjectiveOutcome: String,
    private val videoTimeSeconds: String,
    // Vozes pré-selecionadas para os personagens
    private val voiceNameSpeaker1: String,
    private val voiceNameSpeaker2: String,
    private val voiceNameSpeaker3: String? = null
) {
    private val userInfo: String = buildString {
        appendLine("## Dados do Cliente Solicitante do Vídeo:")
        if (currentUserNameCompany.isNotBlank()) appendLine("- Nome/Empresa: $currentUserNameCompany")
        if (currentUserProfessionSegment.isNotBlank()) appendLine("- Profissão/Ramo de Atuação: $currentUserProfessionSegment")
        if (currentUserAddress.isNotBlank()) appendLine("- Endereço/Website: $currentUserAddress")
    }.trim()

    private val videoContext: String = buildString {
        appendLine("## Contexto e Objetivos do Vídeo:")
        if (videoTitle.isNotBlank()) appendLine("- Título do Vídeo: \"$videoTitle\"")
        if (videoObjectiveIntroduction.isNotBlank()) appendLine("- Objetivo da Introdução: $videoObjectiveIntroduction")
        if (videoObjectiveContent.isNotBlank()) appendLine("- Objetivo do Conteúdo Principal: $videoObjectiveContent")
        if (videoObjectiveOutcome.isNotBlank()) appendLine("- Objetivo do Desfecho/CTA: $videoObjectiveOutcome")
        if (currentUserTargetAudience.isNotBlank()) appendLine("- Público-Alvo: $currentUserTargetAudience")
        if (currentUserLanguageTone.isNotBlank()) appendLine("- Tom de Linguagem Geral Desejado: $currentUserLanguageTone")
        if (videoTimeSeconds.isNotBlank()) appendLine("- Tempo Estimado da Narração Total: $videoTimeSeconds segundos")
    }.trim()

    private val charactersAndVoicesInfo: String = buildString {
        appendLine("## Personagens e Suas Vozes Designadas:")
        appendLine("- Personagem 1: Usará a voz pré-construída Gemini chamada \"$voiceNameSpeaker1\".")
        appendLine("- Personagem 2: Usará a voz pré-construída Gemini chamada \"$voiceNameSpeaker2\".")
        if (!voiceNameSpeaker3.isNullOrBlank()) {
            appendLine("- Personagem 3: Usará a voz pré-construída Gemini chamada \"$voiceNameSpeaker3\".")
            appendLine("- Este diálogo DEVE incluir os três personagens: Personagem 1, Personagem 2 e Personagem 3.")
        } else {
            appendLine("- Apenas dois personagens (Personagem 1 e Personagem 2) devem participar deste diálogo.")
        }
        appendLine("\n**IMPORTANTE: O CONTEÚDO PRINCIPAL DA NARRATIVA OU O TEXTO BASE para este diálogo será fornecido em um ARQUIVO DE TEXTO ANEXO.** Você deve usar esse texto como a principal fonte de informação para criar a conversa entre os personagens.")
    }.trim()

    // Lista completa de vozes Gemini para referência da IA, se necessário (embora as vozes sejam designadas).
    private val availableGeminiVoicesListForReference: String = """
    (Apenas para sua referência, caso precise de nomes válidos, mas priorize as vozes designadas acima)
    Lista de Nomes de Vozes Pré-construídas Gemini Disponíveis:
    Vozes Masculinas: Zephyr, Puck, Charon, Fenrir, Orus, Iapetus, Umbriel, Algieba, Algenib, Rasalgethi, Achernar, Schedar, Gacrux, Zubenelgenubi, Vindemiatrix, Sadachbia, Sadaltager, Sulafat
    Vozes Femininas: Kore, Leda, Aoede, Callirrhoe, Autonoe, Enceladus, Despina, Erinome, Laomedeia, Alnilam, Pulcherrima, Achird
    Vozes Neutras: Bright, Upbeat, Informative, Firm, Excitable, Campfire story, Breezy
    """.trimIndent()

    val prompt: String = """
    Você é um roteirista talentoso, especialista em criar diálogos naturais, dinâmicos e envolventes para vídeos curtos de redes sociais. Sua tarefa é criar um roteiro de diálogo completo para um vídeo, usando EXATAMENTE os personagens e vozes designados, e baseando-se no CONTEÚDO PRINCIPAL fornecido em um ARQUIVO DE TEXTO ANEXO (se fornecido, senão use o contexto geral).

    $userInfo
    $videoContext
    $charactersAndVoicesInfo

    ## Instruções para o Roteiro do Diálogo:

    1.  **Conteúdo Base:**
        *   O **texto principal para o diálogo (narrativa, informações, etc.) está contido em um ARQUIVO DE TEXTO ANEXADO a esta requisição (se um foi anexado).**
        *   Se um arquivo de texto foi anexado, leia e compreenda profundamente seu conteúdo. Ele é a principal fonte para o diálogo. Transforme as informações e o fluxo do texto anexado em uma conversa natural entre os personagens designados.
        *   Se NENHUM arquivo de texto foi anexado, baseie o diálogo nos "Dados do Cliente" e "Contexto e Objetivos do Vídeo" fornecidos acima.

    2.  **Criação do Diálogo (`dialogScript`):**
        *   Utilize OS PERSONAGENS INFORMADOS na seção "Personagens e Suas Vozes Designadas" (Personagem 1, Personagem 2, e Personagem 3 SE a voz para ele foi fornecida).
        *   O diálogo DEVE ser uma conversa fluida e natural.
        *   Cada fala deve ser precedida pela etiqueta de personagem correspondente (ex: "Personagem 1:", "Personagem 2:", "Personagem 3:").
        *   **Incorpore comandos ou descrições de estilo de fala diretamente no texto do diálogo** quando apropriado para transmitir a emoção desejada ou dar ênfase, especialmente considerando o estilo da voz designada para o personagem. Estes comandos devem ser claros, concisos e entre parênteses `()` ou colchetes `[]` ANTES do trecho de texto ao qual se aplicam.
            *   Exemplos de estilo de fala: `(com entusiasmo)`, `(tom mais sério)`, `(sussurrando)`, `(com uma risada contida)`, `(intrigado)`, `(confiante)`.
        *   O conteúdo do diálogo deve abordar os objetivos do vídeo (introdução, conteúdo principal, desfecho/CTA) de forma conversacional, utilizando o texto do arquivo anexado (se houver) ou o contexto geral como base.
        *   Adapte a linguagem e o estilo da conversa ao público-alvo e ao tom geral definidos.
        *   A duração total do diálogo deve ser compatível com o "Tempo Estimado da Narração Total".
        *   Evite descrições visuais de cenas ou ações, foque APENAS no que é falado.
        *   NÃO use marcações como "[pausa]" ou "[risos]" a menos que seja um comando de estilo de fala, como "(com uma risada)".
        *   NÃO inclua números (0-9) no texto; escreva-os por extenso (ex: "noventa e nove" em vez de "99"). Converta símbolos como "R$", "%", "@", "+", "-" para suas formas escritas (ex: "reais", "por cento", "arroba", "mais", "menos").

    3.  **Configuração de Voz para os Personagens (`speakerVoiceSuggestions`):**
        *   Para cada personagem no diálogo, o campo `suggestedVoiceName` DEVE ser EXATAMENTE o nome da voz Gemini que foi designado para ele na seção "Personagens e Suas Vozes Designadas".
        *   No campo `suggestedStyleNotes`, forneça uma breve descrição do estilo geral esperado para aquele personagem, alinhado com a voz designada e o tom geral do vídeo (ex: "alegre e feminina", "calmo e masculino", "informativa e neutra").

    $availableGeminiVoicesListForReference

    ## Formato de Saída JSON Esperado:
    Sua resposta DEVE ser um ÚNICO objeto JSON, sem nenhum texto ou comentário adicional antes ou depois. O JSON deve ter a seguinte estrutura EXATA:

    [{
      "dialogScript": "Personagem 1: (com entusiasmo) Olá e bem-vindos! Hoje vamos conversar sobre o tema do nosso arquivo de texto!\nPersonagem 2: Oi pessoal! (animado) Exatamente! O arquivo menciona que \"{CONTEUDO_DO_ARQUIVO_TEXTO_ADAPTADO_AQUI}\".\nPersonagem 1: E isso se encaixa perfeitamente com {VIDEO_OBJECTIVE_INTRODUCTION}...\nPersonagem 2 (curioso): E como isso nos ajuda com {VIDEO_OBJECTIVE_CONTENT} baseado no texto?\nPersonagem 1: Ótima pergunta! O texto detalha que ... (continua o diálogo baseado no arquivo ou contexto) ...\nPersonagem 2: Entendi! E para finalizar, o que o pessoal pode fazer? {VIDEO_OBJECTIVE_OUTCOME}\nPersonagem 1: Isso mesmo! (concluindo) Não se esqueçam de ...!",
      "speakerVoiceSuggestions": [
        { "speakerTag": "Personagem 1", "suggestedVoiceName": "$voiceNameSpeaker1", "suggestedStyleNotes": "Descrição do estilo para Personagem 1" },
        { "speakerTag": "Personagem 2", "suggestedVoiceName": "$voiceNameSpeaker2", "suggestedStyleNotes": "Descrição do estilo para Personagem 2" }
        ${if (!voiceNameSpeaker3.isNullOrBlank()) ",{ \"speakerTag\": \"Personagem 3\", \"suggestedVoiceName\": \"$voiceNameSpeaker3\", \"suggestedStyleNotes\": \"Descrição do estilo para Personagem 3\" }" else ""}
      ]
    }]

    **Lembre-se:** O **CONTEÚDO PRINCIPAL DA NARRATIVA ESTÁ NO ARQUIVO DE TEXTO ANEXO (se fornecido)**. Use-o para criar o `dialogScript`. As `speakerVoiceSuggestions` são para mapear os Personagens às vozes Gemini designadas. Se nenhum arquivo for anexado, use o contexto geral.
    """.trimIndent().replace("{VIDEO_TITLE}", videoTitle)
                     .replace("{VIDEO_OBJECTIVE_INTRODUCTION}", videoObjectiveIntroduction.take(20) + "...")
                     .replace("{VIDEO_OBJECTIVE_CONTENT}", videoObjectiveContent.take(20) + "...")
                     .replace("{VIDEO_OBJECTIVE_OUTCOME}", videoObjectiveOutcome.take(20) + "...")
                     .replace("{CONTEUDO_DO_ARQUIVO_TEXTO_ADAPTADO_AQUI}", "o conteúdo principal do arquivo anexado ou contexto fornecido")
}