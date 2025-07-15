package com.carlex.euia.prompts

public class DescriptionClottings(titulo: String, extras: String) {
    
    
    
    public var prompt = """
Analise cuidadosamente a imagem fornecida e extraia todas as informações visuais, textuais e contextuais presentes.

Sua tarefa é identificar e descrever elementos como contexto, personagens, objetos, ações, cenario, cores, expressões, e qualquer outro detalhe relevante para o contexto global da lista das imagens 

O resultado deve ser formatado exclusivamente como um objeto JSON plano com pares chave-valor, sem listas, vetores ou objetos aninhados.

Use nomes de chaves descritivos e em snake_case. Tudo deve estar representado em chave: valor. Não agrupe nem compacte campos similares. Cada item visível ou inferido deve ter sua própria entrada única        

"""

}
