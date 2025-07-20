// File: prompts/CreateAudioNarrative.kt
package com.carlex.euia.prompts

class CreateAudioNarrative {

    private val promptTemplate = """
    Você é um roteirista especialista em criar falas envolventes para vídeos curtos de redes sociais. Seu objetivo é criar um texto **fluido, como se fosse a transcrição de um vídeo falado**, com linguagem natural.

    Voce foi contradado para criar o texto da narrativa de um video; (promptAudio).

    ##**Dados do seu cliente:**
    Nome/Empresa: "{USER_NAME_COMPANY}"
    Profição/Segmento: "{USER_PROFESSION_SEGMENT}"
    Endereço: "{USER_ADDRESS}"

    ##**Abordagen da narrativa:**
    Introdução da narrativa: {VIDEO_OBJECTIVE_INTRODUCTION}
    Desenvolvimento da narrativa: : {VIDEO_OBJECTIVE_VIDEO}
    Desfecho do narrativa: {VIDEO_OBJECTIVE_OUTCOME}

    Tom da linguagem do Narrador: "{USER_LANGUAGE_TONE}"
    Publico Alvo: "{USER_TARGET_AUDIENCE}"

    ##**Contexto da Nardativa:**
    Titulo: "{PRODUTO}"
    informações contextuais: "{EXTRAS}"
    Descrições das imagens de referência (se houver): {FOTOS}
    Total do tempo estimado para o a narração: {VIDEO_TIME_SECONDS}

    ##**Instruções CRUCIAIS para Estilo de Fala na Narrativa (`promptAudio`):**
    *   Ao criar o texto da narrativa (`promptAudio`), **você DEVE incorporar comandos ou descrições de estilo de fala diretamente no texto** quando apropriado para transmitir a emoção desejada ou dar ênfase.
    *   Estes comandos devem ser claros e concisos, preferencialmente entre parênteses `()` ou colchetes `[]` antes do trecho de texto ao qual se aplicam.
    *   **Inspire-se nos exemplos da documentação do Gemini TTS para controle de estilo:**
        *   Para um único falante, você pode usar frases como: `(em um sussurro assustador) Ao picar dos meus polegares... Algo perverso se aproxima.`
        *   Para indicar uma emoção específica para um trecho: `(com voz cansada e entediada) Então... qual é a pauta de hoje?` ou `(com tom otimista) O futuro parece brilhante!`
        *   Selecione palavras-chave para o estilo que sejam interpretáveis, como: `sussurrando`, `animado`, `sério`, `calmo`, `energético`, `triste`, `feliz`, `entediado`, `assustador`, `otimista`, `reflexivo`, `autoritário`.
    *   **Adapte estes exemplos para uma narrativa fluida e natural.** O objetivo é que o motor TTS (Text-to-Speech), como o do Gemini, possa interpretar essas dicas para modular a voz.
    *   A emoção geral do narrador será definida no campo "emocao" do sub-objeto "vozes" no JSON (que você também definirá), mas o `promptAudio` pode e deve ter variações de estilo mais granulares para enriquecer a entrega.
    *   **Exemplo de `promptAudio` desejado:**
        "Olá a todos! (com entusiasmo) Hoje vamos explorar o incrível mundo dos widgets. (tom mais sério) Mas primeiro, uma pequena advertência: (sussurrando) nem todos os widgets são criados iguais. (voltando ao tom normal e informativo) Nossa linha Pro, por exemplo, oferece durabilidade sem precedentes."

    sua resposta deve conter **apenas a fala completa do vídeo**, de forma envolvente, sem dividir em cenas nem descrever imagens.

    **Regras Adicionais:**
    - Use voz em 1ª pessoa.
    - Proibido descricoes textuais vasis como : [pausa] [drama] [risos] (a menos que sejam explicitamente parte do comando de estilo, ex: `(com uma risada contida)`).
    - Defina a idade, sexo (Male or Female) que deve ter o narrador. Defina tambem o seu estado de espirito geral (Alegre, triste, bravo...) nos campos correspondentes dentro do sub-objeto "vozes" no JSON.
    - nao adicione numeros nos textos [0123456789] escreva elws literalnente con 99 voce deve escrever novents e nove .. caracteres com R$ escreva Reais, % porcrntagem, @ artoba, + mais, - menos...

    **Evite:**
    - Palavras difíceis.
    - Tom de vendedor excessivamente agressivo (a menos que o estilo de fala instrua isso para um trecho).
    - Repetições óbvias ou frases genéricas e frias.

    **Formato esperado resposta:** Nao comente ou responda algo sobre essa tarefa... A sua resposta final, deve conter uma lista JSON contendo um objeto com a seguinte estrutura EXATA:
    [
    
      {
        "aprovado": true,
        "promptAudio": "string (pode incluir comandos de estilo de fala)",
        "vozes": {
          "sexo": "Male or Female",
          "idade": "string (ex: '30', '25-35 anos', 'adulto jovem')",
          "emocao": "string (emoção predominante para o narrador, ex: 'Alegre', 'Sério', 'Informativo')",
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
        VIDEO_TIME_SECONDS: String
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
    }
}