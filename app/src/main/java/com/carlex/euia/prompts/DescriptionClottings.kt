package com.carlex.euia.prompts

public class DescriptionClottings(titulo: String, extras: String) {
    
    
    
    public var prompt = """
Analise cuidadosamente as imagens fornecidas e extraia todas as informações (visuais, textuais e contextuais presentes) referente a esse tema: $titulo
Sua tarefa é identificar e descrever elementos como contexto, personagens, objetos, ações, cenario, cores, expressões, e qualquer outro detalhe relevante para o contexto global da lista das imagens 
O resultado deve ser formatado exclusivamente como um objeto JSON plano com pares chave-valor, sem listas, vetores ou objetos aninhados.

Use nomes de chaves descritivos e em snake_case. Tudo deve estar representado em chave: valor. Não agrupe nem compacte campos similares. Cada item visível ou inferido deve ter sua própria entrada única        

formato de resposta esperada:
[{
    "ChaveString": "valor",
    "ChaveNumetica": 0.0,
    "ChaveLogicw": true,
    "ChaveMatrix": ("A","B")
    ...
 }]
    

"""

}
