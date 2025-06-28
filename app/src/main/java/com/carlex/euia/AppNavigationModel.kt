// File: AppNavigationModel.kt
package com.carlex.euia // <<<<< VERIFIQUE SE ESTE PACOTE ESTÁ CORRETO >>>>>

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.vector.ImageVector

// Definição do item de menu
data class DrawerMenuItem(
    val title: String,
    @DrawableRes val iconResId: Int? = null,
    val iconImageVector: ImageVector? = null,
    val route: String,
    val isDivider: Boolean = false
)

// Destinos da aplicação
object AppDestinations {
    const val CHAT_ROUTE = "chat"
    const val USERINFO_ROUTE = "user_info"
    const val ABOUT_ROUTE = "about"
    const val SETTINGS_ROUTE = "settings"
    const val IMPORT_DATA_URL_ROUTE = "import_data_url"
    const val VIDEO_CREATION_WORKFLOW = "video_creation_workflow"
    const val PROJECT_MANAGEMENT_ROUTE = "project_management"

        
    // Novas rotas para autenticação e monetização
    const val LOGIN_ROUTE = "login_route" // <<<<< ESTAS CONSTANTES SÃO O FOCO >>>>>
    const val REGISTER_ROUTE = "register_route" // <<<<< ELAS PRECISAM ESTAR AQUI >>>>>
    const val PREMIUM_OFFER_ROUTE = "premium_offer_route" // <<<<< NESTE OBJETO >>>>>
    const val ADD_GEMINI_API_KEY_ROUTE = "add_gemini_api_key"
    const val WORKFLOW_STAGE_CONTEXT = "stage_context"
    const val WORKFLOW_STAGE_IMAGES = "stage_images"
    const val WORKFLOW_STAGE_INFORMATION = "stage_information"
    const val WORKFLOW_STAGE_NARRATIVE = "stage_narrativa"
    const val WORKFLOW_STAGE_AUDIO = "workflow_stage_audio"  // CORREÇÃO: Constante adicionada
    const val WORKFLOW_STAGE_SCENES = "stage_scenes"
    const val WORKFLOW_STAGE_FINALIZE = "stage_finalize"
}

// Definição da etapa do workflow
data class WorkflowStage(
    val title: String,
    val identifier: String
)

// Função para sanitizar nome do diretório (pode ficar aqui ou em utils)
fun sanitizeDirName(name: String): String {
    return name.replace(Regex("[^A-Za-z0-9_.-]"), "_").trim('_')
}