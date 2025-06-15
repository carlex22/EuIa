package com.carlex.euia.data

public class  DataVideo(){
    public var prompt = """
{
  "status": {
    "apresentacao": false,
    "briefing": false,
    "imagens": false,
    "narrativa": false,
    "cenas": false,
    "render": false
  },
  "dados": {
    "briefing": {
      "empresa": "",
      "nome": "",
      "ramo": "",
      "endereco": "",
      "tom": "",
      "alvo": [],
      "tema": "",
      "extras": []
    },
    "imagens": {
      "caracteristicas": "",
      "referencias": [
        {
          "caminho": "",
          "novo_prompt": ""
        }
      ]
    },
    "narrativa": {
      "fala": "",
      "sexo": "",
      "idade": "",
      "emocao": ""
    },
    "cenas": [
      {
        "take": 1,
        "tempo_inicio": 0.0,
        "tempo_fim": 5.0,
        "prompt_para_imagem": "",
        "exibir_produto": false,
        "foto": ""
      }
    ],
    "render": [
      {
        "cena_id": 1,
        "aprovado": 0,
        "sugestao": ""
      }
    ]
  }
}
    """
}


data class RenderData(
    val CENAID: Int,
    val APROVADO: Int,
    val SUGESTAO: String
)


data class CenaData(
    val TAKE: String,
    val TEMPO_INICIO: Double,
    val TEMPO_FIM: Double,
    val PROMPT_PARA_IMAGEM: String,
    val EXIBIR_PRODUTO: Boolean,
    val FOTO: String
)


data class NarrativaData(
    val FALA: String,
    val SEXO: String, // "masculino" ou "feminino"
    val IDADE: String, // faixa etária
    val EMOCAO: String // sentimento dominante
)

data class ImagemData(
    val CARACTERISTICAS: String,
    val IMAGENS: ImagemInfo
)

data class ImagemInfo(
    val CAMINHO: String,
    val NOVO_PROMPT: String
)

data class BriefingData(
    val EMPRESA: String,
    val NOME: String,
    val RAMO: String,
    val ENDERECO: String,
    val TOM: String,
    val ALVO: List<String>,
    val TEMA: String,
    val EXTRAS: List<String>
)



