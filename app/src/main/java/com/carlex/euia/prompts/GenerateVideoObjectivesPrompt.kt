package com.carlex.euia.prompts

/**
 * Cria um prompt para o Gemini sugerir objetivos para diferentes seções de um vídeo,
 * baseado em um título e descrição.
 *
 * @property titulo O título do produto/serviço.
 * @property descricao A descrição do produto/serviço.
 */
class GenerateVideoObjectivesPrompt(private val titulo: String, private val descricao: String) {

    /**
     * O prompt formatado para ser enviado ao modelo Gemini.
     */
    val prompt: String = """
        Com base no título e na descrição de um produto/serviço fornecidos abaixo, sugira objetivos concisos para as três seções principais de um vídeo promocional ou informativo sobre ele: Introdução, Vídeo (desenvolvimento principal) e Resultado (conclusão ou chamada para ação).

        Título: "${titulo.replace("\"", "\\\"")}"
        Descrição: "${descricao.replace("\"", "\\\"")}"

        Sua resposta DEVE ser um único objeto JSON com as seguintes chaves EXATAS e valores como strings:
        - "video_hook": "Seu texto sugerido para o objetivo da introdução aqui. esta fala deve represejtar uma cena de 3 a 5 segundos no maximo"
        - "video_objective_video": "Seu texto sugerido para o objetivo do desenvolvimento principal do vídeo"
        - "video_objective_outcome": "Seu texto sugerido para o desfecho do video, deve c9mpor 1 oy 2 cenas no tim ro video com no maximo 6 segundos"

        **Instruções Importantes:**
        1. Forneça sugestões claras e diretas para cada seção.
        2. Mantenha as sugestões relativamente curtas, idealmente uma frase ou duas para cada.
        3. Se o título ou a descrição forem insuficientes para gerar uma sugestão significativa para alguma seção, você pode retornar uma string como "Detalhes insuficientes para sugerir um objetivo claro para esta seção." para essa chave específica. NÃO omita nenhuma das três chaves ("video_hook", "video_objective_video", "video_objective_outcome") do objeto JSON.
        4. Certifique-se de que a saída seja APENAS o objeto JSON, sem nenhum texto adicional antes ou depois, e sem marcadores de bloco de código como ```json.

        Objeto JSON resultante:
    """.trimIndent()
}