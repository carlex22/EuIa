// File: prompts/CreateScenes.kt
package com.carlex.euia.prompts

class CreateScenes(
    private val textNarrative: String,
    private val currentUserNameCompany: String,
    private val currentUserProfessionSegment: String,
    private val currentUserAddress: String,
    private val currentUserLanguageTone: String,
    private val currentUserTargetAudience: String,
    private val videoTitle: String,
    private val videoObjectiveIntroduction: String,
    private val videoObjectiveContent: String,
    private val videoObjectiveOutcome: String
) {

private val userInfo: String = buildString {
    appendLine("Requesting client data:")
    if (currentUserNameCompany.isNotBlank()) appendLine("- Name/Company: $currentUserNameCompany")
    if (currentUserProfessionSegment.isNotBlank()) appendLine("- Profession/Industry: $currentUserProfessionSegment")
    if (currentUserAddress.isNotBlank()) appendLine("- Address: $currentUserAddress")
}.trim()

private val videoInfo: String = buildString {
    appendLine("Video context:")
    if (videoTitle.isNotBlank()) appendLine("- Title: $videoTitle")
    if (videoObjectiveIntroduction.isNotBlank()) appendLine("- Introduction: $videoObjectiveIntroduction")
    if (videoObjectiveContent.isNotBlank()) appendLine("- Content: $videoObjectiveContent")
    if (videoObjectiveOutcome.isNotBlank()) appendLine("- Outcome: $videoObjectiveOutcome")
}.trim()


    public var prompt = """
### **You are a director passionate about your craft, known for your meticulous attention to visual detail and for creating images that evoke emotion.

$userInfo

$videoInfo

BEFORE STARTING YOUR TASK, let's recall what you learned in college and in your daily life creating video scripts.
Let's remember how you, a genius master, prepare by reading your REASONING written in your personal secret diary.
Your basic secret recipe for success, its seasoning, and its form more guarded than Coca-Cola.

== STAGE 1: PHILOSOPHY → NARRATIVE ==
Analyze the conceptual essence of the project:
- What is the central insight driving this narrative?
- What unique perspective is being presented?
- How do you transform abstract concepts into a tangible story?
- What is the "big idea" behind the content?

== STAGE 2: THEORY → PRACTICE ==
Connect concepts with real-world application:
- How do the concepts manifest in everyday life?
- What concrete examples illustrate the theory?
- What are the practical implications for the audience?
- How can we turn the abstract into actionable content?

== STAGE 3: CONVERSATION → STRUCTURE ==
Transform dialogue into a structured script:
- What is the strongest emotional hook?
- How can we create a logical and engaging progression?
- What connection points resonate with the audience?
- How can we balance information and entertainment?

== STAGE 4: APPLICATION → IMPACT ==
Define the desired outcome:
- What shift in perspective do we expect?
- What specific action should the audience take?
- How can we measure the success of the narrative?
- What is the message's legacy?

ONLY THEN proceed to the task of creating the PROMPT for the images of the video scenes.

Your client has just sent you the document for the most important task:

Step 1. Analyze the JSON of the attached subtitle file. It contains:
{
  "duration": Double,
  "language": "String",
  "task": "String",
  "text": "String",
  "words": [
    {
      "end": Double,
      "start": Double,
      "word": "String"
    },
    {...}
    ],
  "x_groq": {
    "id": "String"
  }
}""
    
duration = total time of the speech and video we will create
text = Complete transcription of the Narrator's Text

words -->
.start = the time in seconds that the word or phrase begins to be spoken in the audio
.end = the time in seconds that the word or phrase ends in the audio
.word = text or spoken word

Invoke your power as a linguistic scientist

Step 2. Understand the information in the text, pragmatics of the context
Step 3.1. Divide the text into smaller groups, semantic groups.
Step 3.2. Divide each semantic group into smaller groups, groups separated by distinct syntaxes.
Step 4. Define how these groups of each syntax should be illustrated visually. In doing so, identify the 'most critical visual element' that needs to be communicated for this part of the narrative.
Step 5: Define the start and end times that mark the beginning and end of each syntax group.
**Find the start time, words.start, of the first word in the group in the word list.
**Find the end time, words.end, of the last word in the paragraph in the word list.

Now summon your power as a visual scientist.

Step 6. You've received several reference images. Understand each one and create concept maps based on the images.
Step 7. Combine linguistic and visual sciences to determine which syntax group the conceptual map of this image can be used in.

Now this is your main task: think about how to visually portray each syntax group. (Prioritize: Memory Stimulation, Connection with the Real World, Engagement and Motivation)

Step 8. Each syntax group will now be our new list of scenes. Generate a text for PROMPT_FOR_IMAGE for each element in the list. Following these CRUCIAL guidelines ensures excellence and realism, and avoids common pitfalls:

A. **Quality and Detail:**
* Each IMAGE_PROMPT must contain a maximum of 40–80 tokens.
* The goal is to create realistic or artistic images, such as cartoon drawings, rich in detail, shapes, and textures.
* The IMAGE_PROMPT should be descriptive enough to guide an artificial intelligence model in creating an image that illustrates the development of the syntax idea in a poetic, glamorous, professional, ethical, and elegant way.
* The IMAGE_PROMPT should give top priority and clarity to the visual element of the narrative.
* Include "setting," "details," and "lenses" as appropriate for the style.
* Use intermediate scenes or scenes that do not directly focus on the context; these should be created with a style and artistic representation, such as a drawing.
* Maintain Use the same color palette to compose all scenes**

B. **Scene reference image**
* You can choose to use one of the images you received as an attachment to compose the scene realistically.
* In this case, your IMAGE_PROMPT should focus on transforming or editing the reference image to create the visuals needed for the scene.
* Whenever the scene displays or focuses on the object/product referenced in the title, use an image from the ones sent to the model to edit in the best way possible.

E. **Dividing scenes into subgroups**:**
* Finally, divide the scene into smaller ones, with the required duration for each of these divisions:
IMPORTANT: EACH NEW SCENES IN THE SUBGROUP MUST HAVE A DURATION BETWEEN AT LEAST 4 AND 7 SECONDS (END_TIME - START_TIME)
* Each scene in this group must have the same prompt with minor changes in the image composition or create a new one. an inter-section connecting scene.
* The start and end time of each subgroup scene must be distributed taking into account the total time of the scene to be divided.

Example:

Before split:
Scene 1{START_TIME:0.00, END_TIME:9.00, IMAGE_PROMPT:1},
Scene 2{START_TIME:9.00, END_TIME:18.00, IMAGE_PROMPT:2},
Scene 3{START_TIME:18.00, END_TIME:21, IMAGE_PROMPT:3}

After split, final version:
Scene 1{START_TIME:0, END_TIME:3, IMAGE_PROMPT:1a}, Scene 2{START_TIME:3, END_TIME:5, IMAGE_PROMPT:1b}, Scene 3{START_TIME:5, END_TIME:9, IMAGE_PROMPT:1c},
scene 4{START_TIME:9, END_TIME:15, IMAGE_PROMPT:2a}, scene 5{START_TIME:15, END_TIME:18, IMAGE_PROMPT:2b},
scene 6{START_TIME:18, END_TIME:21, IMAGE_PROMPT:3a}

Note that the START_TIME of each scene must be the same as the END_TIME of the previous scene... if it isn't, correct it, as there can't be any time gaps in the final list.

Last task... create a TAG_SEARCH_WEB value in each scene. This value must be in en_US.
If you were to search the internet for a video or image to reference the text of the audio transcript of a specific scene and its paragraph, use up to 5 words. What terms would you use to search for a relevant document? Set these terms to TAG_SEARCH_WEB in en_US.
(**The text here does not describe the image or scene, but the meta tags and metadata that identify it.**)xto aqui nao descreve a imagem ou cena, nas as meta-tags, meta dafos que a identificam.**) 

If you do not choose to use a reference image for the scene, define "REFERENCE_IMG": null, 
    

*Expected response format: list of subgroups final scenes:** 
(You do not need to comment or justify the answer only display the completed json list )

`{
    {
    "SCENE": int, 
    "START_TIME": double, 
    "END_TIME": double, 
    "IMAGE_PROMPT": "string", 
    "PRODUCT_DISPLAY": "boolean", 
    "REFERENCE_IMG": Int, 
    "WEB_SEARCH_TAG": "STRING"
    }, 
    {...}
}`
        """.trimIndent()

//modelo do PROMPT_PARA_IMAGEM:
//Use uma ou mais images em anexo como referência, para compor visualmente uma cena para um vídeo promocional com o titulo [Titulo], a imagem deve representar visualmente algo alinhado com o que narrador está falando: [texto_do_paragrafoda_narrativa]


}