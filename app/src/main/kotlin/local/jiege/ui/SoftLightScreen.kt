package local.jiege.ui

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt
import local.jiege.settings.ModuleSettings

internal val SOFT_LIGHT_LEVELS = listOf(
    Triple(ModuleSettings.SOFT_LIGHT_WEAK_HDF, "弱", "HDF"),
    Triple(ModuleSettings.SOFT_LIGHT_MEDIUM_BLACK_MIST, "中", "1/4 黑柔"),
    Triple(ModuleSettings.SOFT_LIGHT_STRONG_WHITE_MIST, "强", "白柔"),
)

internal fun softLightSummary(store: SettingsStore): String =
    SOFT_LIGHT_LEVELS
        .filter { store.get(it.first) }
        .joinToString("，") { "${it.second}挡为${it.third}" }
        .ifEmpty { "未启用" }

/** Sub-page for physical soft-light lens filter simulation. */
@Composable
internal fun SoftLightScreen(
    contentPadding: PaddingValues,
    nestedScroll: NestedScrollConnection,
    overscrollEffect: OverscrollEffect,
    store: SettingsStore,
    listState: LazyListState = rememberLazyListState(),
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScroll),
        contentPadding = contentPadding,
        overscrollEffect = overscrollEffect,
    ) {
        item(key = "levels") {
            UiGroup(title = "挡位替换") {
                SOFT_LIGHT_LEVELS.forEachIndexed { idx, (key, level, filter) ->
                    CouiSettingSwitch(
                        store = store,
                        key = key,
                        title = "${level}挡换成${filter}模拟",
                    )
                    if (idx < SOFT_LIGHT_LEVELS.lastIndex) UiDivider()
                }
            }
        }
        item(key = "gallery_and_fusion") {
            UiGroup(title = "相册与融合") {
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.SOFT_LIGHT_GALLERY,
                    title = "相册编辑生效",
                    summary = "重编辑大师照片时按拍摄焦距套用上方挡位",
                )
                UiDivider()
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.SOFT_LIGHT_NO_LOCAL_CONTRAST,
                    title = "关闭对比度增强",
                    summary = "效果更贴近光学滤镜，略降画面通透度",
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

internal fun opticalVignetteSummary(store: SettingsStore): String {
    return when (store.getInt(ModuleSettings.MASTER_FILM_VIGNETTE)) {
        ModuleSettings.VIGNETTE_ON -> {
            val isCustomLook = store.getInt(ModuleSettings.MASTER_LOOK) != 0
            val comp = store.getInt(ModuleSettings.MASTER_VIGNETTE_LUMA_COMP)
            if (isCustomLook && comp > 0) {
                String.format(Locale.US, "开启 · 亮度补偿 %.2f", comp / 100f)
            } else {
                "开启"
            }
        }
        ModuleSettings.VIGNETTE_FOLLOW_ENGINE -> "跟随引擎"
        else -> "关闭"
    }
}

@Composable
internal fun OpticalVignetteCompSlider(
    store: SettingsStore,
    compKey: String,
    enabled: Boolean = true,
) {
    var rawComp by remember(compKey) {
        mutableFloatStateOf(store.getInt(compKey).coerceIn(0, 100) / 100f)
    }
    val compInt = (rawComp * 100f).roundToInt().coerceIn(0, 100)
    CouiSliderRow(
        title = "全局亮度补偿",
        summary = "按当前焦段与画幅的整体暗角衰减量在线性空间内同步补偿，不改变中心到边角的暗角比例，也不改动曝光补偿显示",
        valueLabel = String.format(Locale.US, "%.2f", compInt / 100f),
        value = rawComp,
        valueRange = 0f..1f,
        enabled = enabled,
        resetLabel = if (compInt != 0) "0.00" else null,
        onReset = {
            rawComp = 0f
            store.setInt(compKey, 0)
        },
        onValueChange = { next ->
            rawComp = next.coerceIn(0f, 1f)
        },
        onValueChangeFinished = {
            store.setInt(compKey, (rawComp * 100f).roundToInt().coerceIn(0, 100))
        },
    )
}

/** Sub-page for optical cos^4(theta) vignette simulation and internal luminance compensation. */
@Composable
internal fun OpticalVignetteScreen(
    contentPadding: PaddingValues,
    nestedScroll: NestedScrollConnection,
    overscrollEffect: OverscrollEffect,
    store: SettingsStore,
    listState: LazyListState = rememberLazyListState(),
) {
    var mode by remember {
        mutableIntStateOf(store.getInt(ModuleSettings.MASTER_FILM_VIGNETTE).coerceIn(0, 2))
    }
    val showComp = mode == ModuleSettings.VIGNETTE_ON && store.getInt(ModuleSettings.MASTER_LOOK) != 0

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScroll),
        contentPadding = contentPadding,
        overscrollEffect = overscrollEffect,
    ) {
        item(key = "optical_vignette") {
            val summary = when (mode) {
                ModuleSettings.VIGNETTE_ON -> "所有大师色彩风格均强制开启物理 cos⁴θ 光学暗角"
                ModuleSettings.VIGNETTE_FOLLOW_ENGINE -> "由各个色彩引擎页面分别控制光学暗角开关与亮度补偿"
                else -> "关闭物理暗角模拟，保留大师模式原生暗角"
            }
            UiGroup(title = "光学暗角") {
                CouiMenuRow(
                    title = "光学暗角模拟",
                    summary = summary,
                    items = listOf("关闭", "开启", "跟随引擎"),
                    selectedIndex = mode,
                    onSelect = { next ->
                        mode = next
                        store.setInt(ModuleSettings.MASTER_FILM_VIGNETTE, next)
                    },
                )
                if (showComp) {
                    UiDivider()
                    OpticalVignetteCompSlider(
                        store = store,
                        compKey = ModuleSettings.MASTER_VIGNETTE_LUMA_COMP,
                        enabled = true,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
