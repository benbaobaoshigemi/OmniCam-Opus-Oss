package local.jiege.ui

import android.content.Context
import android.content.res.AssetManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.itos.coui.chip.CouiChipTabItem
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.theme.CouiTheme
import local.jiege.settings.FilmChartNative
import local.jiege.settings.FilmStyle
import local.jiege.settings.HncsStyle
import local.jiege.settings.MasterAlign
import local.jiege.settings.MasterStyle
import local.jiege.settings.MasterStyles
import local.jiege.settings.ModuleSettings
import java.util.Locale
import kotlin.math.roundToInt

private const val UI_PREFS_NAME = "ui_prefs"
private const val PREF_LAST_CAMERA_STYLE = "last_camera_style"
private const val PREF_LAST_ADOBE_STYLE = "last_adobe_style"

private const val STYLE_OPPO = 0
private const val STYLE_HNCS = 1
private const val STYLE_LUMIX = 2
internal const val STYLE_FILM = 3
private const val STYLE_CANON = 4
private const val STYLE_DARKTABLE = 7
private const val STYLE_LEICA = 8
private const val STYLE_LEICA_M240 = 17
private const val STYLE_LEICA_M10R = 18
private const val STYLE_BASIC = 10
internal const val STYLE_X100VI = 11
private const val STYLE_ADOBE_SIGMA = 13
private const val STYLE_ADOBE_OM = 14
private const val STYLE_ADOBE_SONY = 15
private const val STYLE_ADOBE_NIKON = 16

private val CAMERA_INDICES = listOf(STYLE_HNCS, STYLE_LEICA, STYLE_LEICA_M240, STYLE_LEICA_M10R, STYLE_X100VI)
private val ADOBE_INDICES = listOf(STYLE_BASIC, STYLE_ADOBE_SIGMA, STYLE_ADOBE_OM, STYLE_ADOBE_SONY, STYLE_ADOBE_NIKON)

internal enum class LookCategory { OPPO, CAMERA, FILM, ADOBE, TONEMAP }

private fun categoryOf(styleIndex: Int): LookCategory = when (styleIndex) {
    STYLE_OPPO -> LookCategory.OPPO
    STYLE_FILM -> LookCategory.FILM
    STYLE_DARKTABLE -> LookCategory.TONEMAP
    STYLE_BASIC, STYLE_ADOBE_SIGMA, STYLE_ADOBE_OM, STYLE_ADOBE_SONY, STYLE_ADOBE_NIKON -> LookCategory.ADOBE
    else -> LookCategory.CAMERA
}

/** Tab 2 (色彩引擎): 2-level Master Color Engine overview + blend/exposure cards. */
@Composable
internal fun MasterLookScreen(
    contentPadding: PaddingValues,
    nestedScroll: NestedScrollConnection,
    overscrollEffect: OverscrollEffect,
    listState: LazyListState = rememberLazyListState(),
    store: SettingsStore,
    onOpenCategory: (LookCategory) -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val prefs = remember(context) { context.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE) }

    var styleIndex by remember { mutableIntStateOf(MasterStyles.byIndex(store.getInt(ModuleSettings.MASTER_LOOK)).index) }
    var mode by remember { mutableIntStateOf(store.getInt(ModuleSettings.MASTER_LOOK_MODE)) }
    var revision by remember { mutableIntStateOf(0) }

    val storedCam = prefs.getInt(PREF_LAST_CAMERA_STYLE, STYLE_HNCS).let { if (it in CAMERA_INDICES) it else STYLE_HNCS }
    val storedAdobe = prefs.getInt(PREF_LAST_ADOBE_STYLE, STYLE_BASIC).let { if (it in ADOBE_INDICES) it else STYLE_BASIC }
    var lastCameraStyle by remember { mutableIntStateOf(if (styleIndex in CAMERA_INDICES) styleIndex else storedCam) }
    var lastAdobeStyle by remember { mutableIntStateOf(if (styleIndex in ADOBE_INDICES) styleIndex else storedAdobe) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val current = MasterStyles.byIndex(store.getInt(ModuleSettings.MASTER_LOOK)).index
                styleIndex = current
                mode = store.getInt(ModuleSettings.MASTER_LOOK_MODE)
                if (current in CAMERA_INDICES) {
                    lastCameraStyle = current
                    prefs.edit().putInt(PREF_LAST_CAMERA_STYLE, current).apply()
                } else if (current in ADOBE_INDICES) {
                    lastAdobeStyle = current
                    prefs.edit().putInt(PREF_LAST_ADOBE_STYLE, current).apply()
                }
                revision++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val selections = remember(store) { MasterStyle.IntLookup { key -> store.getInt(key) } }
    val activeCategory = categoryOf(styleIndex)
    val style = MasterStyles.byIndex(styleIndex)
    val blend = mode == MasterStyles.MODE_BLEND

    fun activateStyle(nextIndex: Int) {
        val target = MasterStyles.byIndex(nextIndex)
        styleIndex = target.index
        store.setInt(ModuleSettings.MASTER_LOOK, target.index)
        if (!target.blendable() && mode == MasterStyles.MODE_BLEND) {
            mode = MasterStyles.MODE_REPLACE
            store.setInt(ModuleSettings.MASTER_LOOK_MODE, mode)
        }
        if (target.index in CAMERA_INDICES) {
            lastCameraStyle = target.index
            prefs.edit().putInt(PREF_LAST_CAMERA_STYLE, target.index).apply()
        } else if (target.index in ADOBE_INDICES) {
            lastAdobeStyle = target.index
            prefs.edit().putInt(PREF_LAST_ADOBE_STYLE, target.index).apply()
        }
        revision++
    }

    val oppoSummary = remember(revision) { styleOptionSummary(STYLE_OPPO, store, selections) }
    val cameraSummary = remember(lastCameraStyle, revision) { styleOptionSummary(lastCameraStyle, store, selections) }
    val filmSummary = remember(revision) { styleOptionSummary(STYLE_FILM, store, selections) }
    val adobeSummary = remember(lastAdobeStyle, revision) { styleOptionSummary(lastAdobeStyle, store, selections) }
    val toneMapSummary = remember(revision) { styleOptionSummary(STYLE_DARKTABLE, store, selections) }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScroll),
        contentPadding = contentPadding,
        overscrollEffect = overscrollEffect,
    ) {
        item(key = "categories") {
            UiGroup(title = "色彩体系") {
                CouiCategoryEntryRow(
                    title = "OPPO",
                    summary = oppoSummary,
                    selected = activeCategory == LookCategory.OPPO,
                    onSelectRadio = { activateStyle(STYLE_OPPO) },
                    onOpen = { onOpenCategory(LookCategory.OPPO) },
                )
                UiDivider()
                CouiCategoryEntryRow(
                    title = "数码相机",
                    summary = cameraSummary,
                    selected = activeCategory == LookCategory.CAMERA,
                    onSelectRadio = { activateStyle(lastCameraStyle) },
                    onOpen = { onOpenCategory(LookCategory.CAMERA) },
                )
                UiDivider()
                CouiCategoryEntryRow(
                    title = "胶片",
                    summary = filmSummary,
                    selected = activeCategory == LookCategory.FILM,
                    onSelectRadio = { activateStyle(STYLE_FILM) },
                    onOpen = { onOpenCategory(LookCategory.FILM) },
                )
                UiDivider()
                CouiCategoryEntryRow(
                    title = "Adobe",
                    summary = adobeSummary,
                    selected = activeCategory == LookCategory.ADOBE,
                    onSelectRadio = { activateStyle(lastAdobeStyle) },
                    onOpen = { onOpenCategory(LookCategory.ADOBE) },
                )
                UiDivider()
                CouiCategoryEntryRow(
                    title = "色调映射",
                    summary = toneMapSummary,
                    selected = activeCategory == LookCategory.TONEMAP,
                    onSelectRadio = { activateStyle(STYLE_DARKTABLE) },
                    onOpen = { onOpenCategory(LookCategory.TONEMAP) },
                )
            }
        }
        item(key = "blend") {
            val blendEnabled = style.blendable()
            UiGroup(
                title = "色彩混合",
                footer = if (!blendEnabled) "OPPO 原厂基准无需混合。" else null,
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    CouiSegmentedBar(
                        labels = listOf("替换模式", "混合模式"),
                        selectedIndex = if (blend && blendEnabled) 1 else 0,
                        enabled = blendEnabled,
                        onSelected = { idx ->
                            mode = if (idx == 1) MasterStyles.MODE_BLEND else MasterStyles.MODE_REPLACE
                            store.setInt(ModuleSettings.MASTER_LOOK_MODE, mode)
                        },
                    )
                }
                AnimatedVisibility(visible = blend && blendEnabled) {
                    Column {
                        UiDivider()
                        CouiSettingPercent(
                            store = store,
                            key = ModuleSettings.MASTER_BLEND_TONE,
                            title = "影调",
                            summary = "0% 原厂，100% 所选风格",
                            resettable = true,
                        )
                        UiDivider()
                        CouiSettingPercent(
                            store = store,
                            key = ModuleSettings.MASTER_BLEND_COLOUR,
                            title = "色彩",
                            summary = "0% 原厂，100% 所选风格",
                            resettable = true,
                        )
                    }
                }
            }
        }
        item(key = "credits") {
            UiGroup(title = "致谢") {
                CouiNavRow(
                    title = "光子相机 PhotonCamera",
                    summary = "Lumix、胶片、Canon 与 Darktable 管线来源",
                    onClick = { uriHandler.openUri("https://github.com/bjzhou/PhotonCamera") },
                )
                UiDivider()
                CouiNavRow(
                    title = "Fotufilm",
                    summary = "胶片模拟引擎；胶片档案 CC BY-SA 4.0",
                    onClick = { uriHandler.openUri("https://github.com/DhaliwalX/fotufilm-engine") },
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Sub-page for OPPO stock baseline selection. */
@Composable
internal fun OppoLookScreen(
    contentPadding: PaddingValues,
    nestedScroll: NestedScrollConnection,
    overscrollEffect: OverscrollEffect,
    listState: LazyListState = rememberLazyListState(),
    store: SettingsStore,
) {
    val style = MasterStyles.byIndex(STYLE_OPPO)
    val selections = remember(store) { MasterStyle.IntLookup { key -> store.getInt(key) } }
    var activeLook by remember { mutableIntStateOf(store.getInt(ModuleSettings.MASTER_LOOK)) }
    val choice = style.choices().firstOrNull()
    var selected by remember(choice?.key) { mutableIntStateOf(choice?.let { store.getInt(it.key) } ?: 0) }

    fun activateOppo() {
        activeLook = STYLE_OPPO
        store.setInt(ModuleSettings.MASTER_LOOK, STYLE_OPPO)
        if (store.getInt(ModuleSettings.MASTER_LOOK_MODE) == MasterStyles.MODE_BLEND) {
            store.setInt(ModuleSettings.MASTER_LOOK_MODE, MasterStyles.MODE_REPLACE)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().nestedScroll(nestedScroll),
        contentPadding = contentPadding,
        overscrollEffect = overscrollEffect,
    ) {
        if (activeLook != STYLE_OPPO) {
            item(key = "activate_hint") {
                InactiveCategoryBanner(
                    label = "当前大师引擎未选中 OPPO",
                    onActivate = { activateOppo() },
                )
            }
        }
        if (choice != null) {
            item(key = "oppo_devices") {
                val offeredIndices = choice.labels.indices.filter { choice.offered(it, selections) }
                UiGroup(title = choice.title) {
                    offeredIndices.forEachIndexed { idx, labelIdx ->
                        CouiRadioRow(
                            title = choice.label(labelIdx, selections),
                            summary = choice.summaries?.getOrNull(labelIdx)?.takeIf { it.isNotBlank() },
                            selected = selected == labelIdx && activeLook == STYLE_OPPO,
                            onClick = {
                                activateOppo()
                                selected = labelIdx
                                store.setInt(choice.key, labelIdx)
                            },
                        )
                        if (idx < offeredIndices.lastIndex) UiDivider()
                    }
                }
            }
        }
        item(key = "oppo_optical_vignette") {
            OpticalVignetteEngineGroup(
                store = store,
                styleIndex = STYLE_OPPO,
                onInteract = { activateOppo() },
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** The camera brand the 数码相机 page opens on: the active engine when it is a camera, else the last one shown. */
internal fun initialCameraBrand(context: Context, store: SettingsStore): Int {
    val active = store.getInt(ModuleSettings.MASTER_LOOK)
    if (active in CAMERA_INDICES) return active
    val last = context.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE).getInt(PREF_LAST_CAMERA_STYLE, STYLE_HNCS)
    return if (last in CAMERA_INDICES) last else STYLE_HNCS
}

/** Sub-page for Digital Cameras (Hasselblad X2D HNCS / Leica M9 / Leica M240 / Leica M10-R / Fujifilm X100VI). */
@Composable
internal fun CameraLookScreen(
    contentPadding: PaddingValues,
    nestedScroll: NestedScrollConnection,
    overscrollEffect: OverscrollEffect,
    listState: LazyListState = rememberLazyListState(),
    store: SettingsStore,
    tabBrand: Int,
    onTabBrandChange: (Int) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE) }
    var activeLook by remember { mutableIntStateOf(store.getInt(ModuleSettings.MASTER_LOOK)) }
    var choiceRevision by remember { mutableIntStateOf(0) }
    var resetRevision by remember { mutableIntStateOf(0) }
    val selections = remember(store) { MasterStyle.IntLookup { key -> store.getInt(key) } }

    fun activateBrand(brandIndex: Int) {
        onTabBrandChange(brandIndex)
        activeLook = brandIndex
        store.setInt(ModuleSettings.MASTER_LOOK, brandIndex)
        prefs.edit().putInt(PREF_LAST_CAMERA_STYLE, brandIndex).apply()
        choiceRevision++
    }

    val style = MasterStyles.byIndex(tabBrand)
    val parameters = remember(tabBrand, choiceRevision) { style.params(selections) }
    val segLabels = listOf("X2D", "M9", "M240-P", "M10-R", "X100VI")
    val segSelected = CAMERA_INDICES.indexOf(tabBrand).coerceAtLeast(0)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().nestedScroll(nestedScroll),
        contentPadding = contentPadding,
        overscrollEffect = overscrollEffect,
    ) {
        item(key = "brand_bar") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CouiPageDefaults.horizontalMargin)
                    .padding(top = 12.dp, bottom = 4.dp),
            ) {
                CouiSegmentedBar(
                    labels = segLabels,
                    selectedIndex = segSelected,
                    onSelected = { idx -> activateBrand(CAMERA_INDICES[idx]) },
                )
            }
        }
        if (activeLook != tabBrand) {
            item(key = "activate_hint") {
                InactiveCategoryBanner(
                    label = "当前大师引擎未选中${segLabels[segSelected]}",
                    onActivate = { activateBrand(tabBrand) },
                )
            }
        }
        when (tabBrand) {
            STYLE_HNCS -> {
                val choice = style.choices().firstOrNull()
                if (choice != null) {
                    item(key = "hncs_groups") {
                        var selected by remember(choice.key) { mutableIntStateOf(store.getInt(choice.key)) }
                        val total = choice.labels.size
                        UiGroup(title = "色彩选项") {
                            for (optIdx in 0 until total) {
                                CouiRadioRow(
                                    title = choice.label(optIdx, selections),
                                    summary = choice.summaries?.getOrNull(optIdx)?.takeIf { it.isNotBlank() },
                                    selected = selected == optIdx && activeLook == STYLE_HNCS,
                                    onClick = {
                                        activateBrand(STYLE_HNCS)
                                        selected = optIdx
                                        store.setInt(choice.key, optIdx)
                                        choiceRevision++
                                    },
                                )
                                if (optIdx < total - 1) UiDivider()
                            }
                        }
                    }
                }
                item(key = "hncs_grain") {
                    var grainRevision by remember { mutableIntStateOf(0) }
                    val presetChoice = HncsStyle.GRAIN_PRESET
                    val typeChoice = HncsStyle.GRAIN_TYPE
                    val preset = remember(choiceRevision, grainRevision) {
                        store.getInt(presetChoice.key).coerceIn(0, presetChoice.ids.lastIndex)
                    }
                    val typeIdx = remember(choiceRevision, grainRevision) {
                        store.getInt(typeChoice.key).coerceIn(0, typeChoice.ids.lastIndex)
                    }
                    UiGroup(
                        title = "Phocus 胶片颗粒",
                        headerActions = {
                            if (preset != HncsStyle.GRAIN_PRESET_OFF) {
                                UiInfoActionPill(text = "关闭") {
                                    store.setInt(presetChoice.key, HncsStyle.GRAIN_PRESET_OFF)
                                    grainRevision++
                                    choiceRevision++
                                }
                            }
                        },
                        footer = "仅拍照成片叠加颗粒（取景预览不加颗粒），复刻 Phocus 4.1.1 CFilmGrainFilter。",
                    ) {
                        CouiMenuRow(
                            title = presetChoice.title,
                            summary = presetChoice.summaries?.getOrNull(preset)?.takeIf { it.isNotBlank() },
                            items = presetChoice.labels.toList(),
                            selectedIndex = preset,
                            onSelect = { nextIdx ->
                                activateBrand(STYLE_HNCS)
                                store.setInt(presetChoice.key, nextIdx)
                                if (nextIdx > 0 && nextIdx < HncsStyle.GRAIN_PRESET_VALUES.size) {
                                    val v = HncsStyle.GRAIN_PRESET_VALUES[nextIdx]
                                    store.setInt(typeChoice.key, v[0])
                                    HncsStyle.GRAIN_PARAMS.forEachIndexed { idx, p ->
                                        store.setInt(p.key, v[idx + 1])
                                    }
                                }
                                grainRevision++
                                choiceRevision++
                            },
                        )
                        if (preset != HncsStyle.GRAIN_PRESET_OFF) {
                            UiDivider()
                            CouiMenuRow(
                                title = typeChoice.title,
                                summary = typeChoice.summaries?.getOrNull(typeIdx)?.takeIf { it.isNotBlank() },
                                items = typeChoice.labels.toList(),
                                selectedIndex = typeIdx,
                                onSelect = { nextType ->
                                    activateBrand(STYLE_HNCS)
                                    store.setInt(typeChoice.key, nextType)
                                    store.setInt(presetChoice.key, HncsStyle.GRAIN_PRESET_CUSTOM)
                                    grainRevision++
                                    choiceRevision++
                                },
                            )
                            HncsStyle.GRAIN_PARAMS.forEach { param ->
                                UiDivider()
                                CouiSettingParam(
                                    store = store,
                                    param = param,
                                    revision = grainRevision,
                                    onChanged = {
                                        activateBrand(STYLE_HNCS)
                                        store.setInt(presetChoice.key, HncsStyle.GRAIN_PRESET_CUSTOM)
                                        choiceRevision++
                                    },
                                )
                            }
                        }
                    }
                }
            }
            STYLE_LUMIX -> {
                val choice = style.choices().firstOrNull()
                if (choice != null) {
                    item(key = "lumix_groups") {
                        var selected by remember(choice.key) { mutableIntStateOf(store.getInt(choice.key)) }
                        val total = choice.labels.size
                        val groups = listOf(
                            "彩色风格" to (0 until minOf(7, total)),
                            "黑白单色" to (7 until minOf(12, total)),
                            "电影曲线" to (12 until total),
                        ).filter { !it.second.isEmpty() }
                        Column {
                            groups.forEach { (groupTitle, range) ->
                                UiGroup(title = groupTitle) {
                                    val list = range.toList()
                                    list.forEachIndexed { idx, optIdx ->
                                        CouiRadioRow(
                                            title = choice.label(optIdx, selections),
                                            summary = choice.summaries?.getOrNull(optIdx)?.takeIf { it.isNotBlank() },
                                            selected = selected == optIdx && activeLook == STYLE_LUMIX,
                                            onClick = {
                                                activateBrand(STYLE_LUMIX)
                                                selected = optIdx
                                                store.setInt(choice.key, optIdx)
                                                choiceRevision++
                                            },
                                        )
                                        if (idx < list.lastIndex) UiDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            STYLE_CANON -> {
                val choice = style.choices().firstOrNull()
                if (choice != null) {
                    item(key = "canon_choices") {
                        var selected by remember(choice.key) { mutableIntStateOf(store.getInt(choice.key)) }
                        UiGroup(title = choice.title) {
                            choice.labels.indices.forEachIndexed { idx, optIdx ->
                                CouiRadioRow(
                                    title = choice.label(optIdx, selections),
                                    summary = choice.summaries?.getOrNull(optIdx)?.takeIf { it.isNotBlank() },
                                    selected = selected == optIdx && activeLook == STYLE_CANON,
                                    onClick = {
                                        activateBrand(STYLE_CANON)
                                        selected = optIdx
                                        store.setInt(choice.key, optIdx)
                                        choiceRevision++
                                    },
                                )
                                if (idx < choice.labels.lastIndex) UiDivider()
                            }
                        }
                    }
                }
            }
            STYLE_LEICA_M240 -> {
                val choice = style.choices().firstOrNull()
                if (choice != null) {
                    item(key = "leica_m240_modes") {
                        var selected by remember(choice.key) { mutableIntStateOf(store.getInt(choice.key)) }
                        UiGroup(title = choice.title) {
                            choice.labels.indices.forEachIndexed { idx, optIdx ->
                                CouiRadioRow(
                                    title = choice.label(optIdx, selections),
                                    summary = choice.summaries?.getOrNull(optIdx)?.takeIf { it.isNotBlank() },
                                    selected = selected == optIdx && activeLook == STYLE_LEICA_M240,
                                    onClick = {
                                        activateBrand(STYLE_LEICA_M240)
                                        selected = optIdx
                                        store.setInt(choice.key, optIdx)
                                        choiceRevision++
                                    },
                                )
                                if (idx < choice.labels.lastIndex) UiDivider()
                            }
                        }
                    }
                }
            }
            STYLE_LEICA_M10R -> {
                val choice = style.choices().firstOrNull()
                if (choice != null) {
                    item(key = "leica_m10r_modes") {
                        var selected by remember(choice.key) { mutableIntStateOf(store.getInt(choice.key)) }
                        UiGroup(title = choice.title) {
                            choice.labels.indices.forEachIndexed { idx, optIdx ->
                                CouiRadioRow(
                                    title = choice.label(optIdx, selections),
                                    summary = choice.summaries?.getOrNull(optIdx)?.takeIf { it.isNotBlank() },
                                    selected = selected == optIdx && activeLook == STYLE_LEICA_M10R,
                                    onClick = {
                                        activateBrand(STYLE_LEICA_M10R)
                                        selected = optIdx
                                        store.setInt(choice.key, optIdx)
                                        choiceRevision++
                                    },
                                )
                                if (idx < choice.labels.lastIndex) UiDivider()
                            }
                        }
                    }
                }
            }
            STYLE_X100VI -> {
                val choice = style.choices().firstOrNull()
                if (choice != null) {
                    item(key = "x100vi_groups") {
                        var selected by remember(choice.key) { mutableIntStateOf(store.getInt(choice.key)) }
                        val total = choice.labels.size
                        val groups = listOf(
                            "彩色胶片模拟" to (0 until minOf(11, total)),
                            "黑白与单色" to (11 until total),
                        ).filter { !it.second.isEmpty() }
                        Column {
                            groups.forEach { (groupTitle, range) ->
                                UiGroup(title = groupTitle) {
                                    val list = range.toList()
                                    list.forEachIndexed { idx, optIdx ->
                                        CouiRadioRow(
                                            title = choice.label(optIdx, selections),
                                            summary = choice.summaries?.getOrNull(optIdx)?.takeIf { it.isNotBlank() },
                                            selected = selected == optIdx && activeLook == STYLE_X100VI,
                                            onClick = {
                                                activateBrand(STYLE_X100VI)
                                                selected = optIdx
                                                store.setInt(choice.key, optIdx)
                                                choiceRevision++
                                            },
                                        )
                                        if (idx < list.lastIndex) UiDivider()
                                    }
                                }
                            }
                        }
                    }
                }
                item(key = "x100vi_grain") {
                    var grainRevision by remember { mutableIntStateOf(0) }
                    val strengthChoice = local.jiege.settings.X100viStyle.GRAIN_STRENGTH
                    val sizeChoice = local.jiege.settings.X100viStyle.GRAIN_SIZE
                    val strength = remember(choiceRevision, grainRevision) {
                        store.getInt(strengthChoice.key).coerceIn(0, strengthChoice.ids.lastIndex)
                    }
                    val sizeIdx = remember(choiceRevision, grainRevision) {
                        store.getInt(sizeChoice.key).coerceIn(0, sizeChoice.ids.lastIndex)
                    }
                    UiGroup(
                        title = "X100VI 胶片颗粒",
                        headerActions = {
                            if (strength != local.jiege.settings.X100viStyle.GRAIN_OFF) {
                                UiInfoActionPill(text = "关闭") {
                                    store.setInt(strengthChoice.key, local.jiege.settings.X100viStyle.GRAIN_OFF)
                                    grainRevision++
                                    choiceRevision++
                                }
                            }
                        },
                        footer = "仅拍照成片叠加颗粒（取景预览不加颗粒），按 48µm 等效传感器孔径与输出亮度包络复刻富士 X100VI 原厂颗粒。",
                    ) {
                        CouiMenuRow(
                            title = strengthChoice.title,
                            summary = strengthChoice.summaries?.getOrNull(strength)?.takeIf { it.isNotBlank() },
                            items = strengthChoice.labels.toList(),
                            selectedIndex = strength,
                            onSelect = { nextIdx ->
                                activateBrand(STYLE_X100VI)
                                store.setInt(strengthChoice.key, nextIdx)
                                grainRevision++
                                choiceRevision++
                            },
                        )
                        if (strength != local.jiege.settings.X100viStyle.GRAIN_OFF) {
                            UiDivider()
                            CouiMenuRow(
                                title = sizeChoice.title,
                                summary = sizeChoice.summaries?.getOrNull(sizeIdx)?.takeIf { it.isNotBlank() },
                                items = sizeChoice.labels.toList(),
                                selectedIndex = sizeIdx,
                                onSelect = { nextSize ->
                                    activateBrand(STYLE_X100VI)
                                    store.setInt(sizeChoice.key, nextSize)
                                    grainRevision++
                                    choiceRevision++
                                },
                            )
                        }
                    }
                }
            }
        }
        if (parameters.isNotEmpty()) {
            item(key = "camera_params") {
                UiGroup(
                    title = "参数调节",
                    headerActions = {
                        UiInfoActionPill(text = "恢复默认") {
                            parameters.forEach { store.setInt(it.key, it.def) }
                            resetRevision++
                        }
                    },
                ) {
                    parameters.forEachIndexed { idx, param ->
                        CouiSettingParam(store = store, param = param, revision = resetRevision)
                        if (idx < parameters.lastIndex) UiDivider()
                    }
                }
            }
        }
        item(key = "camera_exposure") {
            val lookId = remember(tabBrand, choiceRevision) { style.lookId(selections) }
            ExposureCompensationGroup(
                store = store,
                styleIndex = tabBrand,
                lookId = lookId,
                revision = choiceRevision,
                onInteract = { activateBrand(tabBrand) },
            )
        }
        item(key = "camera_white_point") {
            WhitePointOffsetGroup(
                store = store,
                styleIndex = tabBrand,
                revision = choiceRevision,
                onInteract = { activateBrand(tabBrand) },
            )
        }
        item(key = "camera_optical_vignette") {
            OpticalVignetteEngineGroup(
                store = store,
                styleIndex = tabBrand,
                onInteract = { activateBrand(tabBrand) },
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

private val SPATIAL_CHOICE_KEYS = setOf(
    "master_look_grain",
    "master_look_halation",
    "master_look_frame",
    "master_look_boost",
)

private val EMULSION_PARAM_KEYS = setOf(
    "master_film_age",
    "master_film_preflash",
    "master_film_bleach",
    "master_film_dir",
)

private const val CC_FILTER_GROUP = "CC补偿"

private data class CcFilterChannel(val suffix: Char, val label: String)

private val CC_FILTER_CHANNELS = listOf(
    CcFilterChannel('r', "红色"),
    CcFilterChannel('g', "绿色"),
    CcFilterChannel('b', "蓝色"),
    CcFilterChannel('c', "青色"),
    CcFilterChannel('m', "品红"),
    CcFilterChannel('y', "黄色"),
)

private val CC_FILTER_DENSITIES = listOf("05", "10", "20", "30", "40", "50")

@Composable
private fun FilmParamGroupCard(
    title: String,
    store: SettingsStore,
    params: List<MasterStyle.Param>,
    revision: Int,
    onReset: () -> Unit,
    onShowInfo: (InfoGuideEntry) -> Unit,
) {
    UiGroup(
        title = title,
        headerActions = {
            UiInfoActionPill(text = "恢复默认", onClick = onReset)
        },
    ) {
        params.forEachIndexed { idx, param ->
            val paramInfo = FilmGuide.param(param.key)
            CouiSettingParam(
                store = store,
                param = param,
                revision = revision,
                onInfoClick = paramInfo?.let { info -> { onShowInfo(info) } },
            )
            if (idx < params.lastIndex) UiDivider()
        }
    }
}

/** Sub-page for Film simulation: inline group chips + film radios + expanded process cards + darkroom sliders. */
@Composable
internal fun FilmLookScreen(
    contentPadding: PaddingValues,
    nestedScroll: NestedScrollConnection,
    overscrollEffect: OverscrollEffect,
    listState: LazyListState = rememberLazyListState(),
    store: SettingsStore,
) {
    val style = MasterStyles.byIndex(STYLE_FILM)
    val context = LocalContext.current
    val selections = remember(store) { MasterStyle.IntLookup { key -> store.getInt(key) } }
    var activeLook by remember { mutableIntStateOf(store.getInt(ModuleSettings.MASTER_LOOK)) }
    var choiceRevision by remember { mutableIntStateOf(0) }
    var resetRevision by remember { mutableIntStateOf(0) }
    var activeInfo by remember { mutableStateOf<InfoGuideEntry?>(null) }

    val choices = style.choices()
    val filmChoice = choices.firstOrNull()
    val filterChoice = choices.firstOrNull { it.key == "master_look_filter" }
    val spatialChoices = choices.filter { it.key in SPATIAL_CHOICE_KEYS }
    val processChoices = choices.drop(1).filter {
        it.key != "master_look_filter" && it.key !in SPATIAL_CHOICE_KEYS
    }
    var selectedFilm by remember(filmChoice?.key) { mutableIntStateOf(filmChoice?.let { store.getInt(it.key) } ?: 0) }
    val safeFilm = if (filmChoice != null && filmChoice.offered(selectedFilm, selections)) selectedFilm else 0
    val filmGroups = remember(filmChoice?.key) {
        (filmChoice?.summaries?.distinct()?.filter { it.isNotBlank() }).orEmpty()
    }
    var selectedGroup by rememberSaveable(filmChoice?.key) {
        mutableStateOf(filmChoice?.summaries?.getOrNull(safeFilm) ?: filmGroups.firstOrNull().orEmpty())
    }
    val matchingFilmIndices = remember(filmChoice?.key, selectedGroup, choiceRevision) {
        if (filmChoice == null) emptyList()
        else filmChoice.labels.indices.filter { idx ->
            filmChoice.offered(idx, selections) &&
                (selectedGroup.isEmpty() || filmChoice.summaries?.getOrNull(idx) == selectedGroup)
        }
    }

    fun activateFilm() {
        activeLook = STYLE_FILM
        store.setInt(ModuleSettings.MASTER_LOOK, STYLE_FILM)
        choiceRevision++
    }

    val visibleProcesses = remember(choiceRevision) {
        processChoices.filter { it.visible.test(selections) }
    }
    val visibleSpatial = remember(choiceRevision) {
        spatialChoices.filter { it.visible.test(selections) }
    }
    val parameters = remember(choiceRevision) {
        style.params(selections)
    }
    val emulsionParams = remember(parameters) {
        parameters.filter { it.key in EMULSION_PARAM_KEYS }
    }
    val darkroomParams = remember(parameters) {
        parameters.filter { it.key !in EMULSION_PARAM_KEYS }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().nestedScroll(nestedScroll),
        contentPadding = contentPadding,
        overscrollEffect = overscrollEffect,
    ) {
        if (activeLook != STYLE_FILM) {
            item(key = "activate_hint") {
                InactiveCategoryBanner(
                    label = "当前大师引擎未选中胶片",
                    onActivate = { activateFilm() },
                )
            }
        }
        if (filmChoice != null) {
            if (filmGroups.isNotEmpty()) {
                item(key = "film_chips") {
                    Spacer(Modifier.height(10.dp))
                    CouiChipFilterBar(
                        items = filmGroups.map { CouiChipTabItem(key = it, label = it) },
                        selectedKey = selectedGroup,
                        onSelectedKeyChange = { selectedGroup = it as String },
                    )
                }
            }
            item(key = "film_card") {
                val activeFilmName = filmChoice.label(safeFilm, selections)
                UiGroup(
                    title = "胶卷",
                    footer = if (safeFilm !in matchingFilmIndices) "当前使用：$activeFilmName" else null,
                ) {
                    matchingFilmIndices.forEachIndexed { pos, idx ->
                        val filmId = filmChoice.ids[idx]
                        val filmLabel = filmChoice.label(idx, selections)
                        val filmInfo = FilmGuide.film(filmId, filmLabel)
                        CouiRadioRow(
                            title = filmLabel,
                            selected = idx == safeFilm && activeLook == STYLE_FILM,
                            trailingExtra = filmInfo?.let { info ->
                                {
                                    CouiInfoIconButton("了解 $filmLabel") {
                                        val card = buildFilmSwatchCard(
                                            assets = context.assets,
                                            style = style,
                                            filmChoice = filmChoice,
                                            filmIdx = idx,
                                            isSelectedFilm = idx == safeFilm,
                                            selections = selections,
                                            store = store,
                                        )
                                        activeInfo = if (card != null) info.copy(swatchCard = card) else info
                                    }
                                }
                            },
                            onClick = {
                                activateFilm()
                                selectedFilm = idx
                                store.setInt(filmChoice.key, idx)
                                choiceRevision++
                            },
                        )
                        if (pos < matchingFilmIndices.lastIndex) UiDivider()
                    }
                }
            }
        }
        if (filterChoice != null) {
            item(key = "film_filter_card") {
                val storedFilter = run {
                    choiceRevision
                    store.getInt(filterChoice.key)
                }
                val effectiveFilter = if (storedFilter in filterChoice.labels.indices && filterChoice.offered(storedFilter, selections)) storedFilter else 0
                val filterGroups = remember(filterChoice.key) {
                    filterChoice.summaries?.distinct()?.filter { it.isNotBlank() }.orEmpty()
                }
                val activeFilterGroup = filterChoice.summaries?.getOrNull(effectiveFilter) ?: filterGroups.firstOrNull().orEmpty()
                val groupInfo = FilmGuide.choiceGroup(filterChoice.key)
                UiGroup(
                    title = filterChoice.title,
                    headerActions = {
                        if (groupInfo != null) {
                            CouiInfoIconButton("了解 ${filterChoice.title}") { activeInfo = groupInfo }
                        }
                    },
                ) {
                    CouiMenuRow(
                        title = "滤镜分类",
                        items = filterGroups,
                        selectedIndex = filterGroups.indexOf(activeFilterGroup).coerceAtLeast(0),
                        onSelect = { groupPos ->
                            val targetGroup = filterGroups.getOrNull(groupPos) ?: return@CouiMenuRow
                            val nextIdx = filterChoice.labels.indices.firstOrNull {
                                filterChoice.summaries?.getOrNull(it) == targetGroup && filterChoice.offered(it, selections)
                            } ?: 0
                            activateFilm()
                            store.setInt(filterChoice.key, nextIdx)
                            choiceRevision++
                        },
                    )
                    if (activeFilterGroup == CC_FILTER_GROUP) {
                        val currentId = filterChoice.ids.getOrNull(effectiveFilter).orEmpty()
                        val channelIdx = CC_FILTER_CHANNELS
                            .indexOfFirst { currentId.endsWith(it.suffix) }
                            .coerceAtLeast(0)
                        val densityIdx = CC_FILTER_DENSITIES
                            .indexOfFirst { d -> currentId.startsWith("cc$d") }
                            .coerceIn(0, CC_FILTER_DENSITIES.lastIndex)
                        val activeChannel = CC_FILTER_CHANNELS[channelIdx]
                        var sliderStep by remember(effectiveFilter, choiceRevision) {
                            mutableFloatStateOf(densityIdx.toFloat())
                        }
                        var dirty by remember(filterChoice.key) { mutableStateOf(false) }
                        val clampedStep = sliderStep.roundToInt().coerceIn(0, CC_FILTER_DENSITIES.lastIndex)
                        val previewId = "cc${CC_FILTER_DENSITIES[clampedStep]}${activeChannel.suffix}"
                        val previewIdx = filterChoice.ids.indexOf(previewId).let {
                            if (it >= 0) it else effectiveFilter
                        }
                        val latestPreviewIdx by rememberUpdatedState(previewIdx)
                        val latestDirty by rememberUpdatedState(dirty)
                        DisposableEffect(store, filterChoice.key) {
                            onDispose {
                                if (latestDirty) store.setInt(filterChoice.key, latestPreviewIdx)
                            }
                        }
                        UiDivider()
                        CouiMenuRow(
                            title = "补偿色彩",
                            items = CC_FILTER_CHANNELS.map { it.label },
                            selectedIndex = channelIdx,
                            onSelect = { nextCh ->
                                val nextChannel = CC_FILTER_CHANNELS.getOrNull(nextCh) ?: return@CouiMenuRow
                                val targetId = "cc${CC_FILTER_DENSITIES[clampedStep]}${nextChannel.suffix}"
                                val nextIdx = filterChoice.ids.indexOf(targetId).takeIf { it >= 0 } ?: return@CouiMenuRow
                                dirty = false
                                activateFilm()
                                store.setInt(filterChoice.key, nextIdx)
                                choiceRevision++
                            },
                        )
                        UiDivider()
                        CouiSliderRow(
                            title = "补偿浓度",
                            valueLabel = filterChoice.label(previewIdx, selections),
                            value = sliderStep,
                            valueRange = 0f..CC_FILTER_DENSITIES.lastIndex.toFloat(),
                            steps = CC_FILTER_DENSITIES.size - 2,
                            showTickMarks = true,
                            summary = FilmStyle.filterSummary(previewIdx).takeIf { it.isNotBlank() },
                            onValueChange = { next ->
                                sliderStep = next.coerceIn(0f, CC_FILTER_DENSITIES.lastIndex.toFloat())
                                dirty = true
                            },
                            onValueChangeFinished = {
                                dirty = false
                                activateFilm()
                                store.setInt(filterChoice.key, latestPreviewIdx)
                                choiceRevision++
                            },
                        )
                    } else if (effectiveFilter > 0 || activeFilterGroup != filterGroups.firstOrNull()) {
                        val groupIndices = remember(filterChoice.key, activeFilterGroup, choiceRevision) {
                            filterChoice.labels.indices.filter {
                                filterChoice.summaries?.getOrNull(it) == activeFilterGroup && filterChoice.offered(it, selections)
                            }
                        }
                        if (groupIndices.isNotEmpty()) {
                            UiDivider()
                            CouiMenuRow(
                                title = "滤镜型号",
                                summary = FilmStyle.filterSummary(effectiveFilter).takeIf { it.isNotBlank() },
                                items = groupIndices.map { filterChoice.label(it, selections) },
                                selectedIndex = groupIndices.indexOf(effectiveFilter).coerceAtLeast(0),
                                onSelect = { pos ->
                                    val nextIdx = groupIndices.getOrNull(pos) ?: return@CouiMenuRow
                                    activateFilm()
                                    store.setInt(filterChoice.key, nextIdx)
                                    choiceRevision++
                                },
                            )
                        }
                    }
                }
            }
        }
        visibleProcesses.forEach { choice ->
            item(key = "film_process_${choice.key}") {
                var stored by remember(choice.key) { mutableIntStateOf(store.getInt(choice.key)) }
                val offered = remember(choice.key, choiceRevision, safeFilm) {
                    val raw = choice.labels.indices.filter { choice.offered(it, selections) }
                    if (choice.key == "master_look_push") {
                        raw.sortedWith(compareBy<Int> { if (it == 0) 0 else 1 }.thenBy { FilmStyle.pushStopsAt(it) })
                    } else {
                        raw
                    }
                }
                val effective = if (choice.offered(stored, selections)) stored else 0
                val groupInfo = FilmGuide.choiceGroup(choice.key)
                val isFinishChoice = choice.key == "master_look_finish"
                val isPushChoice = choice.key == "master_look_push"
                val isReflectionFilm = isFinishChoice && offered.size == 1
                val isPositiveFinish = isFinishChoice && offered.none { it == 1 }
                val currentFilmId = filmChoice?.ids?.getOrNull(safeFilm).orEmpty()
                val currentFilmIsPositive = filmChoice != null && FilmStyle.isPositive(safeFilm)
                UiGroup(
                    title = choice.title,
                    headerActions = {
                        if (groupInfo != null) {
                            CouiInfoIconButton("了解 ${choice.title}") { activeInfo = groupInfo }
                        }
                    },
                ) {
                    offered.forEachIndexed { idx, optIdx ->
                        val isDefaultFinish = isFinishChoice && optIdx == 0
                        val isDefaultLight = choice.key == "master_look_light" && optIdx == 0
                        val rowTitle = when {
                            isDefaultFinish || isDefaultLight -> "默认"
                            else -> choice.label(optIdx, selections)
                        }
                        val rowSummary = when {
                            isDefaultFinish -> choice.label(0, selections).removeSuffix("（原配）")
                            isPushChoice -> FilmGuide.pushSummary(choice.ids[optIdx], currentFilmIsPositive)
                            else -> choice.summaries?.getOrNull(optIdx)
                        }?.takeIf { it.isNotBlank() }
                        val rowInfo = when {
                            isFinishChoice -> FilmGuide.finishOption(
                                finishId = choice.ids[optIdx],
                                resolvedLabel = choice.label(optIdx, selections),
                                isPositiveFilm = isPositiveFinish,
                                isReflectionFilm = isReflectionFilm,
                            )
                            isPushChoice -> FilmGuide.pushOption(
                                pushId = choice.ids[optIdx],
                                resolvedLabel = rowTitle,
                                filmId = currentFilmId,
                                isPositiveFilm = currentFilmIsPositive,
                            )
                            else -> null
                        }
                        CouiRadioRow(
                            title = rowTitle,
                            summary = rowSummary,
                            selected = effective == optIdx && activeLook == STYLE_FILM,
                            trailingExtra = rowInfo?.let { info ->
                                {
                                    CouiInfoIconButton("了解 $rowTitle") { activeInfo = info }
                                }
                            },
                            onClick = {
                                activateFilm()
                                stored = optIdx
                                store.setInt(choice.key, optIdx)
                                choiceRevision++
                            },
                        )
                        if (idx < offered.lastIndex) UiDivider()
                    }
                }
            }
        }
        if (visibleSpatial.isNotEmpty()) {
            item(key = "spatial_card") {
                UiGroup(
                    title = "颗粒与光晕",
                    headerActions = {
                        CouiInfoIconButton("了解 颗粒与光晕") {
                            activeInfo = FilmGuide.spatialOverview()
                        }
                    },
                ) {
                    visibleSpatial.forEachIndexed { idx, choice ->
                        val stored = remember(choice.key, choiceRevision) { store.getInt(choice.key) }
                        val offered = remember(choice.key, choiceRevision, safeFilm) {
                            choice.labels.indices.filter { choice.offered(it, selections) }
                        }
                        val effective = if (choice.offered(stored, selections)) stored else 0
                        val selectedPos = offered.indexOf(effective).coerceAtLeast(0)
                        val offeredLabels = offered.map { choice.label(it, selections) }
                        val rowSummary = choice.summaries?.getOrNull(effective)?.takeIf { it.isNotBlank() }
                        CouiMenuRow(
                            title = choice.title,
                            summary = rowSummary,
                            items = offeredLabels,
                            selectedIndex = selectedPos,
                            onSelect = { pos ->
                                val nextIdx = offered.getOrNull(pos) ?: return@CouiMenuRow
                                activateFilm()
                                store.setInt(choice.key, nextIdx)
                                choiceRevision++
                            },
                        )
                        if (idx < visibleSpatial.lastIndex) UiDivider()
                    }
                }
            }
        }
        if (emulsionParams.isNotEmpty()) {
            item(key = "emulsion_params_card") {
                FilmParamGroupCard(
                    title = "底片与显影",
                    store = store,
                    params = emulsionParams,
                    revision = resetRevision,
                    onReset = {
                        emulsionParams.forEach { store.setInt(it.key, it.def) }
                        resetRevision++
                    },
                    onShowInfo = { activeInfo = it },
                )
            }
        }
        if (darkroomParams.isNotEmpty()) {
            item(key = "darkroom_params_card") {
                FilmParamGroupCard(
                    title = "印相与暗房",
                    store = store,
                    params = darkroomParams,
                    revision = resetRevision,
                    onReset = {
                        darkroomParams.forEach { store.setInt(it.key, it.def) }
                        resetRevision++
                    },
                    onShowInfo = { activeInfo = it },
                )
            }
        }
        item(key = "film_exposure") {
            val lookId = remember(choiceRevision, safeFilm) { style.lookId(selections) }
            ExposureCompensationGroup(
                store = store,
                styleIndex = STYLE_FILM,
                lookId = lookId,
                revision = choiceRevision,
                onInteract = { activateFilm() },
            )
        }
        item(key = "film_white_point") {
            WhitePointOffsetGroup(
                store = store,
                styleIndex = STYLE_FILM,
                revision = choiceRevision,
                onInteract = { activateFilm() },
            )
        }
        item(key = "film_optical_vignette") {
            OpticalVignetteEngineGroup(
                store = store,
                styleIndex = STYLE_FILM,
                onInteract = { activateFilm() },
            )
        }
        item(key = "bottom_space") {
            Spacer(Modifier.height(16.dp))
        }
    }

    CouiInfoDialog(entry = activeInfo, onDismiss = { activeInfo = null })
}

/** Sub-page for Adobe (Basic + Fujifilm + Sigma + OM System + Sony). */
@Composable
internal fun AdobeLookScreen(
    contentPadding: PaddingValues,
    nestedScroll: NestedScrollConnection,
    overscrollEffect: OverscrollEffect,
    listState: LazyListState = rememberLazyListState(),
    store: SettingsStore,
) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE) }
    var activeLook by remember { mutableIntStateOf(store.getInt(ModuleSettings.MASTER_LOOK)) }
    var tabAdobe by remember {
        mutableIntStateOf(
            if (activeLook in ADOBE_INDICES) activeLook
            else prefs.getInt(PREF_LAST_ADOBE_STYLE, STYLE_BASIC).let { if (it in ADOBE_INDICES) it else STYLE_BASIC },
        )
    }
    var choiceRevision by remember { mutableIntStateOf(0) }
    var resetRevision by remember { mutableIntStateOf(0) }
    val selections = remember(store) { MasterStyle.IntLookup { key -> store.getInt(key) } }

    fun activateAdobe(idx: Int) {
        tabAdobe = idx
        activeLook = idx
        store.setInt(ModuleSettings.MASTER_LOOK, idx)
        prefs.edit().putInt(PREF_LAST_ADOBE_STYLE, idx).apply()
        choiceRevision++
    }

    val style = MasterStyles.byIndex(tabAdobe)
    val segLabels = listOf("基础", "适马", "奥之心", "索尼", "尼康")
    val segSelected = ADOBE_INDICES.indexOf(tabAdobe).coerceAtLeast(0)
    val parameters = remember(tabAdobe, choiceRevision) { style.params(selections) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().nestedScroll(nestedScroll),
        contentPadding = contentPadding,
        overscrollEffect = overscrollEffect,
    ) {
        item(key = "adobe_seg") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CouiPageDefaults.horizontalMargin)
                    .padding(top = 12.dp, bottom = 4.dp),
            ) {
                CouiSegmentedBar(
                    labels = segLabels,
                    selectedIndex = segSelected,
                    onSelected = { idx -> activateAdobe(ADOBE_INDICES[idx]) },
                )
            }
        }
        if (activeLook != tabAdobe) {
            item(key = "activate_hint") {
                InactiveCategoryBanner(
                    label = "当前大师引擎未选中${segLabels[segSelected]}",
                    onActivate = { activateAdobe(tabAdobe) },
                )
            }
        }
        style.choices().forEachIndexed { pos, choice ->
            val visible = run {
                choiceRevision
                choice.visible.test(selections)
            }
            if (visible) {
                item(key = "adobe_choice_${tabAdobe}_$pos") {
                    var selected by remember(choice.key) { mutableIntStateOf(store.getInt(choice.key)) }
                    val offered = remember(choice.key, choiceRevision) {
                        choice.labels.indices.filter { choice.offered(it, selections) }
                    }
                    val effective = if (choice.offered(selected, selections)) selected else 0
                    UiGroup(title = choice.title) {
                        offered.forEachIndexed { idx, optIdx ->
                            CouiRadioRow(
                                title = choice.label(optIdx, selections),
                                summary = choice.summaries?.getOrNull(optIdx)?.takeIf { it.isNotBlank() },
                                selected = effective == optIdx && activeLook == tabAdobe,
                                onClick = {
                                    activateAdobe(tabAdobe)
                                    selected = optIdx
                                    store.setInt(choice.key, optIdx)
                                    choiceRevision++
                                },
                            )
                            if (idx < offered.lastIndex) UiDivider()
                        }
                    }
                }
            }
        }
        if (parameters.isNotEmpty()) {
            item(key = "adobe_params") {
                UiGroup(
                    title = "参数调节",
                    headerActions = {
                        UiInfoActionPill(text = "恢复默认") {
                            parameters.forEach { store.setInt(it.key, it.def) }
                            resetRevision++
                        }
                    },
                ) {
                    parameters.forEachIndexed { idx, param ->
                        CouiSettingParam(store = store, param = param, revision = resetRevision)
                        if (idx < parameters.lastIndex) UiDivider()
                    }
                }
            }
        }
        item(key = "adobe_exposure") {
            val lookId = remember(tabAdobe, choiceRevision) { style.lookId(selections) }
            ExposureCompensationGroup(
                store = store,
                styleIndex = tabAdobe,
                lookId = lookId,
                revision = choiceRevision,
                onInteract = { activateAdobe(tabAdobe) },
            )
        }
        item(key = "adobe_white_point") {
            WhitePointOffsetGroup(
                store = store,
                styleIndex = tabAdobe,
                revision = choiceRevision,
                onInteract = { activateAdobe(tabAdobe) },
            )
        }
        item(key = "adobe_optical_vignette") {
            OpticalVignetteEngineGroup(
                store = store,
                styleIndex = tabAdobe,
                onInteract = { activateAdobe(tabAdobe) },
            )
        }
        item(key = "bottom_space") {
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Sub-page for Tone Mapping (Darktable Sigmoid / Filmic / AgX). */
@Composable
internal fun ToneMapLookScreen(
    contentPadding: PaddingValues,
    nestedScroll: NestedScrollConnection,
    overscrollEffect: OverscrollEffect,
    listState: LazyListState = rememberLazyListState(),
    store: SettingsStore,
) {
    var activeLook by remember { mutableIntStateOf(store.getInt(ModuleSettings.MASTER_LOOK)) }
    var choiceRevision by remember { mutableIntStateOf(0) }
    var resetRevision by remember { mutableIntStateOf(0) }
    val selections = remember(store) { MasterStyle.IntLookup { key -> store.getInt(key) } }
    val style = MasterStyles.byIndex(STYLE_DARKTABLE)
    val parameters = remember(choiceRevision) { style.params(selections) }

    fun activateToneMap() {
        activeLook = STYLE_DARKTABLE
        store.setInt(ModuleSettings.MASTER_LOOK, STYLE_DARKTABLE)
        choiceRevision++
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().nestedScroll(nestedScroll),
        contentPadding = contentPadding,
        overscrollEffect = overscrollEffect,
    ) {
        if (activeLook != STYLE_DARKTABLE) {
            item(key = "activate_hint") {
                InactiveCategoryBanner(
                    label = "当前大师引擎未选中色调映射",
                    onActivate = { activateToneMap() },
                )
            }
        }
        style.choices().forEachIndexed { pos, choice ->
            item(key = "tonemap_choice_$pos") {
                var selected by remember(choice.key) { mutableIntStateOf(store.getInt(choice.key)) }
                val effective = if (choice.offered(selected, selections)) selected else 0
                UiGroup(title = choice.title) {
                    choice.labels.indices.forEachIndexed { idx, optIdx ->
                        CouiRadioRow(
                            title = choice.label(optIdx, selections),
                            summary = choice.summaries?.getOrNull(optIdx)?.takeIf { it.isNotBlank() },
                            selected = effective == optIdx && activeLook == STYLE_DARKTABLE,
                            onClick = {
                                activateToneMap()
                                selected = optIdx
                                store.setInt(choice.key, optIdx)
                                choiceRevision++
                            },
                        )
                        if (idx < choice.labels.lastIndex) UiDivider()
                    }
                }
            }
        }
        if (parameters.isNotEmpty()) {
            item(key = "tonemap_params") {
                UiGroup(
                    title = "参数调节",
                    headerActions = {
                        UiInfoActionPill(text = "恢复默认") {
                            parameters.forEach { store.setInt(it.key, it.def) }
                            resetRevision++
                        }
                    },
                ) {
                    parameters.forEachIndexed { idx, param ->
                        CouiSettingParam(store = store, param = param, revision = resetRevision)
                        if (idx < parameters.lastIndex) UiDivider()
                    }
                }
            }
        }
        item(key = "tonemap_exposure") {
            val lookId = remember(choiceRevision) { style.lookId(selections) }
            ExposureCompensationGroup(
                store = store,
                styleIndex = STYLE_DARKTABLE,
                lookId = lookId,
                revision = choiceRevision,
                onInteract = { activateToneMap() },
            )
        }
        item(key = "tonemap_white_point") {
            WhitePointOffsetGroup(
                store = store,
                styleIndex = STYLE_DARKTABLE,
                revision = choiceRevision,
                onInteract = { activateToneMap() },
            )
        }
        item(key = "tonemap_optical_vignette") {
            OpticalVignetteEngineGroup(
                store = store,
                styleIndex = STYLE_DARKTABLE,
                onInteract = { activateToneMap() },
            )
        }
        item(key = "bottom_space") {
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun OpticalVignetteEngineGroup(
    store: SettingsStore,
    styleIndex: Int,
    onInteract: () -> Unit,
) {
    if (store.getInt(ModuleSettings.MASTER_FILM_VIGNETTE) != ModuleSettings.VIGNETTE_FOLLOW_ENGINE) return
    val vigKey = remember(styleIndex) { ModuleSettings.vignetteKey(styleIndex) }
    val compKey = remember(styleIndex) { ModuleSettings.vignetteCompKey(styleIndex) }
    var vigOn by remember(vigKey) { mutableStateOf(store.getInt(vigKey) > 0) }
    UiGroup(title = "光学暗角") {
        CouiSwitchRow(
            title = "光学暗角模拟",
            summary = "依当前焦段与画幅计算物理 cos⁴θ 暗角并关闭原生暗角",
            checked = vigOn,
            onCheckedChange = { next ->
                onInteract()
                vigOn = next
                store.setInt(vigKey, if (next) 1 else 0)
            },
        )
        if (styleIndex != STYLE_OPPO && vigOn) {
            UiDivider()
            OpticalVignetteCompSlider(
                store = store,
                compKey = compKey,
                enabled = true,
            )
        }
    }
}

@Composable
private fun ExposureCompensationGroup(
    store: SettingsStore,
    styleIndex: Int,
    lookId: String,
    revision: Int,
    onInteract: () -> Unit,
) {
    val modeKey = remember(styleIndex) { MasterAlign.exposureModeKey(styleIndex) }
    val alignKey = remember(styleIndex) { MasterAlign.alignKey(styleIndex) }
    val offsetParam = remember(styleIndex) { MasterAlign.offsetParam(styleIndex) }
    val canAlign = remember(lookId) { MasterAlign.covered(lookId) }
    var storedExpMode by remember(modeKey) { mutableIntStateOf(store.getInt(modeKey)) }
    val effectiveExpMode = if (canAlign) storedExpMode else MasterAlign.MODE_OFFSET
    UiGroup(
        title = "曝光补偿",
        footer = if (!canAlign) "当前风格未收录对齐数据，仅支持手动偏移。" else null,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            CouiSegmentedBar(
                labels = listOf("偏移量", "对齐原厂"),
                selectedIndex = if (effectiveExpMode == MasterAlign.MODE_ALIGN) 1 else 0,
                enabled = canAlign,
                onSelected = { idx ->
                    val next = if (idx == 1) MasterAlign.MODE_ALIGN else MasterAlign.MODE_OFFSET
                    storedExpMode = next
                    store.setInt(modeKey, next)
                    onInteract()
                },
            )
        }
        UiDivider()
        if (effectiveExpMode == MasterAlign.MODE_OFFSET) {
            CouiSettingParam(
                store = store,
                param = offsetParam,
                revision = revision,
                enabled = true,
                onChanged = onInteract,
            )
        } else {
            val availableIndices = remember(lookId) {
                MasterAlign.LABELS.indices.filter { MasterAlign.available(lookId, it) }
            }
            var align by remember(alignKey, lookId) {
                val stored = store.getInt(alignKey)
                mutableIntStateOf(if (MasterAlign.available(lookId, stored)) stored else MasterAlign.NONE)
            }
            val menuLabels = remember(lookId) {
                availableIndices.map { MasterAlign.LABELS[it] }
            }
            val selectedPos = availableIndices.indexOf(align).coerceAtLeast(0)
            CouiMenuRow(
                title = "对齐目标",
                summary = MasterAlign.SUMMARIES.getOrNull(align),
                items = menuLabels,
                selectedIndex = selectedPos,
                onSelect = { pos ->
                    val target = availableIndices[pos]
                    align = target
                    store.setInt(alignKey, target)
                    onInteract()
                },
            )
        }
    }
}

@Composable
private fun WhitePointOffsetGroup(
    store: SettingsStore,
    styleIndex: Int,
    revision: Int,
    onInteract: () -> Unit,
) {
    val tempParam = remember(styleIndex) { MasterAlign.wbTempParam(styleIndex) }
    val tintParam = remember(styleIndex) { MasterAlign.wbTintParam(styleIndex) }
    var resetRev by remember(styleIndex) { mutableIntStateOf(0) }
    UiGroup(
        title = "白点偏移",
        headerActions = {
            UiInfoActionPill(text = "归零") {
                store.setInt(tempParam.key, 0)
                store.setInt(tintParam.key, 0)
                resetRev++
                onInteract()
            }
        },
        footer = "在色彩引擎内部做 Bradford 色度适应（±800 K 与青品偏移），不改变手机原生 AWB。",
    ) {
        CouiSettingParam(
            store = store,
            param = tempParam,
            revision = revision + resetRev,
            enabled = true,
            onChanged = onInteract,
        )
        UiDivider()
        CouiSettingParam(
            store = store,
            param = tintParam,
            revision = revision + resetRev,
            enabled = true,
            onChanged = onInteract,
        )
    }
}

@Composable
private fun InactiveCategoryBanner(label: String, onActivate: () -> Unit) {
    UiGroup {
        UiInfoRow(
            title = label,
            summary = "点按下方任意项即自动启用",
            value = "立即启用",
            valueColor = CouiTheme.colorScheme.accentOnSurface,
            onClick = onActivate,
        )
    }
}

private fun styleOptionSummary(styleIndex: Int, store: SettingsStore, selections: MasterStyle.IntLookup): String {
    val style = MasterStyles.byIndex(styleIndex)
    val firstChoice = style.choices().firstOrNull() ?: return style.label
    val raw = store.getInt(firstChoice.key)
    val idx = if (firstChoice.offered(raw, selections)) raw else 0
    val chosenLabel = firstChoice.label(idx, selections)
    return if (styleIndex == STYLE_FILM) {
        val process = style.choices().getOrNull(2)?.takeIf { it.visible.test(selections) }?.let { ch ->
            val pRaw = store.getInt(ch.key)
            val pIdx = if (ch.offered(pRaw, selections)) pRaw else 0
            if (pIdx == 0) " · 默认" else " · " + ch.label(pIdx, selections)
        }.orEmpty()
        "$chosenLabel$process"
    } else {
        "${style.label} · $chosenLabel"
    }
}

private fun buildFilmSwatchCard(
    assets: AssetManager,
    style: MasterStyle,
    filmChoice: MasterStyle.Choice,
    filmIdx: Int,
    isSelectedFilm: Boolean,
    selections: MasterStyle.IntLookup,
    store: SettingsStore,
): FilmSwatchCardData? {
    val lookup = if (isSelectedFilm) {
        selections
    } else {
        MasterStyle.IntLookup { key ->
            if (key == filmChoice.key) filmIdx else ModuleSettings.defaultInt(key)
        }
    }
    val lookId = style.lookId(lookup)
    val params = HashMap<String, Float>(style.settings(lookup))
    val activeParams = style.params(lookup)
    for (p in activeParams) {
        val clamped = lookup.get(p.key).coerceIn(p.min, p.max)
        params[p.id] = clamped.toFloat() / p.scale.toFloat()
    }
    val ev = if (isSelectedFilm) {
        MasterAlign.evFor(
            lookId,
            store.getInt(MasterAlign.exposureModeKey(STYLE_FILM)),
            store.getInt(MasterAlign.exposureKey(STYLE_FILM)),
            store.getInt(MasterAlign.alignKey(STYLE_FILM)),
        )
    } else {
        0f
    }
    if (ev != 0f) params["exposure"] = ev
    if (isSelectedFilm) {
        val wbTemp = store.getInt(MasterAlign.wbTempKey(STYLE_FILM)).coerceIn(-800, 800)
        val wbTint = store.getInt(MasterAlign.wbTintKey(STYLE_FILM)).coerceIn(-50, 50)
        if (wbTemp != 0) params["wb_temp"] = wbTemp.toFloat()
        if (wbTint != 0) params["wb_tint"] = wbTint.toFloat()
    }

    val out = FloatArray(FilmChartNative.OUT_FLOATS)
    if (!FilmChartNative.render(assets, lookId, params, out)) return null

    val refColors = ArrayList<Color>(FilmChartNative.PATCHES)
    val filmColors = ArrayList<Color>(FilmChartNative.PATCHES)
    val filmOffset = FilmChartNative.PATCHES * 3
    for (i in 0 until FilmChartNative.PATCHES) {
        val rOff = i * 3
        refColors.add(
            Color(
                red = out[rOff].coerceIn(0f, 1f),
                green = out[rOff + 1].coerceIn(0f, 1f),
                blue = out[rOff + 2].coerceIn(0f, 1f),
                alpha = 1f,
                colorSpace = ColorSpaces.DisplayP3,
            ),
        )
        val fOff = filmOffset + rOff
        filmColors.add(
            Color(
                red = out[fOff].coerceIn(0f, 1f),
                green = out[fOff + 1].coerceIn(0f, 1f),
                blue = out[fOff + 2].coerceIn(0f, 1f),
                alpha = 1f,
                colorSpace = ColorSpaces.DisplayP3,
            ),
        )
    }

    val choices = style.choices()
    val finishChoice = choices.getOrNull(2)
    val pushChoice = choices.getOrNull(1)
    val finishLabel = finishChoice?.let { ch ->
        val raw = lookup.get(ch.key)
        val eff = if (ch.offered(raw, lookup)) raw else 0
        ch.label(eff, lookup)
            .removeSuffix("（原配）")
            .replace("Kodak ", "")
            .replace("Fujifilm ", "")
    }.orEmpty()
    val pushLabel = pushChoice?.takeIf { it.visible.test(lookup) }?.let { ch ->
        val raw = lookup.get(ch.key)
        val eff = if (ch.offered(raw, lookup)) raw else 0
        if (eff == 0) null else ch.label(eff, lookup)
    }
    val topText = listOfNotNull(
        finishLabel.takeIf { it.isNotEmpty() },
        pushLabel,
    ).joinToString(" · ")

    val extraParts = ArrayList<String>()
    for (cIdx in 3 until choices.size) {
        val ch = choices[cIdx]
        if (!ch.visible.test(lookup)) continue
        val raw = lookup.get(ch.key)
        val eff = if (ch.offered(raw, lookup)) raw else 0
        if (eff != 0) {
            extraParts.add("${ch.title} ${ch.label(eff, lookup)}")
        }
    }
    if (ev != 0f) {
        extraParts.add(String.format(Locale.ROOT, "曝光 %+.2f EV", ev))
    }
    for (p in activeParams) {
        val v = lookup.get(p.key).coerceIn(p.min, p.max)
        if (v != p.def) {
            val formatted = if (p.scale == 1 || p.unit == "%") {
                "$v${p.unit}"
            } else {
                String.format(Locale.ROOT, "%.2f", v / p.scale.toFloat()) + p.unit
            }
            extraParts.add("${p.title.replace("多留", "")} $formatted")
        }
    }

    return FilmSwatchCardData(
        topText = topText.ifEmpty { "默认预设" },
        bottomText = if (extraParts.isNotEmpty()) extraParts.joinToString(" · ") else "左上原色 · 右下过片",
        refColors = refColors,
        filmColors = filmColors,
        cols = FilmChartNative.COLS,
        rows = FilmChartNative.ROWS,
    )
}
