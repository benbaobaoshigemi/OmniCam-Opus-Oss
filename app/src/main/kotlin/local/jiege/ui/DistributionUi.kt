package local.jiege.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import local.jiege.settings.DiagnosticsContract

/** UI-only distribution extension points; overridden via file overlay in release builds. */
internal object DistributionUi {
    const val BRAND_TITLE: String = "OmniCam Opus"
    const val LOG_FILE_PREFIX: String = "OmniCam-Opus"

    fun statusBannerOverride(health: Health?): String? = null

    @Composable
    fun HomeVerificationSlot(cardModifier: Modifier) {
        UiGroup(title = "校验", modifier = cardModifier) {
            UiInfoRow(title = "验证完成", summary = "开发模式")
        }
    }

    @Composable
    fun logExportFileName(): String = LogExport.fileName()
}
