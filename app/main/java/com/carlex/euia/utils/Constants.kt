package com.carlex.euia.utils

object WorkerTags {
    // Tags principais que o AppLifecycleObserver verifica
    const val AUDIO_NARRATIVE = "audio_narrative_work"
    const val VIDEO_PROCESSING = "video_processing_work"

    // Outras tags definidas nos seus workers (para referência ou uso em outros locais)
    // Algumas podem vir dos companion objects dos workers se forem públicas e você preferir.
    // Se não, defina-as aqui para centralizar.

    // Tags para UrlImportWorker (como definidas em UrlImportWorker.kt)
    const val URL_IMPORT_WORK = "url_import_work_initial_gemini" // Usada no ViewModel
    const val URL_IMPORT_WORK_PRE_CONTEXT = "url_import_work_pre_context"
    const val URL_IMPORT_WORK_CONTENT_DETAILS = "url_import_work_content_details"

    // Tag para ImageProcessingWorker (como definida em ImageProcessingWorker.kt)
    const val IMAGE_PROCESSING_WORK = "image_processing_work"

    // Tag para RefImageAnalysisWorker (como definida em RefImageAnalysisWorker.kt)
    const val REF_IMAGE_ANALYSIS_WORK = "ref_image_analysis_work"

    // Adicione quaisquer outras tags de worker que você use no sistema.
}