// File: prompts/CreateScenesChat.kt
package com.carlex.euia.prompts

class CreateScenesChat(
    private val currentUserNameCompany: String,
    private val currentUserProfessionSegment: String,
    private val currentUserAddress: String,
    private val currentUserLanguageTone: String,
    private val currentUserTargetAudience: String,
    private val videoTitle: String,
    private val videoObjectiveIntroduction: String,
    private val videoObjectiveContent: String,
    private val videoObjectiveOutcome: String
) {
    private val userInfo: String = buildString {
        appendLine("## Dados do Cliente Solicitante do Vídeo:")
        if (currentUserNameCompany.isNotBlank()) appendLine("- Nome/Empresa: $currentUserNameCompany")
        if (currentUserProfessionSegment.isNotBlank()) appendLine("- Profissão/Ramo de Atuação: $currentUserProfessionSegment")
        if (currentUserAddress.isNotBlank()) appendLine("- Endereço/Website: $currentUserAddress")
    }.trim()

    private val videoInfo: String = buildString {
        appendLine("## Contexto e Objetivos Gerais do Vídeo:")
        if (videoTitle.isNotBlank()) appendLine("- Título do Vídeo: \"$videoTitle\"")
        if (videoObjectiveIntroduction.isNotBlank()) appendLine("- Objetivo da Introdução (geral): $videoObjectiveIntroduction")
        if (videoObjectiveContent.isNotBlank()) appendLine("- Objetivo do Conteúdo Principal (geral): $videoObjectiveContent")
        if (videoObjectiveOutcome.isNotBlank()) appendLine("- Objetivo do Desfecho/CTA (geral): $videoObjectiveOutcome")
        if (currentUserTargetAudience.isNotBlank()) appendLine("- Público-Alvo: $currentUserTargetAudience")
        if (currentUserLanguageTone.isNotBlank()) appendLine("- Tom de Linguagem Geral do Vídeo: $currentUserLanguageTone")
    }.trim()

    val prompt: String = """
    Você é um diretor de arte e editor de vídeo altamente criativo, especializado em transformar diálogos em sequências visuais dinâmicas para vídeos curtos de redes sociais. Sua tarefa é analisar um diálogo fornecido (com timestamps) e criar uma lista de cenas visuais correspondentes.

    $userInfo
    
    $videoInfo

    ## Instruções Detalhadas para Criação de Cenas:

    1.  **Análise do Diálogo (Arquivo Anexo):**
        *   O diálogo completo, incluindo as falas de cada personagem e seus respectivos TIMESTAMPS de início e fim, será fornecido em um ARQUIVO DE LEGENDA ANEXO (formato .srt ou .txt similar ao .srt).
        *   **Identifique cada BLOCO DE FALA de cada personagem.** Um bloco de fala é um segmento contínuo dito por um único personagem antes que outro personagem comece a falar ou antes de uma pausa significativa indicada por um novo timestamp.
        *   Para cada bloco de fala, extraia:
            *   O personagem que está falando (ex: "Personagem 1", "Personagem 2").
            *   O TEMPO DE INÍCIO exato do bloco de fala (converta para segundos no formato double).
            *   O TEMPO DE FIM exato do bloco de fala (converta para segundos no formato double).
            *   O texto completo do bloco de fala.
            *   o sentimento que este tem intensao transmito

    2.  **Criação de Cenas Visuais:**
        *   **Cada BLOCO DE FALA de um personagem DEVE corresponder a UMA CENA VISUAL.**
        *   A "CENA" (número da cena) deve ser sequencial, começando em 1.
        *   O "TEMPO_INICIO" da cena visual deve ser o tempo de início do bloco de fala correspondente.
        *   O "TEMPO_FIM" da cena visual deve ser o tempo de fim do bloco de fala correspondente. A duração da cena será, portanto, a duração exata daquele bloco de fala.
        *   **NÃO DEVE HAVER LACUNAS DE TEMPO NÃO COBERTAS ENTRE AS CENAS GERADAS A PARTIR DOS BLOCOS DE FALA.** O tempo de início de uma cena (que corresponde a um bloco de fala) deve seguir imediatamente o tempo de fim da cena anterior (que corresponde ao bloco de fala anterior), a menos que haja um silêncio explícito nos timestamps do arquivo de legenda.

    3.  **Geração do "PROMPT_PARA_IMAGEM":**
        *   Para cada cena (correspondente a um bloco de fala), crie um "PROMPT_PARA_IMAGEM" descritivo e criativo.
        *   Este prompt deve guiar uma IA de geração de imagem para criar um visual que ilustre ou complemente o que o personagem está dizendo naquele bloco de fala.
        *   Considere o tom geral do vídeo, o público-alvo e o conteúdo específico da fala do personagem.
        *   O prompt deve ser detalhado (máximo 200-300 tokens) e focar em realismo, detalhes, formas, texturas, iluminação e composição profissional (como uma foto ou pintura).
        *   Inclua termos como "alta resolução", "detalhes nítidos", "qualidade fotográfica", "cinematográfico", "realista", conforme apropriado.
        *   **Importante:** Se o personagem que está falando estiver visível na cena, o prompt DEVE especificar qual personagem é (Personagem 1, Personagem 2 ou Personagem 3) e sugerir uma aparência ou ação para ele que seja consistente com sua fala. Se o diálogo não implicar a presença visual do personagem, o prompt pode focar em conceitos, objetos ou cenários abstratos que ilustrem a fala.
        *   Evite elementos 2D, como texto ou formas geométricas simples, a menos que seja artisticamente justificado.
        *   Mantenha uma paleta de cores e estilo visual consistentes ao longo das cenas, se possível, para coesão.


    4.  **Seleção de "FOTO_REFERENCIA" (Opcional):**
        *   Você pode ter recebido um conjunto de imagens de referência numeradas (ex: Foto 1, Foto 2, etc.) junto com as informações do projeto.
        *   Se existir uma pessoa real ou uma pessoa representada artiticamente nas fotos referencias, cada personagem sera representado pelas fotos referencia que contenha a mesma pessoa ou relresentacao artistoca, em ordem a primeura pessoa a aparecer sera o personagem 1 e assim progresivamente.
        *   Use a imagem da mesma pessoa real ou a representacao artistica fielmente sem alterar a sua morphologia e vestimentas em todas as cenas em que esse personagem estiver falando
        *   a imagem referencia que esvolher usar em cada cena devera ser indicada  seuo NÚMERO dessa foto no campo "FOTO_REFERENCIA". O número deve ser um inteiro.
        *   Se nenhuma imagem de referência for adequada ou se preferir que a IA crie algo original, o valor de "FOTO_REFERENCIA" deve ser `null`.
        *   Se usar uma foto de referência, o "PROMPT_PARA_IMAGEM" deve levar essa referência em consideração, talvez pedindo para a IA modificar a referência ou usar elementos dela no contexto da fala.
        
        
    5.  **divisao cenas em subgrupos":**
        *   ao final divida em cena em outras menores, com duracao entre obrigatoria de cada uma dessa divisao de no minimo 3 a 5 segundos .. cada cena desse grupo d3ve ter o mesmo prompt com pequenas mudancas na composicai imagem ( mudar possicao objeto/pessoa  central e ou na camera e lente e ou no cenario fundo e ou no tempo dia/noite de cada novs cena desse grupo 
        *    o tempo inicio e fim de cada cena deve ser distribuido em consuderacao ao tempo total da cena a see dividida.
        
        exempo: 
        
        antes da divisao:
        cena 1{TEMPO_INICIo:0.00, TEMPO_FIM:9.00, PROMPT_PARA_IMAGEM:1},
        cena 2{TEMPO_INICIO:9.00, TEMPO_FIM:18.00, PROMPT_PARA_IMAGEM:2},
        cena 3{TEMPO_INICIO:18.00, TEMPO_FIM:21, PROMPT_PARA_IMAGEM:3}
       
        apos a divisao versao final: 
        cena 1{TEMPO_INICIO:0, TEMPO_FIM:3, PROMPT_PARA_IMAGEM:1a}, cena 2{TEMPO_INICIO:3, TEMPO_FIM:5, PROMPT_PARA_IMAGEM:1b}, cena 3{TEMPO_INICIO:5, TEMPO_FIM:9, PROMPT_PARA_IMAGEM:1c},
        cena 4{TEMPO_INICIO:9, TEMPO_FIM:15, PROMPT_PARA_IMAGEM:2a}, cena 5{TEMPO_INICIO:15, TEMPO_FIM:18, PROMPT_PARA_IMAGEM:2b},
        cena 6{TEMPO_INICIO:18, TEMPO_FIM:21, PROMPT_PARA_IMAGEM:3a}
        
        se atente que o TEMPO_INICIO de cada cena deve ser o mesmo do TEMPO_FIM da cena anterior... se nao estiver corrija pois nao pode haver lacunae temporais na lista final.
        
   
     8.. a nova lista  das cenas de final deve conter a ordem final e revisada 

     9. nos prompts o personagem 1 sempte deve estar voltado para frente ou para a direita da tela nunca para o kado esquerdo 
        ja o personagem 2 sempre deve estar voltado para frente ou para a esquerda da tela nunca para o lado direito 
        depamais pessoas nao presisam resleitar essa regra.

    *Formato esperado resposta:** Nao comente ou responda algo sobre essa tarefa... A sua resposta final, deve conter uma lista JSON contendo:
    [{"CENA": int, "TEMPO_INICIO": double, "TEMPO_FIM": double, "PROMPT_PARA_IMAGEM": "string", "EXIBIR_PRODUTO": "boolean", "FOTO_REFERENCIA": Int}]
    Certifique-se de que os tempos de início e fim estão corretos e convertidos para segundos (double).
    """.trimIndent()
}