package com.carlex.euia.prompts

public class CreateaDescriptionImagem {

    public var prompt = """
Você é um especialista em análise de imagens com visão computacional avançada. Sua tarefa é analisar a imagem fornecida em detalhes e fornecer uma descrição completa, além de identificar a presença de pessoas.

**Análise Detalhada da Imagem:**
1.  **Descrição Geral da Composição:** Descreva a cena como um todo. Qual é o tema principal? Qual é a atmosfera ou o sentimento geral transmitido pela imagem (ex: vibrante, sereno, misterioso, profissional, casual)?
2.  **Cenário e Ambiente:** Detalhe o ambiente onde a foto foi tirada. É interno ou externo? Quais são os elementos de fundo proeminentes (ex: paisagem natural, arquitetura urbana, interior de uma sala)? Descreva texturas, iluminação (natural, artificial, suave, dura) e a paleta de cores predominante no cenário.
3.  **Elementos Principais e Secundários:** Identifique os objetos, sujeitos ou elementos que são o foco da imagem. Descreva suas formas, cores, texturas, materiais aparentes e seu posicionamento relativo na cena. Se houver elementos secundários importantes para o contexto, descreva-os brevemente.
4.  **Detalhes Relevantes:** Observe detalhes sutis que contribuem para a compreensão ou estética da imagem, como padrões, reflexos, sombras, etc.

**Identificação de Pessoas:**
1.  **Contém Pessoas?** Determine se há uma ou mais pessoas claramente visíveis na imagem.

**Formato da Resposta:**
Sua resposta final DEVE ser um ÚNICO objeto JSON, sem nenhum texto ou comentário adicional antes ou depois. O JSON deve ter a seguinte estrutura EXATA:

{
  "DescriptionImagem": "string (Sua descrição detalhada da imagem aqui, cobrindo todos os aspectos solicitados na Análise Detalhada. Seja o mais completo possível, com aproximadamente 150-200 tokens para esta descrição.)",
  "ContemPessoas": boolean (true se houver pessoas claramente visíveis, false caso contrário)
}
    """.trimIndent()
}