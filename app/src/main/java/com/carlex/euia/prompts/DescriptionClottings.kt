package com.carlex.euia.prompts

public class DescriptionClottings(titulo: String, extras: String) {
    
    
    
    public var prompt = """
Analise todas as imagens,
localize e indentifique o seguinte objeto(s) nas fotos enviadas: $titulo
processe e tambem extraia todas as informações para completar os dados da lista:
Exclua da resposta final os pares com valores null ou n/a




Sua resposta final **deve conter apenas uma lista JSON**, no seguinte formato:
[
  {
    "identificacao_visual_peca.nome_modelo": "string"
  },
  {
    "identificacao_visual_peca.codigo_referencia_visual": "string"
  },
  {
    "identificacao_visual_peca.colecao": "string"
  },
  {
    "identificacao_visual_peca.linha_produto": "string"
  },
  {
    "identificacao_visual_peca.genero_pretendido_estilo_visual": "string"
  },
  {
    "identificacao_visual_peca.faixa_etaria_pretendida_estilo_visual": "string"
  },
  {
    "identificacao_visual_peca.designer": "string"
  },
  {
    "identificacao_visual_peca.referencia_visual_desenho_tecnico": "string (URL ou identificador)"
  },
  {
    "atributos_visuais_principais.cores_principais": "string"
  },
  {
    "atributos_visuais_principais.cores_secundarias": "string"
  },
  {
    "atributos_visuais_principais.estampas": "string"
  },
  {
    "atributos_visuais_principais.tipos_visuais_materiais_principais": "string"
  },
  {
    "atributos_visuais_principais.acabamentos_visuais_no_material_principal[Int].localizacao": "string"
  },
  {
    "atributos_visuais_principais.acabamentos_visuais_no_material_principal[Int].tipo_acabamento": "string"
  },
  {
    "atributos_visuais_principais.caimento_visual": "string"
  },
  {
    "atributos_visuais_principais.sensacao_textura_visual": "string"
  },
  {
    "atributos_visuais_principais.brilho_visual": "string"
  },
  {
    "atributos_visuais_principais.transparencia_visual": "string"
  },
  {
    "silhueta_caimento_visual.tipo_silhueta": "string"
  },
  {
    "silhueta_caimento_visual.tipo_caimento": "string"
  },
  {
    "silhueta_caimento_visual.volume": "string"
  },
  {
    "elementos_design_visiveis.tipo_decote": "string"
  },
  {
    "elementos_design_visiveis.tipo_manga": "string"
  },
  {
    "elementos_design_visiveis.tipo_bainha": "string"
  },
  {
    "elementos_design_visiveis.tipos_bolsos": "string"
  },
  {
    "elementos_design_visiveis.tipo_colarinho": "string"
  },
  {
    "elementos_design_visiveis.tipo_punho": "string"
  },
  {
    "elementos_design_visiveis.estilo_cintura": "string"
  },
  {
    "elementos_design_visiveis.caracteristicas_visiveis": "string"
  },
  {
    "detalhes_construcao_visiveis.tipos_costura_visiveis": "string"
  },
  {
    "detalhes_construcao_visiveis.detalhes_pesponto_visiveis[Int].localizacao": "string"
  },
  {
    "detalhes_construcao_visiveis.detalhes_pesponto_visiveis[Int].cor_linha": "string"
  },
  {
    "detalhes_construcao_visiveis.detalhes_pesponto_visiveis[Int].espessura_linha": "string"
  },
  {
    "detalhes_construcao_visiveis.detalhes_pesponto_visiveis[Int].efeito_visual": "string"
  },
  {
    "aviamentos_ferragens_visiveis.tipos": "string"
  },
  {
    "aviamentos_ferragens_visiveis.detalhes[Int].tipo": "string (da taxonomia de tipos)"
  },
  {
    "aviamentos_ferragens_visiveis.detalhes[Int].material_visual": "string"
  },
  {
    "aviamentos_ferragens_visiveis.detalhes[Int].acabamento_visual": "string"
  },
  {
    "aviamentos_ferragens_visiveis.detalhes[Int].cor": "string"
  },
  {
    "aviamentos_ferragens_visiveis.detalhes[Int].forma": "string"
  },
  {
    "aviamentos_ferragens_visiveis.detalhes[Int].dimensoes_tamanho_visual": "string"
  },
  {
    "aviamentos_ferragens_visiveis.detalhes[Int].localizacao_visual": "string"
  },
  {
    "detalhes_visuais_design_estrutural.bolsos_visuais[Int].tipo": "string"
  },
  {
    "detalhes_visuais_design_estrutural.bolsos_visuais[Int].localizacao_visual": "string"
  },
  {
    "detalhes_visuais_design_estrutural.bolsos_visuais[Int].forma_visual": "string"
  },
  {
    "detalhes_visuais_design_estrutural.bolsos_visuais[Int].medidas_visuais": "string"
  },
  {
    "detalhes_visuais_design_estrutural.bolsos_visuais[Int].cor": "string"
  },
  {
    "detalhes_visuais_design_estrutural.bolsos_visuais[Int].acabamento_visual": "string"
  },
  {
    "detalhes_visuais_design_estrutural.bolsos_visuais[Int].detalhes_fechamento_visual": "string"
  },
  {
    "detalhes_visuais_design_estrutural.bolsos_visuais[Int].observacoes": "string"
  },
  {
    "detalhes_visuais_design_estrutural.pregas_visuais[Int].tipo": "string"
  },
  {
    "detalhes_visuais_design_estrutural.pregas_visuais[Int].localizacao_visual": "string"
  },
  {
    "detalhes_visuais_design_estrutural.pregas_visuais[Int].profundidade_visual": "string"
  },
  {
    "detalhes_visuais_design_estrutural.pregas_visuais[Int].direcao_visual": "string"
  },
  {
    "detalhes_visuais_design_estrutural.pregas_visuais[Int].fixacao_visual": "string"
  },
  {
    "detalhes_visuais_design_estrutural.vinco_visual[Int].localizacao_visual": "string"
  },
  {
    "detalhes_visuais_design_estrutural.vinco_visual[Int].tipo": "string"
  },
  {
    "detalhes_visuais_design_estrutural.aviamentos_vieses_cordoes_visuais[Int].tipo": "string"
  },
  {
    "detalhes_visuais_design_estrutural.aviamentos_vieses_cordoes_visuais[Int].localizacao_visual": "string"
  },
  {
    "detalhes_visuais_design_estrutural.aviamentos_vieses_cordoes_visuais[Int].material_visual": "string"
  },
  {
    "detalhes_visuais_design_estrutural.aviamentos_vieses_cordoes_visuais[Int].medida_visual": "string"
  },
  {
    "detalhes_visuais_design_estrutural.aviamentos_vieses_cordoes_visuais[Int].cor": "string"
  },
  {
    "detalhes_visuais_design_estrutural.cos_visual.tipo": "string"
  },
  {
    "detalhes_visuais_design_estrutural.cos_visual.altura_visual": "string"
  },
  {
    "detalhes_visuais_design_estrutural.cos_visual.detalhes_visuais": "string"
  },
  {
    "detalhes_visuais_design_estrutural.golas_visuais.tipo": "string"
  },
  {
    "detalhes_visuais_design_estrutural.golas_visuais.forma_visual": "string"
  },
  {
    "detalhes_visuais_design_estrutural.golas_visuais.detalhes_visuais": "string"
  },
  {
    "detalhes_visuais_design_estrutural.punhos_visuais.tipo": "string"
  },
  {
    "detalhes_visuais_design_estrutural.punhos_visuais.forma_visual": "string"
  },
  {
    "detalhes_visuais_design_estrutural.punhos_visuais.detalhes_visuais": "string"
  },
  {
    "detalhes_visuais_design_estrutural.elementos_suporte_visuais[Int].tipo": "string"
  },
  {
    "detalhes_visuais_design_estrutural.elementos_suporte_visuais[Int].material_visual": "string"
  },
  {
    "detalhes_visuais_design_estrutural.elementos_suporte_visuais[Int].localizacao_visual": "string"
  },
  {
    "detalhes_visuais_design_estrutural.proporcao_visual": "string"
  },
  {
    "detalhes_visuais_design_estrutural.posicionamento_visual": "string"
  },
  {
    "bainhas_inferiores_visuais[Int].localizacao": "string (por exemplo, bainha da manga, bainha da peça, abertura da perna)"
  },
  {
    "bainhas_inferiores_visuais[Int].tipo": "string (taxonomia de tipos de bainha)"
  },
  {
    "bainhas_inferiores_visuais[Int].acabamento_visual": "string (por exemplo, pespontado, ponto invisível, borda overlocada, borda crua)"
  },
  {
    "bainhas_inferiores_visuais[Int].largura_visual": "string"
  },
  {
    "bainhas_inferiores_visuais[Int].forma_visual": "string (por exemplo, reto, curvo, assimétrico)"
  },
  {
    "bainhas_inferiores_visuais[Int].detalhes_visuais": "string (por exemplo, fenda, abertura, vista formatada)"
  },
  {
    "bainhas_inferiores_visuais[Int].observacoes": "string"
  },
  {
    "elasticos_visiveis[Int].localizacao": "string (por exemplo, cintura, punho, bainha)"
  },
  {
    "elasticos_visiveis[Int].tipo": "string (por exemplo, elástico exposto, elástico canalizado)"
  },
  {
    "elasticos_visiveis[Int].largura_visual": "string"
  },
  {
    "elasticos_visiveis[Int].cor": "string"
  },
  {
    "elasticos_visiveis[Int].textura_estampa_visual": "string"
  },
  {
    "elasticos_visiveis[Int].metodo_aplicacao_visual": "string (por exemplo, costurado diretamente, em um canal)"
  },
  {
    "elasticos_visiveis[Int].observacoes": "string"
  },
  {
    "fechamentos_visiveis[Int].localizacao": "string (por exemplo, centro da frente, costura lateral, punho)"
  },
  {
    "fechamentos_visiveis[Int].tipo": "string (taxonomia de tipos de fecho, por exemplo, zíper, carcela de botões, amarração, colchete)"
  },
  {
    "fechamentos_visiveis[Int].estilo_visual": "string (por exemplo, zíper exposto, carcela oculta, amarrações decorativas)"
  },
  {
    "fechamentos_visiveis[Int].detalhes_componentes_visuais": "string (referenciando ou descrevendo detalhes visuais de botões, puxadores de zíper, etc.)"
  },
  {
    "fechamentos_visiveis[Int].sobreposicao_visual": "string (por exemplo, esquerda sobre direita, largura da carcela de botões)"
  },
  {
    "fechamentos_visiveis[Int].observacoes": "string"
  },
  {
    "acabamento_superficie_visual.detalhes_decorativos_visuais[Int].tipo": "string"
  },
  {
    "acabamento_superficie_visual.detalhes_decorativos_visuais[Int].descricao_visual": "string"
  },
  {
    "acabamento_superficie_visual.detalhes_decorativos_visuais[Int].localizacao_visual": "string"
  },
  {
    "acabamento_superficie_visual.detalhes_decorativos_visuais[Int].tecnica_aplicacao_visual": "string"
  },
  {
    "acabamento_superficie_visual.processos_lavanderia_visuais": "string"
  },
  {
    "acabamento_superficie_visual.passadoria_visual": "string"
  },
  {
    "acabamento_superficie_visual.acabamento_borda_visual[Int].elemento": "string"
  },
  {
    "acabamento_superficie_visual.acabamento_borda_visual[Int].tipo_acabamento": "string"
  },
  {
    "elementos_funcionais_visuais.elementos_refletivos": "string (localizacao, forma, cor)"
  },
  {
    "inclusividade_adaptabilidade_visual.usabilidade_adaptativa_visual": "string"
  },
  {
    "inclusividade_adaptabilidade_visual.ajustes_visuais_necessidades_especificas": "string"
  },
  {
    "inclusividade_adaptabilidade_visual.ampliacao_tamanho_visual.faixa_tamanho": "string"
  },
  {
    "inclusividade_adaptabilidade_visual.ampliacao_tamanho_visual.adaptacoes_visuais_proporcionais": "string"
  },
  {
    "detalhes_visuais_especificos_categoria_peca.categoria": "string"
  },
  {
    "detalhes_visuais_especificos_categoria_peca.subcategoria": "string"
  },
  {
    "detalhes_visuais_especificos_categoria_peca.pistas_visuais": "string"
  },
  {
    "elementos_visuais_identidade_marca.branding_visual_discreto.logo_em_relevo_sutil_visual": "string"
  },
  {
    "elementos_visuais_identidade_marca.branding_visual_discreto.estampa_visual_marca_registrada": "string"
  },
  {
    "elementos_visuais_identidade_marca.detalhes_assinatura_visual": "string"
  },
  {
    "comportamento_visual_dinamico.efeito_movimento_tecido": "string"
  },
  {
    "comportamento_visual_dinamico.pontos_articulacao_visuais": "string"
  },
  {
    "adaptacoes_ambientais_visuais.indicadores_resistencia_agua_visuais": "string"
  },
  {
    "adaptacoes_ambientais_visuais.pistas_visuais_protecao_uv": "string"
  },
  {
    "hierarquia_visual.foco_primario_visual": "string"
  },
  {
    "hierarquia_visual.elementos_secundarios_visuais": "string"
  },
  {
    "layout_posicionamento_visual.posicionamentos_elemento[Int].tipo_elemento": "string"
  },
  {
    "layout_posicionamento_visual.posicionamentos_elemento[Int].descricao_localizacao_visual": "string"
  },
  {
    "layout_posicionamento_visual.posicionamentos_elemento[Int].pontos_referencia_posicionamento": "string"
  },
  {
    "layout_posicionamento_visual.posicionamentos_elemento[Int].distancia_referencia": "string"
  },
  {
    "layout_posicionamento_visual.posicionamentos_elemento[Int].relacao_visual_adjacente": "string"
  },
  {
    "layout_posicionamento_visual.detalhes_proporcao_visual": "string"
  },
  {
    "variacoes_visuais[Int].nome_variacao": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.cores_principais": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.cores_secundarias": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.estampas": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.acabamentos_visuais_no_material_principal": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.substituicoes_aviamentos_ferragens_visiveis[Int].tipo_aviamento": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.substituicoes_aviamentos_ferragens_visiveis[Int].substituicao_propriedades_visuais.acabamento": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.substituicoes_aviamentos_ferragens_visiveis[Int].substituicao_propriedades_visuais.cor": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.substituicoes_aviamentos_ferragens_visiveis[Int].substituicao_propriedades_visuais.forma": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.tipo_silhueta": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.tipo_caimento": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.volume": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.tipo_decote": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.tipo_manga": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.tipo_bainha": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.tipos_bolsos": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.tipo_colarinho": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.tipo_punho": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.estilo_cintura": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.tipos_fechamento_visiveis": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.caracteristicas_visiveis": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.tipos_costura_visiveis": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.detalhes_pesponto_visiveis": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.layout_posicionamento_visual": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.partes_visuais_modulares_customizaveis": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.impacto_visual_estrutura_subjacente": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.comportamento_visual_dinamico": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.adaptacoes_ambientais_visuais": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.hierarquia_visual": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.detalhes_visuais_especificos_categoria_peca": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.inclusividade_adaptabilidade_visual": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.pistas_visuais_sustentabilidade": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.visuais_tecnologia_integrada": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.pistas_visuais_ciclo_vida": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.elementos_visuais_multissensoriais": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.pistas_visuais_seguranca_crianca": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.contexto_sazonalidade_visual": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.elementos_visuais_identidade_marca": "string"
  },
  {
    "variacoes_visuais[Int].substituicoes_atributo_visual.referencias_visuais_culturais_historicas": "string"
  },
  {
    "variacoes_visuais[Int].visual_attribute_overrides.visual_bottom_hems": "string"
  },
  {
    "variacoes_visuais[Int].visual_attribute_overrides.visible_elastics": "string"
  },
  {
    "variacoes_visuais[Int].visual_attribute_overrides.visible_closures": "string"
  },
  {
    "impacto_visual_estrutura_subjacente[Int].referencia_elemento_subjacente": "string"
  },
  {
    "impacto_visual_estrutura_subjacente[Int].efeito_visual_em": "string"
  },
  {
    "impacto_visual_estrutura_subjacente[Int].descricao_efeito_visual": "string"
  }
]

Após completar o json
processar o texto abaixo, extrair informacoes sobre o objeto: $titulo 
incluir essas informacoes no json da resposta final

$extras

"""

}
