package com.carlex.euia.data

import kotlinx.serialization.Serializable

/**
 * Define a animação para um único asset dentro de uma cena.
 * As propriedades são declaradas como nulas para dar flexibilidade,
 * permitindo que a IA omita campos que não são relevantes para um tipo específico de asset
 * (por exemplo, um efeito sonoro não terá coordenadas 'x' ou 'z').
 *
 * @param x1 Posição X inicial normalizada (0.0 a 1.0).
 * @param y1 Posição Y inicial normalizada (0.0 a 1.0).
 * @param z1 Nível de zoom inicial (1.0 = sem zoom, >1.0 = mais zoom).
 * @param x2 Posição X final normalizada.
 * @param y2 Posição Y final normalizada.
 * @param z2 Nível de zoom final.
 * @param curva O tipo de interpolação da animação (ex: "linear", "ease_in_out", "bounce_end").
 * @param tempo_inicio_anim O tempo em segundos (relativo ao início da cena) em que a animação começa.
 * @param tempo_fim_anim O tempo em segundos em que a animação termina. Opcional para sons instantâneos.
 * @param opacidade_inicio Opacidade inicial do asset (0.0 = transparente, 1.0 = opaco).
 * @param opacidade_fim Opacidade final do asset.
 * @param volume Volume do efeito sonoro (0.0 a 1.0). Relevante apenas para `tipo = "efeito_sonoro"`.
 */
@Serializable
data class AssetAnimation(
    val x1: Double? = null,
    val y1: Double? = null,
    val z1: Double? = null,
    val x2: Double? = null,
    val y2: Double? = null,
    val z2: Double? = null,
    val curva: String? = null,
    val tempo_inicio_anim: Double,
    val tempo_fim_anim: Double? = null,
    val opacidade_inicio: Double? = null,
    val opacidade_fim: Double? = null,
    val volume: Double? = null
)

/**
 * Representa um único objeto (visual ou sonoro) que compõe uma cena.
 * Uma cena é composta por uma lista destes assets.
 *
 * @param id_asset Um identificador único para este asset específico.
 * @param tipo O tipo de asset, que define como ele deve ser renderizado.
 *             Valores esperados: "imagem_fundo", "overlay_png", "overlay_texto", "efeito_sonoro".
 * @param path Para o 'imagem_fundo', é o caminho original do arquivo. Para efeitos, é um prompt descritivo
 *             para que o sistema possa gerar ou buscar o recurso posteriormente.
 * @param camada A ordem de empilhamento visual (z-index). 0 é o fundo. Valores maiores ficam na frente.
 *                -1 é usado para assets não-visuais como áudio.
 * @param animacao O objeto que define como este asset se comporta ao longo do tempo.
 */
@Serializable
data class SceneAsset(
    val id_asset: String,
    val tipo: String,
    val path: String,
    val camada: Int,
    val animacao: AssetAnimation
)

/**
 * A estrutura completa para uma única cena de pós-produção.
 * Ela contém todas as informações necessárias para que o FFmpeg renderize a cena com todos os seus efeitos.
 *
 * @param id_cena O identificador único da cena, que deve corresponder ao ID da `SceneLinkData` original para referência.
 * @param assets_da_cena Uma lista de todos os assets (imagem de fundo, overlays, sons) que compõem esta cena.
 */
@Serializable
data class PostProductionScene(
    val id_cena: String,
    val assets_da_cena: List<SceneAsset>
)