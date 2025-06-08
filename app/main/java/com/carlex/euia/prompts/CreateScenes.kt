// File: prompts/CreateScenes.kt
package com.carlex.euia.prompts

class CreateScenes(
    private val textNarrative: String,
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
        appendLine("Dados cliente solicitante:")
        if (currentUserNameCompany.isNotBlank()) appendLine("- Nome/Empresa: $currentUserNameCompany")
        if (currentUserProfessionSegment.isNotBlank()) appendLine("- Profissão/Ramo Atuação: $currentUserProfessionSegment")
        if (currentUserAddress.isNotBlank()) appendLine("- Endereço: $currentUserAddress")
    }.trim()

    private val videoInfo: String = buildString {
        appendLine("Contexto do video:")
        if (videoTitle.isNotBlank()) appendLine("- Titulo: $videoTitle")
        if (videoObjectiveIntroduction.isNotBlank()) appendLine("- Introdução: $videoObjectiveIntroduction")
        if (videoObjectiveContent.isNotBlank()) appendLine("- Conteudo: $videoObjectiveContent")
        if (videoObjectiveOutcome.isNotBlank()) appendLine("- Desfecho: $videoObjectiveOutcome")
    }.trim()

    public var prompt = """
### **Você é um diretor brilhante**, especializado em criar vídeos curtos e impactantes, como Shorts, foi contratado para criar a composicao visual para cenas de um video.

$userInfo

$videoInfo

texto do narrador do video: $textNarrative

Passo 1. Analise o texto do arquivo de legendas em anexo (.srt ou .txt), ele tem timestamp e a transcrição do dialogo do narrador.

Passo 2. Entenda as informacoes do video seus objetivos e a forma com o que o narrador fala sobre esse contexto.

Passo 3. Separar em paragrafos o texto da fala do narrador. (texto_do_paragrafoda_narrativa)

Passo 4. Defina como esse paragrafo deve vai ilustrar visualmente a narrativa junto com o contexto. Ao fazer isso, identifique o 'elemento visual mais crítico' que precisa ser comunicado para esta parte da narrativa.

Passo 5. defina o tempo de inicio e fim que marcam o inicio e o fim de cada paragrafo.
Os TimeStamp tempo da legendas estao no formato hh:mm:ss voce precisa converter esse tempo para segundos double
Cada paragrafo termina exatamente no tempo do inicio do proximo paragrafo
nao podem haver lacunas de tempo vazios entre os paragrafos

Passo 6. voce recebeu varias imagems de referencia, entenda cada uma, e pense como pode usar elas para os elementos para compor as imagens das cenas. 

Passo 7. para cada paragrafo crie 1 ou mais cenas com duracao entre 2 a 5 segundos cada.

Passo 8. seje criativo e pense no texto para PROMPT_PARA_IMAGEM. siga estas diretrizes CRUCIAIS para garantir excelência e realismo, e evitar problemas comuns:

    A.  **Qualidade e Detalhamento:**
        *   cada PROMPT_PARA_IMAGEM de conter maximo 200–300 tokens".
        *   O objetivo é criar **imagens realistas ou artísticas ricas em detalhes, formas e texturas**.
        *   O `PROMPT_PARA_IMAGEM` deve ser descritivo o suficiente para guiar a IA na criação de uma imagem que ilustre o desenvolvimento da ideia da narrativa de forma poética, glamurosa, profissional, ética e elegante.
        *   O `PROMPT_PARA_IMAGEM` deve dar prioridade máxima e clareza ao 'elemento visual mais crítico'
        *   Sempre priorize focar em elementos proporcionais, como uma pintura ou foto profissional.
        *   Inclua termos como "alta resolução", "detalhes nítidos", "selfie", "lente olho de gato", "qualidade fotográfica" ou "qualidade cinematográfica", "realista", conforme apropriado para o estilo.
        *   use cenas intermediarias ou que nak foquem direranente no contexto, elas devem ser criadas com um estilo e represebtacao cartunista
        *   Mantenha a mesma palheta de cor para compor todas as cenas** 
        *   Voce é profucional descreve contexto e cenarios de forma proficional, nada de 2d ou fundo monocromaticos ou imagens sem contexto criativo.
        *   Sempre inclir detalhes do cenario/ambiente para nao criarmos imagens sem fundo.
        
    B.  **Imagem referencia da cena**
        *   Voce pode escolher usar 1 imagem das que recebeu para compor a cena de forma realista.
        *   Nesse casso o seu PROMPT_PARA_IMAGEM deve focar em um comwndo para que o modelo edite ou use algum doz elemento da imagem que se referencie diretamente com o contexto e o titulo do video.
        *   Sempre que a cena exibir ou focar no objeto/produto referenciado no titulo, use uma imagembreferencua que melhor se enquadre 
        *   busque descrever as cenas usando a imagem referencia nao na mesma posicao ou cenario da foto original, algumas cenas poderiam ficar iguais, sempre tente pedir para pocicionar ou detalyar o objeto principal, em angulos, cenarios ou contexto levemente diferenre
   
    C.  **Elementos 2d**
        *   Proibido incluir no prompt descricao de elementos 2d como formas geometrivas ou textos de qualquer expecie

    D.  **Inicio prompt**
        *   no inicio do prompt informe ao modelo se:
            *   Vai editar a imagem referencia, ex: (Use a imagem que enviei e a modifique da seguinte forma:..."
            *   Vai usar algum(s) elemento(s) da imagem referecia, ex: (Crie uma imagem ... use a roupa/objeto/detalhe da imagem em anexo para ..."
            *   Vai criar uma imagem intermediaria, ex: (Crie uma imagem que ilustre de forna ludica/de um desenho/de uma pintura ..."
            *   Vai criar alguma imagem sem foto referencia, ex: (Crie uma imagem que represente: ...)

     E.  **Tempo duracao das cenas**
         *  O tempo de inicio de cada cena, teve **OBRIGATIRIAMENTE SEM EXCOES NENHUMA**, concidir exatamente com o tempo do fim da cena anterior
         *  Revise os tempos de todas as cenas, nao podemos ter tempo vazios entre as cenas em ipotesw nenhuma
         *  Converta TEMPO_INICIO e TEMPO_FIM para segundos formato double**

*Formato esperado resposta:** Nao comente ou responda algo sobre essa tarefa... A sua resposta final, deve conter uma lista JSON contendo:
[{"CENA": int, "TEMPO_INICIO": double, "TEMPO_FIM": double, "PROMPT_PARA_IMAGEM": "string", "EXIBIR_PRODUTO": "boolean", "FOTO_REFERENCIA": Int}]

        """.trimIndent()

//modelo do PROMPT_PARA_IMAGEM:
//Use uma ou mais images em anexo como referência, para compor visualmente uma cena para um vídeo promocional com o titulo [Titulo], a imagem deve representar visualmente algo alinhado com o que narrador está falando: [texto_do_paragrafoda_narrativa]


}