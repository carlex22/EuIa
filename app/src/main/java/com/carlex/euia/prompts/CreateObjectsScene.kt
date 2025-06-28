package com.carlex.euia.prompts

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Data class para serializar as informações de entrada de cada cena de forma limpa,
 * para serem inseridas diretamente no corpo do prompt.
 */
@Serializable
data class CenaInputInfo(
    val id_cena: String,
    val texto_narracao_especifico: String,
    val duracao_segundos: Double,
    val largura_imagem_px: Int,
    val altura_imagem_px: Int
)

/**
 * Constrói um prompt massivo e completo para a IA "Mestre Ilusionista" analisar UMA ÚNICA cena
 * e gerar seu roteiro de pós-produção em um JSON detalhado.
 *
 * @param cenaInfo O objeto com todos os dados da cena a ser analisada.
 * @param textoCompletoNarrativa O roteiro completo do vídeo para dar contexto geral à IA.
 * @param larguraFrame A largura do frame final do vídeo para guiar os cálculos de animação.
 * @param alturaFrame A altura do frame final do vídeo.
 */
class CreateObjectsForSingleScene(
    private val cenaInfo: CenaInputInfo,
    private val textoCompletoNarrativa: String,
    private val larguraFrame: Int,
    private val alturaFrame: Int
) {

    // Instância do codificador JSON para formatar o objeto de entrada da cena.
    private val jsonEncoder = Json { prettyPrint = true; encodeDefaults = true }

    // Propriedade pública que contém a string final e completa do prompt.
    val prompt: String

    init {
        // Formata o objeto de entrada da cena como uma string JSON para incluir no prompt.
        val cenaInfoJson = jsonEncoder.encodeToString(cenaInfo)

        // Constrói o prompt final usando templates de string do Kotlin.
        prompt = """
        **MISSÃO:** Agir como um "Mestre Ilusionista" - um diretor de pós-produção e VFX de elite. Sua tarefa é analisar **UMA ÚNICA CENA** e gerar seu roteiro técnico de pós-produção em formato JSON.

        ---
        **DOSSIÊ DO PROJETO**
        ---

        **1. NARRATIVA COMPLETA (CONTEXTO GERAL):**
        "${textoCompletoNarrativa.replace("\"", "'")}"

        **2. ESPECIFICAÇÕES TÉCNICAS DO VÍDEO FINAL:**
        - **Dimensões do Frame:** ${larguraFrame}x${alturaFrame} pixels.
        - **Regra de Ouro:** Nenhuma animação pode resultar em bordas pretas. O zoom da imagem de fundo deve sempre preencher o frame.

        **3. DADOS DA CENA ATUAL PARA PROCESSAR:**
        ```json
        $cenaInfoJson
        ```

        ---
        **DIRETIVAS DE PÓS-PRODUÇÃO (SEU TRABALHO):**
        ---

        Para a cena acima, execute o seguinte processo criativo-técnico:

        **A. DIREÇÃO DE CÂMERA (ASSET DE FUNDO - OBRIGATÓRIO):**
        - Analise a imagem (cujas dimensões são ${cenaInfo.largura_imagem_px}x${cenaInfo.altura_imagem_px}) e a narração ("${cenaInfo.texto_narracao_especifico}").
        - Identifique o **Ponto Focal** mais relevante na imagem que se conecta à narração.
        - Crie uma animação de câmera (`zoompan`) para o asset "imagem_fundo". O movimento deve guiar o olhar do espectador suavemente até o Ponto Focal ao final da duração da cena.
        - Calcule os valores para `x1, y1, z1` (início) e `x2, y2, z2` (fim). O zoom (`z`) deve ser o mínimo necessário para preencher o frame de ${larguraFrame}x${alturaFrame}.
        - Defina uma `curva` de animação ('linear' ou 'ease_in_out').

        **B. ILUSIONISMO E EFEITOS (ASSETS OPCIONAIS):**
        - Avalie a emoção e o propósito da cena. É uma piada? Uma revelação? Um momento de suspense?
        - **REGRAS:** Seja minimalista. Adicione no máximo **UM** overlay visual e **UM** efeito sonoro, e apenas se for muito eficaz.
        - Se decidir por um efeito:
            - **`tipo`**: Defina como "overlay_png", "overlay_texto", ou "efeito_sonoro".
            - **`path`**: **ESSENCIAL:** Crie um **prompt descritivo** para o asset a ser gerado (ex: "emoji de gato sorrindo com óculos escuros", "som de engrenagens girando rápido").
            - **`animacao`**: Projete a animação completa, respeitando a `duracao_segundos` da cena.

        ---
        **FORMATO DE SAÍDA OBRIGATÓRIO:**
        ---
        Sua resposta deve ser **exclusivamente um objeto JSON** representando esta única cena processada, com a `id_cena` e a lista `assets_da_cena`. O `path` do asset de fundo deve ser o caminho da imagem original da cena.

        **Exemplo de Resposta Final:**
        ```json
        {
          "id_cena": "${cenaInfo.id_cena}",
          "assets_da_cena": [
            {
              "id_asset": "fundo_${cenaInfo.id_cena}",
              "tipo": "imagem_fundo",
              "path": "caminho_da_imagem_da_cena.jpg",
              "camada": 0,
              "animacao": { "x1": 0.1, "y1": 0.1, "z1": 1.2, "x2": 0.6, "y2": 0.4, "z2": 1.4, "curva": "ease_in_out", "tempo_inicio_anim": 0.0, "tempo_fim_anim": ${cenaInfo.duracao_segundos}, "opacidade_inicio": 1.0, "opacidade_fim": 1.0 }
            }
          ]
        }
        ```
        """.trimIndent()
    }
}