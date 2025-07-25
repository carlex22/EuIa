// File: prompts/CreateAudioNarrative.kt
package com.carlex.euia.prompts

class CreateAudioNarrative {

    private val promptTemplate = """
You've been hired to create the narrative text for a video; promptAudio.

## **Project Details:**
- **Name/Company:** "{USER_NAME_COMPANY}"
- **Field of Business:** "{USER_PROFESSION_SEGMENT}"
- **Target Audience:** "{USER_TARGET_AUDIENCE}"
- **General Language Tone:** {USER_LANGUAGE_TONE}
- **Central Video Theme:** "{PRODUTO}"
- **Context Information:** "{EXTRAS}"
- **Image Descriptions:** {FOTOS}
- **Estimated Maximum Running Time:** {VIDEO_TIME_SECONDS} seconds

Important: Pay attention to the narrator and their emotion and intonation...
The narrator is: {VOICE_EMOTION}

## **Narrative Approach:**
Introduction Narrative: {VIDEO_OBJECTIVE_INTRODUCTION}
*Unless otherwise required in the Narrative Introduction:
Start by talking about a problem, difficulty, challenge, struggle, battle... something that the context of the video aims to solve.
Narrative Development: {VIDEO_OBJECTIVE_VIDEO}
Narrative Outcome: {VIDEO_OBJECTIVE_OUTCOME}

BEFORE STARTING YOUR TASK, let's review what you learned in college and in your daily life creating video scripts.
Let's think about how you, a genius master, prepare yourself by following your REASONING written down in your personal secret diary. Your basic secret recipe for success, its seasoning, and its form more guarded than Coca-Cola's

== STAGE 1: PHILOSOPHY → NARRATIVE ==
Analyze the conceptual essence of the project:
- What is the central insight driving this narrative?
- What unique perspective is being presented?
- How can abstract concepts be transformed into a tangible story?
- What is the "big idea" behind the content?

== STAGE 2: THEORY → PRACTICE ==
Connect concepts with real-world application:
- How do concepts manifest in everyday life?
- What concrete examples illustrate the theory?
- What are the practical implications for the audience?
- How can you turn the abstract into actionable?

== STAGE 3: CONVERSATION → STRUCTURE ==
Transform dialogue into a structured script:
- What is the strongest emotional hook?
- How can you create a logical and engaging progression? - What connection points resonate with the audience?
- How to balance information and entertainment?

== STEP 4: APPLICATION → IMPACT ==
Define the desired outcome:
- What shift in perspective do we hope for?
- What specific action should the audience take?
- How to measure the success of the narrative?
- What is the message's legacy?

## **Narrative Structure:**
Build the audio prompt following this 7-step structure for maximum impact:

1. **The Hook:** Always start with positivity and a lot of empathy, be quick and to the point with a direct, counterintuitive question or a surprising fact about the "{PRODUCT}." The goal is to generate immediate curiosity. (the hook)
2. **Quick Engagement:** Next, create a call to action that is easy and relatable. Direct answer about the "{PRODUCT}" (action)
3. **The Direct Answer:** Answer the hook question clearly and objectively.
4. **The Main Explanation:** Develop the reason for the answer. Use simple analogies to explain the central concept, using contextual information.
5. **The Point of Connection, Pain, or Humor:** Create a moment of empathy. Relate the topic to a common frustration, an everyday problem, or a culturally relevant joke that the "{USER_TARGET_AUDIENCE}" would understand.
6. **The Moral of the Story:** Summarize the main message in a single, powerful, memorable sentence. This is the main takeaway the viewer should take away from the video.
7. **Final Call to Action:** Use the closing objective to guide the viewer. Objective: "{VIDEO_OBJECTIVE_OUTCOME}".

DEFINE A SPECIFIC OUTCOME:
1. What change in perspective are we hoping for? 2. What concrete action should the audience take?
3. How to measure the success of the narrative?
4. What is the message's legacy?

SUCCESS METRICS:
- Engagement: Likes, comments, shares, sales, engagement, collaboration, publicity, journalism...
- Action: Use of the tools mentioned
- Transformation: Change in language around the topic

TURN DIALOGUE INTO A SCRIPT:
1. What counterintuitive question opens the narrative?
2. How to create a progression that keeps attention?
3. Where to insert moments of emotional connection?
4. VERY IMPORTANT... How to balance information, advertising, and propaganda with entertainment and information of the highest quality?

Audio prompt should contain a paralinguistic description at the beginning of the text, describing:
(the character)
(their action)
(the setting)
(their emotion)
(their intention)
Example: `[`John records a video while walking in a forest, confident and joyful, talking about what joy is:`]`


(Attention: paralinguistic descriptions must be in en-US language
and the plain text of the transcription of what the narrator will say
must be in pt-Br language)

(**Attention:**)
(Audio prompt should be used as a prompt for a model to generate audio from the text)
(Audio prompt will be used as a prompt for a model to generate audio from the text)
(Audio prompt must contain the paringuistic elements [expressions, emotions, sound, etc.], as well as the plain text of the speech...)
(!= You cannot insert punctuation or commas within the paringuistic descriptions remove `,` and `.` ; they must be as short and objective as possible.)
(All paringuistic elements must follow the rule: they must be enclosed in the prompt between brackets `[`string`]`; this rule is mandatory.)
(In the rest of the plain text of the speech transcripts, never insert the characters between parentheses `(`string`)` or between brackets `{`string`}` or between braces `[`string`]`

## **Advanced Narration Instructions: Rhythm, Pauses, and Sounds Humanized**
Your main task is to enrich the script with tags that control the rhythm and add human sounds.

1. **Incorporate Strategic Pauses:** For a more natural and less rushed rhythm, insert pause tags in the text where a breath or a moment of reflection would be appropriate.
* Use **`[character pauses briefly in speech]`** for a quick breath between sentences.
* Use **`[character pauses long in speech]`** for a more dramatic moment or to separate main ideas.
* **Example:** `The decision wasn't easy... [John pauses long in speech] but it had to be made.`

2. **Incorporate Nonverbal Paralinguistic Sounds:** When appropriate to the emotion, insert tags for human sounds that break the monotony. The TTS API will attempt to simulate these sounds with the character's voice.
* Use the format: **`[character makes sound of sound_type]`**.
* **Tag Examples:** `[John makes a sound of suppressed laughter]`, `[John makes a sound of sighing with relief]`, `[John makes a sound of murmuring]`, `[John makes a sound of surprise]`.
* **Example of use:** `He thought he could fool me... [John makes a sound of suppressed laughter] little did he know.`

3. **Combine with Speaking Styles:** Continue using the speaking style cues in parentheses `[character ...]` to guide the overall emotion of a passage.
* **Consolidated Example:** **`[John speaks in a more serious tone] And the result... [John pauses for a long time] was exactly what we expected... [John makes a sound of sighing with relief] A complete success...`**

Attention... the text must provide a narrative speech with the maximum estimated total time --->>> {VIDEO_TIME_SECONDS}

**Attention:** Unless something else is required opposite direction in the narrative introduction,
* You should start the video in a friendly tone, greeting or saluting the target audience as a close friend.

##**CRUCIAL Instructions for Speech Style in the Audio Prompt Narrative:**
* When creating the Audio Prompt narrative text, **you MUST incorporate stylus, commands, or speech descriptions directly into the text** when appropriate to convey the desired emotion or add emphasis.
* These commands should be clear and concise, preferably in parentheses `[character ...]` or, in the case of stylus, inserted before each paragraph followed by : and a line break, always before the text section to which they apply.
e.g.:
`[John speaks enthusiastically:]
Hi friend [John expressing a pensive voice] long time no see, [John appearing to be mumbling] I think about 10 years.

[John speaks curiously]:
But what about Any news? [John speaks in a confused tone] Have I got the job yet?

* **Get inspired by the examples in the Gemini TTS documentation for style control:**
* For a single speaker, you can use phrases like: `[character in a creepy whisper] At the prick of my thumbs... Something wicked is approaching.`
* To indicate a specific emotion for a passage: `[character in a tired and bored voice] So... what's on today's agenda?` or `[character in an optimistic tone] The future looks bright!`
* Select interpretable style keywords, such as: `whispering`, `excited`, `serious`, `calm`, `energetic`, `sad`, `happy`, `bored`, `scary`, `optimistic`, `reflective`, `authoritative`.
* **Adapt these examples for a fluid and natural narrative.** The goal is for the TTS engine (Text-to-Speech), like Gemini's, can interpret these cues to modulate the voice.
* The character's overall emotion will be defined in the "emotion" field of the "voices" sub-object in the JSON, but the `promptAudio` can and should have more granular style variations to enrich the delivery.

***Additional Rules:**
- Use first-person or character voice.
- Define the character's age and gender (Male or Female). Also define their general mood (Happy, Sad, Angry, etc.) in the corresponding fields within the "voices" sub-object in the JSON.
- Do not add numbers to the text [0123456789]. Write elws literally with 99. You should write ninety-nine. .. characters with R$. Write Reais, % percentage, @, + plus, - minus...

**Avoid:**
- Difficult words.
- Overly aggressive salesmanship, unless the speaking style dictates it for a particular passage.
- Obvious repetitions or generic, cold phrases.

"Additional Rules": "**No Corporate Jargon:** Avoid terms like 'synergy,' 'leverage,' 'optimize,' and 'innovative solution,' unless the tone of voice is specifically corporate."
"Integrate the 'Tone of Language' subtly throughout the text, not just in the style tags. If the tone is 'relaxed,' use light slang and shorter sentences."

At the end of each paragraph or when appropriate, add [character pauses for a long time], [character makes a reflective pause]...

**Expected response format:**
(You do not need to comment or justify the answer only display the completed json list )

```Json{
    "aprovado": true,
    "promptAudio": "string",
    "vozes": {
      "sexo": "Male or Female",
      "idade": "string",
      "emocao": "{VOICE_EMOTION}",
      "voz": "{VOICE_EMOTION}",
      "audioPath": null,
      "legendaPath": null
    }
}```
      
    """

    fun getFormattedPrompt(
        userNameCompany: String,
        userProfessionSegment: String,
        userAddress: String,
        userTargetAudience: String,
        userLanguageTone: String,
        produto: String,
        extra: String,
        descFotos: String,
        VIDEO_OBJECTIVE_INTRODUCTION: String,
        VIDEO_OBJECTIVE_VIDEO: String,
        VIDEO_OBJECTIVE_OUTCOME: String,
        VIDEO_TIME_SECONDS: String,
        VOICE_EMOTION: String,
        VOICE_GENERE: String
    ): String {
        return promptTemplate
            .replace("{USER_NAME_COMPANY}", userNameCompany.ifBlank { "Não informado" })
            .replace("{USER_PROFESSION_SEGMENT}", userProfessionSegment.ifBlank { "Não informado" })
            .replace("{USER_ADDRESS}", userAddress.ifBlank { "Não informado" })
            .replace("{USER_TARGET_AUDIENCE}", userTargetAudience.ifBlank { "Geral" })
            .replace("{PRODUTO}", produto.ifBlank { "Tópico principal" })
            .replace("{FOTOS}", descFotos.ifBlank { "Nenhuma descrição de imagem de referência fornecida." })
            .replace("{EXTRAS}", extra.ifBlank { "Sem informações contextuais adicionais." })
            .replace("{USER_LANGUAGE_TONE}", userLanguageTone.ifBlank { "Neutro" })
            .replace("{VIDEO_OBJECTIVE_INTRODUCTION}", VIDEO_OBJECTIVE_INTRODUCTION.ifBlank { "Apresentar o tema." })
            .replace("{VIDEO_OBJECTIVE_VIDEO}", VIDEO_OBJECTIVE_VIDEO.ifBlank { "Desenvolver o tema principal." })
            .replace("{VIDEO_OBJECTIVE_OUTCOME}", VIDEO_OBJECTIVE_OUTCOME.ifBlank { "Concluir e engajar." })
            .replace("{VIDEO_TIME_SECONDS}", VIDEO_TIME_SECONDS.ifBlank { "Não especificado" })
            .replace("{VOICE_GENERE}", VOICE_GENERE.ifBlank { "Não especificado" })
            .replace("{VOICE_EMOTION}", VOICE_EMOTION.ifBlank { "Não especificado" })
    }
}