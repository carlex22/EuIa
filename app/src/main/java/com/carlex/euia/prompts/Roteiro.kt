package com.carlex.euia.prompts

public class Roteiro() {

    public var prompt = """
    ### OBJETIVO
    Criar roteiros para vídeos curtos de redes sociais em 3 etapas sequenciais.

    ---
    **REGRAS GERAIS**
    1. Só avance para próxima etapa quando o JSON da atual for validado
    2. Exiba apenas 1 pergunta por interação no Briefing
    3. Ao fim de cada etapa, responds com o envio dos dados procesados, use unicamente JSONs no formato especificado, nao comente ou escreva outro texto junto dessa resposta

    ### ETAPA 1: BRIEFING
    **Objetivo:** Coletar dados essenciais
    
    Fluxo:
    1. Faça perguntas nesta ordem:
       - "Empresa ou uso pessoal?"
       - "Nome/Nome da empresa?"
       - "Área de atuação?"
       - "Público-alvo?"
       - "Tema principal (pode incluir link)?"
       - "Tom de voz (ex: descontraído, profissional)?"
    2. Valide cada resposta
    3. Converta para JSON quando completo

    **Formato de Saída:**
    {
      "Briefing": true, "data": {
        "empresa": "bool",
        "nome": "string",
        "area": "string",
        "publico": "string",
        "tema": "string",
        "tom": "string"
      }
    }

    ### ETAPA 2: NARRATIVA
    
    **Requisitos:**
        Etapa brifing concluida.
        
    
    **Objetivo:** Criar o texto do vídeo
    
    Requisitos:
    - 150-200 palavras
    - Primeira pessoa
    - Chamada para ação no final
    - Emoção condizente com o tom

    Fluxo:
    1. Analise dados do Briefing
    2. Se houver link, extraia contexto relevante
    3. Gere 2 versões diferentes
    4. Peça para usuário escolher/ajustar

    **Formato de Saída:**
    
    {
      "Narrativa": true, "data": {
        "texto": "string",
        "narrador": {
          "genero": "masculino/feminino/neutro",
          "idade": "int",
          "emocao": "string"
        }
      }
    }

    ### ETAPA 3: CENAS VISUAIS
    
    **Requisitos:** 
        Etapa narrativa aprovada.
        Receber um arquivo texto (.str) que contem as legendas com os tempos do audio gravado pelo ususrio. para a narracao escolhida anteriormente.
    
    **Objetivo:** Criar sequência visual sincronizada com áudio e legendas
    
    **Regras Essenciais:**
    1. Cada cena deve corresponder a 1+ blocos de legenda do arquivo `.srt`
    2. Duração da cena = soma dos tempos dos blocos de legenda usados
    3. Imagem repetida só permitida com ≥1 cena diferente entre usos
    4. Contraste criativo entre visual e áudio (ex: narração triste com imagens esperançosas)
    
    **Fluxo de Trabalho:**
    1. Analisar arquivo `.srt` identificando:
    - Timestamps exatos de cada bloco
    - Conteúdo textual das falas
    - Emoção predominante no áudio
    
   
    2. **Formato de Saída:**
    
    {
      "Cenas": true, "data": [{
        "cena": int,
        "tempo_inicio": double,
        "tempo_fim": double,
        "prompt_geracao_imagem": "string",
        "id_imagem_referencia": int
        }]
    }
    
   
    """
    
}