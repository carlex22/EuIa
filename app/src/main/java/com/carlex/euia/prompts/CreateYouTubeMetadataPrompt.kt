// File: euia/prompts/CreateYouTubeMetadataPrompt.kt
package com.carlex.euia.prompts

/**
 * Cria um prompt altamente especializado para instruir a IA a agir como um especialista em marketing do YouTube.
 * O objetivo é gerar todos os metadados necessários para publicar um vídeo, incluindo um prompt criativo
 * para gerar uma imagem de thumbnail viral.
 *
 * @param videoNarrative O roteiro completo ou a transcrição do áudio do vídeo. Este é o contexto principal.
 * @param originalTitle O título de trabalho ou o tema principal do vídeo.
 * @param visualStyleDescription Uma breve descrição do estilo visual do vídeo (ex: "cinematográfico e sombrio", "vlog diurno e brilhante", "animação 2D minimalista"). Isso ajuda a IA a criar um prompt de thumbnail mais coeso.
 */
class CreateYouTubeMetadataPrompt(
    private val videoNarrative: String,
    private val originalTitle: String,
    private val visualStyleDescription: String
) {
    val prompt: String

    init {
        // Constrói o prompt final usando as informações fornecidas no construtor.
        prompt = """
        Você é um especialista em marketing digital e SEO para o YouTube, com um talento especial para criar conteúdo viral. Sua missão é analisar a narrativa e o contexto de um vídeo e criar os metadados perfeitos para publicação, incluindo um prompt para gerar uma thumbnail irresistível.

        **CONTEXTO DO VÍDEO PARA ANÁLISE:**
        - **Título Original/Tema:** "$originalTitle"
        - **Narrativa Completa (Roteiro):** "$videoNarrative"
        - **Estilo Visual Geral:** "$visualStyleDescription"

        **SUA TAREFA:**
        Com base no contexto fornecido, gere um objeto JSON com a seguinte estrutura e conteúdo:

        1.  **"title"**: Crie um título OTIMIZADO para SEO e cliques (máximo de 70 caracteres). Deve ser cativante, claro e conter as palavras-chave mais relevantes para buscas no YouTube. Pense em como despertar curiosidade.
        
        2.  **"description"**: Crie uma descrição detalhada e envolvente. Comece com 2-3 frases que resumem o vídeo e prendem a atenção do espectador, repetindo as palavras-chave do título naturalmente. Depois, se a narrativa permitir, adicione mais detalhes, expandindo os pontos principais. **Finalize a descrição com uma seção de 3 a 5 hashtags relevantes.**
        
        3.  **"hashtags"**: Forneça uma string única contendo de 3 a 5 das hashtags mais importantes, separadas por espaço (ex: "IA, MarketingDigital, Automacao"). Estas são as hashtags que aparecerão acima do título do vídeo.
        
        4.  **"thumbnail_prompt"**: Esta é a sua obra de arte. Crie um prompt de texto para um gerador de imagens de IA (como Midjourney ou DALL-E) para criar a thumbnail perfeita para este vídeo. O prompt deve ser:
            - **Visualmente Impactante:** Descreva uma cena que gere uma forte emoção (curiosidade, surpresa, urgência) e que se destaque.
            - **Legível em Miniatura:** A composição deve ser simples e clara o suficiente para ser entendida em um tamanho pequeno na tela de um celular. Evite excesso de texto ou elementos confusos.
            - **Relevante:** A imagem deve representar visualmente o núcleo do vídeo, a pergunta principal ou o resultado mais chocante.
            - **Detalhado e Artístico:** Use uma linguagem rica e termos técnicos de arte para guiar a IA. Ex: "fotografia cinematográfica", "hyper-realistic", "iluminação de estúdio dramática", "cores vibrantes e contrastantes", "close-up emocional no rosto", "renderização em 8K", "arte digital épica".

        **REGRAS CRÍTICAS:**
        - Sua resposta deve ser APENAS o objeto JSON, sem nenhum texto, explicação ou comentário adicional.

        **EXEMPLO DE RESPOSTA JSON:**
        [{
          "title": "A IA pode prever o futuro? O resultado vai te chocar!",
          "description": "Será que a inteligência artificial pode realmente prever o futuro? Neste vídeo, mergulhamos fundo na tecnologia e nos dados para descobrir a verdade surpreendente por trás dos algoritmos preditivos. Prepare-se para mudar sua perspectiva! IA, Futuro, Tecnologia, InteligenciaArtificial, Previsoes",
          "hashtags": "IA, Futuro, Tecnologia",
          "thumbnail_prompt": "Fotografia cinematográfica de um robô humanoide com acabamento cromado olhando intensamente para uma bola de cristal brilhante. Dentro da bola de cristal, imagens caóticas do futuro se formam como um borrão de luz. O robô tem uma expressão de surpresa em seu rosto metálico. Fundo escuro com linhas de dados em neon azul e roxo passando rapidamente. Cores vibrantes e contrastantes, hyper-realistic, 8K."
        }]
        """.trimIndent()
    }
}