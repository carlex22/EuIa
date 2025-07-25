// File: euia/prompts/ExtractInfoFromUrlPrompt.kt
package com.carlex.euia.prompts

/**
 * Cria um prompt para a Gemini extrair informações de pré-contexto de uma URL,
 * e sugerir conteúdo para um vídeo, adaptando as sugestões para um monólogo ou um diálogo.
 *
 * @property contentUrl A URL do conteúdo a ser analisado.
 * @property currentUserNameCompany Nome/Empresa atual do usuário.
 * @property currentUserProfessionSegment Profissão/Segmento atual do usuário.
 * @property currentUserAddress Endereço/Localização atual do usuário.
 * @property currentUserLanguageTone Tom de linguagem atual do usuário.
 * @property currentUserTargetAudience Público-alvo atual do usuário.
 * @property isChat Boolean que indica se a narrativa será um diálogo.
 */
class ExtractInfoFromUrlPrompt(
    private val contentUrl: String,
    private val currentUserNameCompany: String,
    private val currentUserProfessionSegment: String,
    private val currentUserAddress: String,
    private val currentUserLanguageTone: String,
    private val currentUserTargetAudience: String,
    private val isChat: Boolean // Novo parâmetro
) {

private val userInfoHint: String = buildString {
    appendLine("Client details for personalization:")
    if (currentUserNameCompany.isNotBlank()) appendLine("- Name/Company: $currentUserNameCompany")
    if (currentUserProfessionSegment.isNotBlank()) appendLine("- Profession/Industry: $currentUserProfessionSegment")
    if (currentUserAddress.isNotBlank()) appendLine("- Location: $currentUserAddress")
}.trim()

    val prompt: String

init {
    // --- BEGIN MODIFICATION: Modern engagement logic for creators ---
    val objectiveInstructions = if (isChat) {
        """
        *   **Adapt to a DIALOGUE.** Think like modern content creators: how would two characters hook the audience within 3 seconds?

        6.  "video_hook": String
            *   Suggest a powerful **opening line** in dialogue form to instantly grab attention.
            *   Think of what one character would say to make the viewer curious and stay. Examples:
                - "Wait — you haven’t seen this yet?"
                - "This thing might change your life..."
                - "Why is nobody talking about this?"

        7.  "video_objective_content": String
            *   Describe how the **conversation develops naturally**, keeping curiosity and energy alive.
            *   Who questions, who explains? What unexpected turns or playful tension keeps the viewer engaged?

        8.  "video_objective_outcome": String
            *   Suggest how the **conversation ends**, with a clever **call to action** or a **thought-provoking line** to spark comments or shares.
        """
    } else {
        """
        *   **Adapt to a SINGLE NARRATOR.** Think like a solo storyteller aiming to hook the viewer in under 3 seconds.

        6.  "video_hook": String
            *   Suggest a high-impact **first sentence** for the narrator to grab attention.
            *   Use surprise, curiosity, or emotional weight. Example:
                - "You’ve been doing this wrong your whole life..."
                - "No one told me this — but it works."
                - "Here’s why this matters more than you think."
            *   Max 18 tokens.

        7.  "video_objective_content": String
            *   Describe the **flow of the core content**: how it’s structured to maintain interest.
            *   Suggest mini-reveals, emotional storytelling, and logic layers to hold attention.

        8.  "video_objective_outcome": String
            *   Suggest a **modern ending**: bold CTA, a twist, a final question, or a relatable thought that makes the viewer want to interact (like, comment, share, explore). Max 25 tokens.
        """
    }
    // --- END MODIFICATION ---

    prompt = """
    You are a screenwriter who creates high-engagement videos for the internet. You've been hired to craft a short-form video.

    $userInfoHint

    Your job is to analyze a URL or theme to generate a **strategic and creative pre-context** for a video that will perform well on platforms like YouTube Shorts, TikTok, or Instagram Reels.

    Analyze the following URL to understand the content and build the foundation for your video creation:

    URL: "$contentUrl"

    Now tap into your secret mental notebook — your **creator playbook** — and follow the proven structure below.

    == STAGE 1: PHILOSOPHY → NARRATIVE ==
    Uncover the big idea:
    - What is the emotional or conceptual core of the story?
    - What angle or lens makes this different from other content?
    - How to turn abstract ideas into a relatable and visual micro-narrative?

    == STAGE 2: THEORY → PRACTICE ==
    Ground it in the real world:
    - How does this idea apply to everyday life or common pain points?
    - What specific examples or metaphors can be used?
    - Why should the audience care — what’s in it for them?

    == STAGE 3: CONVERSATION → STRUCTURE ==
    Shape the content arc:
    - What’s the emotional hook in the first 3 seconds?
    - How does the tension or curiosity build throughout?
    - What’s the high point or surprise moment?

    == STAGE 4: APPLICATION → IMPACT ==
    What outcome do we want?
    - What mindset shift or curiosity should remain after watching?
    - What action do we want viewers to take?
    - What makes this video memorable or worth sharing?

    ➤ Your response must be a SINGLE JSON OBJECT.
    ⚠️ Return only the JSON. No text, no markdown, no explanation.
    ⚠️ **All values must be written in Brazilian Portuguese (pt-BR).**
    
    Fields to generate:
    

    1.  "suggested_title": String  
        *   Catchy and relevant to the content. Strip technical jargon or unnecessary text.

    2.  "main_summary": String  
        *   Concise and clear explanation of the main idea behind the URL content.

    3.  "video_hook": String  
        *   First line of the video — it must **grab attention** fast.
        *   Use mystery, contradiction, or direct emotional connection.

    4.  "video_objective_content": String  
        *   Core content flow or dialogue structure. What’s revealed, and how.

    5.  "video_objective_outcome": String  
        *   Ending with impact — call to action, twist, or a bold final phrase.

    6.  "suggested_language_tone": String  
        *   Suggest tone style (e.g. bold, casual, inspiring). Max 3 tokens.

    7.  "suggested_target_audience": String  
        *   Who is the content made for? Max 6 tokens.

    $objectiveInstructions

    Expected JSON format:
    [{
      "suggested_title": "string",
      "main_summary": "string",
      "video_hook": "string",
      "video_objective_content": "string",
      "video_objective_outcome": "string",
      "suggested_language_tone": "string",
      "suggested_target_audience": "string"
    }]
    """.trimIndent()
}




}