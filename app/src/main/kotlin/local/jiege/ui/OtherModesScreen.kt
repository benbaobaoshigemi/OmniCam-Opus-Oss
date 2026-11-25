package local.jiege.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import local.jiege.settings.ModuleSettings

private val XPAN_CLEAN_OPTIONS = listOf(
    ModuleSettings.XPAN_CLEAN_SMALL_MASK to "关闭小取景框遮罩",
    ModuleSettings.XPAN_CLEAN_LARGE_MASK to "关闭大取景框遮罩",
    ModuleSettings.XPAN_CLEAN_ANIMATION to "关闭显影动画",
)

/** Tab 4 (其他模式): Photo / High-Pixel / POP / XPAN / Video settings isolated from Master/Ricoh Pro-Auto. */
@Composable
internal fun OtherModesScreen(
    contentPadding: PaddingValues,
    nestedScroll: NestedScrollConnection,
    overscrollEffect: OverscrollEffect,
    listState: LazyListState = rememberLazyListState(),
    store: SettingsStore,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScroll),
        contentPadding = contentPadding,
        overscrollEffect = overscrollEffect,
    ) {
        item(key = "photo") {
            var natural by remember { mutableStateOf(store.get(ModuleSettings.NATURAL_MODE)) }
            UiGroup(title = "拍照") {
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.UNLOCK_25MP,
                    title = "放宽 25MP 自动切换条件",
                    summary = "放宽照度、ISO、温控与防抖限制，需开启相机“超清优先”",
                )
                UiDivider()
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.NATURAL_MODE,
                    title = "拍照模式自然化",
                    onChange = { natural = it },
                )
                AnimatedVisibility(visible = natural) {
                    Column {
                        UiDivider()
                        CouiSettingSwitch(
                            store = store,
                            key = ModuleSettings.NATURAL_NO_AI_DETAIL,
                            title = "关闭 AI 细节增强",
                            summary = "抑制算法涂抹重绘与发丝锐化",
                        )
                        UiDivider()
                        CouiSettingSwitch(
                            store = store,
                            key = ModuleSettings.NATURAL_NO_LCE,
                            title = "关闭局部对比度增强（LCE）",
                        )
                        UiDivider()
                        CouiSettingSwitch(
                            store = store,
                            key = ModuleSettings.NATURAL_NO_SUNSET,
                            title = "关闭落日特调",
                            summary = "避免太阳死白扁平与边缘假光晕",
                        )
                        UiDivider()
                        CouiSettingSwitch(
                            store = store,
                            key = ModuleSettings.NATURAL_NO_TELE_CONTRAST,
                            title = "关闭长焦反差增强",
                            summary = "10× 以上关闭局部提反差，消除亮边光晕与过锐感",
                        )
                        UiDivider()
                        CouiSettingSwitch(
                            store = store,
                            key = ModuleSettings.NATURAL_NO_TELE_SR,
                            title = "关闭超长焦 AI 超分",
                            summary = "20× 以上禁用生成式补细节，仅做常规放大",
                        )
                        UiDivider()
                        CouiSettingPercent(
                            store = store,
                            key = ModuleSettings.NATURAL_SOFTEN,
                            title = "进一步柔化",
                            summary = "仅作用于普通拍照模式",
                        )
                    }
                }
            }
        }
        item(key = "high_pixel") {
            UiGroup(title = "哈苏超清") {
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.HIGH_PIXEL_EFFECTS,
                    title = "哈苏超清滤镜与调色盘",
                    summary = "在取景框角落启用滤镜、调色盘与柔光",
                )
                UiDivider()
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.HIGH_PIXEL_200M_NO_FACE,
                    title = "解除 200MP 人脸识别门禁",
                    summary = "无人脸场景也启用真 200MP 读出（限 1×、充足光线、非 HDR）",
                )
            }
        }
        item(key = "pop") {
            UiGroup(title = "POP") {
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.POP_SDR_STYLE,
                    title = "POP 使用 SDR 风格",
                    summary = "直出偏亮 CCD 质感；关闭则输出 Ultra HDR",
                )
                UiDivider()
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.POP_HIGH_PIXEL,
                    title = "POP 高像素（25MP）",
                    summary = "光线充足且无人脸时输出 25MP",
                )
            }
        }
        item(key = "xpan") {
            var xpanCleanExpanded by remember {
                mutableStateOf(XPAN_CLEAN_OPTIONS.any { store.get(it.first) })
            }
            var xpanCleanRev by remember { mutableStateOf(0) }
            val cleanedSummary = remember(xpanCleanRev) {
                XPAN_CLEAN_OPTIONS
                    .filter { store.get(it.first) }
                    .joinToString("、") { it.second.removePrefix("关闭") }
                    .let { if (it.isEmpty()) "保持原厂取景" else "已关闭：$it" }
            }
            UiGroup(title = "XPAN") {
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.XPAN_DEVELOPING_LIVE,
                    title = "XPAN 显影实况",
                    summary = "保存为负片显影动态实况",
                )
                UiDivider()
                UiInfoRow(
                    title = "XPAN 取景净化",
                    summary = cleanedSummary,
                    value = if (xpanCleanExpanded) "收起" else "展开",
                    onClick = { xpanCleanExpanded = !xpanCleanExpanded },
                )
                AnimatedVisibility(visible = xpanCleanExpanded) {
                    Column {
                        XPAN_CLEAN_OPTIONS.forEach { (key, title) ->
                            UiDivider()
                            CouiSettingSwitch(
                                store = store,
                                key = key,
                                title = title,
                                onChange = { xpanCleanRev++ },
                            )
                        }
                    }
                }
            }
        }
        item(key = "video") {
            UiGroup(title = "视频") {
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.VIDEO_FILTER_HIGH_SPEC,
                    title = "4K / 60fps 视频滤镜",
                    summary = "开启滤镜时不回退至 1080p 30fps",
                )
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.VIDEO_BEAUTY_HIGH_SPEC,
                    title = "4K / 60fps 视频美颜",
                    summary = "开启美颜时不回退至 1080p 30fps",
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
