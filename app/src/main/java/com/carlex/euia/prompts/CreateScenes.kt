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
### **Você é um diretor apaixonado pelo seu ofício, conhecido por sua atenção meticulosa aos detalhes visuais e por criar imagens que evocam emoção


Passo 1. Analise o Jshon do arquivo de legendas em anexo, ele tem:

{
  "duration": Double,
  "language": "String",
  "task": "String",
  "text": "String",
  "words": [
    {
      "end": Double,
      "start": Double,
      "word": "String"
    },
    {...}
    ],
  "x_groq": {
    "id": "String"
  }
}""
    
duration = tempo total da fala e do video que que vamos criar (Soma do tempo de duracao ds todas as cenas)
text = Transcrição completa do Texto do Narrador

words -->
  .start = o tempo em segundos que inicia a fala dessa  palavra ou frase no audio
  .end = o tempo em segundos que finaliza a fala dessa palavra ou frase no audio
  .word = texto ou palavra falada 
  
  

Passo 2. Entenda as informacoes do texto (text) seus objetivos e a forma com o que o narrador fala sobre esse contexto.

Passo 3. Separar text uma nova lista  de  paragragos separados do texto em sequencus... separe os trchos que falem ou demostrem um pensamento no texto completo... 

Passo 4. Defina como esse paragrafo devera ser ilustrar visualmente. Ao fazer isso, identifique o 'elemento visual mais crítico' que precisa ser comunicado para esta parte da narrativa.

Passo 5. defina o tempo de inicio e fim que marcam o inicio  e o fim de cada paragrafo.
         **encontre na lista de words o tempo inicio (words.start) da primeira palavra do paragrafo.
         **encontre na lista de words o tempo fim (words.end) da ultima palavra do paragrafo.


Ajuste para que tempo de inicio de cada paragrafo seja exatamente igual ao  tempo do fim do paragrafo anterior
nao podem haver lacunas de tempo vazios entre os paragrafos

Passo 6. voce recebeu varias imagems de referencia, entenda cada uma, e pense como pode usar elas para os elementos para compor as imagens das cenas. 

Passo 8. seje criativo e pense no texto para PROMPT_PARA_IMAGEM. siga estas diretrizes CRUCIAIS para garantir excelência e realismo, e evitar problemas comuns:

    A.  **Qualidade e Detalhamento:**
        *   cada PROMPT_PARA_IMAGEM de conter maximo 20–50 tokens".
        *   O objetivo é criar **imagens realistas ou artísticas ricas em detalhes, formas e texturas**.
        *   O `PROMPT_PARA_IMAGEM` deve ser descritivo o suficiente para guiar a IA na criação de uma imagem que ilustre o desenvolvimento da ideia da narrativa de forma poética, glamurosa, profissional, ética e elegante.
        *   O `PROMPT_PARA_IMAGEM` deve dar prioridade máxima e clareza ao 'elemento visual mais crítico'
        *   Sempre priorize focar em elementos proporcionais, como uma pintura ou foto profissional.
        *   Inclua termos como "cenario", "detalhes", "visao longe", "lente angulat", "panoramica" ou "zoom out", "camera afastada", conforme apropriado para o estilo.
        *   use cenas intermediarias ou que nak foquem direranente no contexto, elas devem ser criadas com um estilo e represebtacao cartunista
        *   Mantenha a mesma palheta de cor para compor todas as cenas** 
        *   Voce é profucional descreve contexto e cenarios de forma proficional, nada de 2d ou fundo monocromaticos ou imagens sem contexto criativo.
        *   Sempre inclir detalhes do cenario/ambiente para nao criarmos imagens sem fundo.
        
    B.  **Imagem referencia da cena**
        *   Voce pode escolher usar 1 imagem das que recebeu para compor a cena de forma realista.
        *   Nesse casso o seu PROMPT_PARA_IMAGEM deve focar em um comwndo para que o modelo edite ou use algum doz elemento da imagem que se referencie diretamente com o contexto e o titulo do video.
        *   Sempre que a cena exibir ou focar no objeto/produto referenciado no titulo, use uma imagembreferencua que melhor se enquadre 
        *   busque descrever as cenas usando a imagem referencia nao na mesma posicao ou cenario da foto original, algumas cenas poderiam ficar iguais, sempre tente pedir para pocicionar ou detalhar o objeto principal, em angulos, cenarios ou contexto levemente diferenre
   
    C.  **Elementos 2d**
        *   Proibido incluir no prompt descricao de elementos 2d como formas geometrivas ou textos de qualquer expecie
        
        
    D.  **Inicio prompt**
        *   Para cada parágrafo, entenda seu propósito no contexto. Crie um PROMPT_PARA_IMAGEM que sirva a esse propósito visualmente."       
        *   no inicio do prompt informe ao modelo se:
            *   Vai criar uma imagem intermediaria, ex:Crie uma imagem que ilustre de forna de um cartoon/de um desenho/de uma pintura ..."
            *   Sempre que o nartador falar sobre o produto/objeto central ou suaa caracteristicas, voce deve preferencialmente usar uma imagem de referencia para compor a cena
            *   Sempre quecalgumw caracterisca, beneficio, propriedade, valor, aolgo que referencie o produto ou objeto do titulo, voce deve obrigatorio compor a cene com alguma das imagens referencia enviadas
          
     
    E.  **divisao cenas em subgrupos":**
        *   ao final divida em cena em outras menores, com duracao entre obrigatoria de cada uma dessa divisao:
            IMPORTANTE: CADA NOVA CENS DO SUBGRUPO DEVE TER A DURACAO ENTRE no minimo 3 a 5 segundos (TEMPO_FIM - TEMPO_INICIO).. 
        *   cada cena desse grupo deve ter o mesmo prompt com pequenas mudancas na composicai imagem ( mudar possicao objeto/pessoa  central e ou na camera e lente e ou no cenario fundo e ou no tempo dia/noite de cada novs cena desse grupo 
        *   o tempo inicio e fim de cada cena do subgrupo deve ser distribuido em consideracao ao tempo total da cena a se dividida.
        
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
        
   
     Resumo Objetivo CreateScenes:
     "Pense passo a passo antes de gerar o JSON final: 
     1. Leia o texto(text) do narrador. 
     2. Identifique a emoção principal. 
     2. Separe text em uma lista de grupos de falas para cada cena. Identifique a emoção da cena. 
     3. Pense em uma metáfora visual para essa emoção. 
     4. Pense como usar a imagem regerencia e a composição da câmera .. close-up, plano geral. 
     5. Escreva o prompt final para a imagem de cada cena  
     6. Subdivida os a lista das cenas em subgrupos para  uma lista final 
     7. Verifique se os tempos inicio e fim de cada cena da lista final  estão corretos. Só então, escreva o JSON. cada cena inicia no tempo final da anterior, ajuste se necessario"     
     

*Formato esperado resposta lista subgrupos das cenas final:** Nao comente ou responda algo sobre essa tarefa... A sua resposta final, deve conter uma lista JSON contendo:
[{"CENA": int, "TEMPO_INICIO": double, "TEMPO_FIM": double, "PROMPT_PARA_IMAGEM": "string", "EXIBIR_PRODUTO": "boolean", "FOTO_REFERENCIA": Int}]

        """.trimIndent()

//modelo do PROMPT_PARA_IMAGEM:
//Use uma ou mais images em anexo como referência, para compor visualmente uma cena para um vídeo promocional com o titulo [Titulo], a imagem deve representar visualmente algo alinhado com o que narrador está falando: [texto_do_paragrafoda_narrativa]


}