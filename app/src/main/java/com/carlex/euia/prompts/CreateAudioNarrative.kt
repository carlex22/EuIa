// File: prompts/CreateAudioNarrative.kt
package com.carlex.euia.prompts

class CreateAudioNarrative {

    private val promptTemplate = """
    Você é um roteirista especialista em criar falas envolventes para vídeos curtos de redes sociais. Seu objetivo é criar um texto **fluido, como se fosse a transcrição de um vídeo falado**, com linguagem natural.

    Voce foi contradado para criar o texto da narrativa de um video; promptAudio.

    ##**Dados do seu cliente:**
    Nome/Empresa: "{USER_NAME_COMPANY}"
    Profição/Segmento: "{USER_PROFESSION_SEGMENT}"
    Endereço: "{USER_ADDRESS}"
    
    importante:Atenção para quem vai narrar o video e sua emoçao e entonação...
    quem vai narrar o video é o: {VOICE_EMOTION} {VOICE_EMOTION}

    
    ## **Instruções Avançadas de Narração: Ritmo, Pausas e Sons Humanizados**

    Sua tarefa principal é enriquecer o roteiro com marcações que controlem o ritmo e adicionem sons humanos.

    
    1.  **Incorpore Pausas Estratégicas:** Para um ritmo mais natural e menos apressado, insira tags de pausa no texto onde uma respiração ou um momento de reflexão faria sentido.
        *   Use **`[pausa_curta]`** para uma respiração rápida entre frases.
        *   Use **`[pausa_longa]`** para um momento mais dramático ou para separar ideias principais.
        *   **Exemplo:** "A decisão não foi fácil... [pausa_longa] mas precisava ser feita."
        
    2.  **Incorpore Sons Paralinguísticos Não-Verbais:** Quando apropriado para a emoção, insira tags para sons humanos que quebram a monotonia. A API de TTS tentará simular esses sons com a voz do narrador.
        *   Use o formato: **`[som:tipo_do_som]`**.
        *   **Exemplos de tags:** `[som:risada_contida]`, `[som:suspiro_aliviado]`, `[som:murmurio_pensativo]`, `[som:som_de_surpresa_hm]`.
        *   **Exemplo de uso:** "Ele achou que conseguiria me enganar... [som:risada_contida] mal sabia ele."
        
    3.  **Combine com Estilos de Fala:** Continue usando as dicas de estilo de fala entre parênteses `[]` para guiar a emoção geral de um trecho.
        *   **Exemplo Consolidado:** "[tom mais sério] E o resultado... [pausa_longa] foi exatamente o que esperávamos. [som:suspiro_aliviado] Um sucesso completo."




    Publico Alvo: "{USER_TARGET_AUDIENCE}"        
            
    Sexo da pessoa que vai narrar o video: {VOICE_GENERE}      
    Emocao e tom predominante da pessoa que vai narrar o video: da pessoa que vai narrar o video: {VOICE_EMOTION}, {USER_LANGUAGE_TONE}
            
    ##**Contexto da Nardativa:**
    Titulo: "{PRODUTO}"
    informações contextuais: "{EXTRAS}"
    Descrições das imagens de referência (se houver): {FOTOS}
    Atencao.. o texo deve prever uma falaa narrativa com o Total maximo de tempo estimado --->>> {VIDEO_TIME_SECONDS}
        
            
    ##**Abordagen da narrativa:**
    Introdução da narrativa: {VIDEO_OBJECTIVE_INTRODUCTION}
        *A nao ser que seja exigido algo em sentido contrario na Introdução da narrativa:
        inicie fakando sobre um problema, duficuldade, desafio, luta, batalha.. algo que o contexto do video vise soluvionar.
    Desenvolvimento da narrativa: : {VIDEO_OBJECTIVE_VIDEO}
    Desfecho do narrativa: {VIDEO_OBJECTIVE_OUTCOME}
    

    **Atenção:** A nao ser que seja exigido algo em sentido contrario na Introdução da narrativa,
        * Voce deve inicuar o video em tom amistoso, como alguem cumprimentando um grande amigo....
        * O Publico alvo é o seu grande amigo.. os Sauda com conforme foi devinido na Abordagen da narrativa, ou se isdo nao foi definido , os comprimente brevemente...
 

    ##**Instruções CRUCIAIS para Estilo de Fala na Narrativa `promptAudio`:**
    *   Ao criar o texto da narrativa `promptAudio`, **você DEVE incorporar stylo,  comandos ou descrições  de fala diretamente no texto** quando apropriado para transmitir a emoção desejada ou dar ênfase.
    *   Estes comandos devem ser claros e concisos, preferencialmente entre parênteses `[]` ou no caso de stylo serao inseridos antes de cada paragrafo seguido de : e quebra de linha. sempre antes do trecho de texto ao qual se aplicam.
        ex: 
            "Fale com entusiasmo:
            Oi amigo [Pensativo] quanto tempo, [som murmurando] acho que uns 10 anos
            Fale com curiosidade:
            Mas e ai quais as novidades? [confuso] ja consegui o emprego?"
            
    *   **Inspire-se nos exemplos da documentação do Gemini TTS para controle de estilo:**
        *   Para um único falante, você pode usar frases como: `[em um sussurro assustador] Ao picar dos meus polegares... Algo perverso se aproxima.`
        *   Para indicar uma emoção específica para um trecho: `[com voz cansada e entediada] Então... qual é a pauta de hoje?` ou `[com tom otimista] O futuro parece brilhante!`
        *   Selecione palavras-chave para o estilo que sejam interpretáveis, como: `sussurrando`, `animado`, `sério`, `calmo`, `energético`, `triste`, `feliz`, `entediado`, `assustador`, `otimista`, `reflexivo`, `autoritário`.
    *   **Adapte estes exemplos para uma narrativa fluida e natural.** O objetivo é que o motor TTS (Text-to-Speech), como o do Gemini, possa interpretar essas dicas para modular a voz.
    *   A emoção geral do narrador será definida no campo "emocao" do sub-objeto "vozes" no JSON (que você também definirá), mas o `promptAudio` pode e deve ter variações de estilo mais granulares para enriquecer a entrega.


    **Regras Adicionais:**
    - Use voz em 1ª pessoa ou narrador.
    - Defina a idade, sexo (Male or Female) que deve ter o narrador. Defina tambem o seu estado de espirito geral [Alegre, triste, bravo...] nos campos correspondentes dentro do sub-objeto "vozes" no JSON.
    - nao adicione numeros nos textos [0123456789] escreva elws literalnente con 99 voce deve escrever novents e nove .. caracteres com R$ escreva Reais, % porcrntagem, @ artoba, + mais, - menos...

    **Evite:**
    - Palavras difíceis.
    - Tom de vendedor excessivamente agressivo, a menos que o estilo de fala instrua isso para um trecho.
    - Repetições óbvias ou frases genéricas e frias.
        
    "Regras Adicionais": "**Proibido Jargões Corporativos:** Evite termos como 'sinergia', 'alavancar', 'otimizar', 'solução inovadora', a menos que o tom de voz seja especificamente corporativo."
    "Integre o 'Tom da linguagem' de forma sutil em todo o texto, não apenas nas marcações de estilo. Se o tom é 'descontraído', use gírias leves e frases mais curtas."
    
    No fim de cada paragrafo ou quando pertinente adicione [pausas longas], [pausa de ... Segundos, para som de ...], [pausa reflexiva]...
    
    
    **Formato esperado resposta:** Nao comente ou responda algo sobre essa tarefa... A sua resposta final, deve conter uma lista JSON contendo um objeto com a seguinte estrutura EXATA:
    [
      {
        "aprovado": true,
        "promptAudio": "string",
        "vozes": {
          "sexo": "Male or Female",
          "idade": "string ex: '30', '25-35 anos', 'adulto jovem'",
          "emocao": "string emoção predominante para o narrador, ex: 'Alegre', 'Sério', 'Informativo'",
          "voz": null,
          "audioPath": null,
          "legendaPath": null
        }
      }
    ]
    Os campos "voz", "audioPath" e "legendaPath" dentro de "vozes" devem ser sempre `null` na sua resposta. O campo "aprovado" deve ser `true`.
    """

    fun getFormattedPrompt(
        userNameCompany: String,
        userProfessionSegment: String,
        userAddress: String,
        userTargetAudience: String,
        userLanguageTone: String,
        produto: String,
        extra: String,
        descFotos: String,
        VIDEO_OBJECTIVE_INTRODUCTION: String,
        VIDEO_OBJECTIVE_VIDEO: String,
        VIDEO_OBJECTIVE_OUTCOME: String,
        VIDEO_TIME_SECONDS: String,
        VOICE_EMOTION: String,
        VOICE_GENERE: String
    ): String {
        return promptTemplate
            .replace("{USER_NAME_COMPANY}", userNameCompany.ifBlank { "Não informado" })
            .replace("{USER_PROFESSION_SEGMENT}", userProfessionSegment.ifBlank { "Não informado" })
            .replace("{USER_ADDRESS}", userAddress.ifBlank { "Não informado" })
            .replace("{USER_TARGET_AUDIENCE}", userTargetAudience.ifBlank { "Geral" })
            .replace("{PRODUTO}", produto.ifBlank { "Tópico principal" })
            .replace("{FOTOS}", descFotos.ifBlank { "Nenhuma descrição de imagem de referência fornecida." })
            .replace("{EXTRAS}", extra.ifBlank { "Sem informações contextuais adicionais." })
            .replace("{USER_LANGUAGE_TONE}", userLanguageTone.ifBlank { "Neutro" })
            .replace("{VIDEO_OBJECTIVE_INTRODUCTION}", VIDEO_OBJECTIVE_INTRODUCTION.ifBlank { "Apresentar o tema." })
            .replace("{VIDEO_OBJECTIVE_VIDEO}", VIDEO_OBJECTIVE_VIDEO.ifBlank { "Desenvolver o tema principal." })
            .replace("{VIDEO_OBJECTIVE_OUTCOME}", VIDEO_OBJECTIVE_OUTCOME.ifBlank { "Concluir e engajar." })
            .replace("{VIDEO_TIME_SECONDS}", VIDEO_TIME_SECONDS.ifBlank { "Não especificado" })
            .replace("{VOICE_GENERE}", VOICE_GENERE.ifBlank { "Não especificado" })
            .replace("{VOICE_EMOTION}", VOICE_GENERE.ifBlank { "VOICE_EMOTION" })
    }
}