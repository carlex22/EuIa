package com.carlex.euia.utils

object WorkerTags {
    // Tags principais que o AppLifecycleObserver verifica
    const val AUDIO_NARRATIVE = "audio_narrative_work"
    const val VIDEO_PROCESSING = "video_processing_work"
    const val IMAGE_PROCESSING_WORK = "image_processing_work"
    const val VIDEO_RENDER = "video_render_work" // Constante para VideoRenderWorker

    const val IMAGE_DOWNLOAD_WORK = "image_download_work" // <<< NOVA TAG ADICIONADA

    // Outras tags definidas nos seus workers (para referência ou uso em outros locais)
    const val URL_IMPORT_WORK = "url_import_work_initial_gemini"
    const val URL_IMPORT_WORK_PRE_CONTEXT = "url_import_work_pre_context"
    const val URL_IMPORT_WORK_CONTENT_DETAILS = "url_import_work_content_details"
    const val REF_IMAGE_ANALYSIS_WORK = "ref_image_analysis_work"
}