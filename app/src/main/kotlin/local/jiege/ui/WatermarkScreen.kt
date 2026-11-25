package local.jiege.ui

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogActionStyle
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.dialog.Dialog
import com.itos.coui.dialog.InputDialog
import com.itos.coui.icons.CouiIcons
import com.itos.coui.theme.CouiTheme
import local.jiege.settings.LaunchModes
import local.jiege.settings.ModelPresets
import local.jiege.settings.ModuleSettings

/** 独立保存水印: modes that keep their own watermark settings. */
@Composable
internal fun WatermarkModesScreen(
    contentPadding: PaddingValues,
    nestedScroll: NestedScrollConnection,
    overscrollEffect: OverscrollEffect,
    store: SettingsStore,
    listState: LazyListState = rememberLazyListState(),
) {
    var modes by remember { mutableStateOf(LaunchModes.watermarkModes(store.getString(ModuleSettings.WATERMARK_MODES))) }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScroll),
        contentPadding = contentPadding,
        overscrollEffect = overscrollEffect,
    ) {
        item(key = "modes") {
            UiGroup(
                title = "按模式保存",
                footer = "开启的模式独立保存水印开关与样式；理光 GR 默认独立。",
            ) {
                LaunchModes.WATERMARK_NAMES.forEachIndexed { idx, name ->
                    CouiSwitchRow(
                        title = LaunchModes.label(name),
                        checked = name in modes,
                        onCheckedChange = { on ->
                            val next = LinkedHashSet<String>()
                            LaunchModes.WATERMARK_NAMES.forEach {
                                if (if (it == name) on else it in modes) next.add(it)
                            }
                            modes = next
                            store.setString(ModuleSettings.WATERMARK_MODES, next.joinToString(","))
                        },
                    )
                    if (idx < LaunchModes.WATERMARK_NAMES.lastIndex) UiDivider()
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Top-bar action signal holder for ModelPresetScreen. */
internal class PresetScreenActions {
    var addTrigger by mutableIntStateOf(0)
}

/** Model presets sub-page: pick a preset, add one via top-bar "+" InputDialog, or delete custom ones. */
@Composable
internal fun ModelPresetScreen(
    contentPadding: PaddingValues,
    nestedScroll: NestedScrollConnection,
    overscrollEffect: OverscrollEffect,
    store: SettingsStore,
    actions: PresetScreenActions,
    listState: LazyListState = rememberLazyListState(),
) {
    val dark = CouiTheme.isDark
    val scheme = CouiTheme.colorScheme
    val (material, backdrop) = rememberOmniDialogMaterial(dark)
    var custom by remember { mutableStateOf(store.getString(ModuleSettings.MODEL_CUSTOM_PRESETS)) }
    var selected by remember { mutableStateOf(store.getString(ModuleSettings.MODEL_PRESET_ID)) }
    var addVisible by remember { mutableStateOf(false) }
    var addEverOpened by remember { mutableStateOf(false) }
    if (addVisible) addEverOpened = true
    var addSession by remember { mutableIntStateOf(0) }
    var nameValue by remember { mutableStateOf(TextFieldValue("")) }
    var pendingDelete by remember { mutableStateOf<ModelPresets.Preset?>(null) }
    var deleteVisible by remember { mutableStateOf(false) }
    val presets = ModelPresets.all(custom)
    val effective = presets.firstOrNull { it.id == selected }?.id ?: presets.first().id

    fun openAdd() {
        nameValue = TextFieldValue("")
        addSession++
        addVisible = true
    }

    LaunchedEffect(actions.addTrigger) {
        if (actions.addTrigger > 0) {
            actions.addTrigger = 0
            openAdd()
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScroll),
        contentPadding = contentPadding,
        overscrollEffect = overscrollEffect,
    ) {
        item(key = "presets") {
            UiGroup(title = "机型预设") {
                presets.forEachIndexed { idx, preset ->
                    CouiRadioRow(
                        title = preset.name,
                        summary = if (preset.builtin) "内置" else "自定义",
                        selected = preset.id == effective,
                        onClick = {
                            selected = preset.id
                            store.setString(ModuleSettings.MODEL_PRESET_ID, preset.id)
                        },
                        trailingExtra = if (!preset.builtin) {
                            {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .clickable(role = Role.Button) {
                                            pendingDelete = preset
                                            deleteVisible = true
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    UiIcon(
                                        imageVector = CouiIcons.Filled.Close,
                                        tint = scheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                        contentDescription = "删除",
                                    )
                                }
                            }
                        } else null,
                    )
                    if (idx < presets.lastIndex) UiDivider()
                }
            }
        }
        item(key = "add_row") {
            UiGroup {
                CouiNavRow(
                    title = "新增自定义预设",
                    onClick = ::openAdd,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (addEverOpened) {
        key(addSession) {
            val trimmed = nameValue.text.trim()
            val tooLong = nameValue.text.length > 60
            InputDialog(
                value = nameValue,
                onValueChange = { updated ->
                    nameValue = if (updated.text.length <= 60) updated else updated.copy(text = updated.text.take(60))
                },
                onDismissRequest = { addVisible = false },
                title = "新增预设",
                message = "用于水印显示与 EXIF 机型写入",
                placeholder = "请输入机型名称",
                clearContentDescription = "清空",
                counter = "${nameValue.text.length}/60",
                counterIsError = tooLong,
                dark = dark,
                accent = scheme.accent,
                material = material,
                backdrop = backdrop,
                visible = addVisible,
                confirmAction = CouiDialogAction(
                    text = "保存预设",
                    enabled = trimmed.isNotEmpty(),
                    style = CouiDialogActionStyle.Primary,
                    colors = CouiDialogDefaults.primaryActionColors(scheme.accent),
                    onClick = {
                        if (trimmed.isNotEmpty()) {
                            custom = ModelPresets.add(custom, trimmed)
                            store.setString(ModuleSettings.MODEL_CUSTOM_PRESETS, custom)
                            addVisible = false
                        }
                    },
                ),
                dismissAction = CouiDialogAction(
                    text = "取消",
                    onClick = { addVisible = false },
                ),
            )
        }
    }

    pendingDelete?.let { preset ->
        Dialog(
            onDismissRequest = { deleteVisible = false },
            title = "删除自定义预设",
            message = "确定删除“${preset.name}”吗？",
            dark = dark,
            material = material,
            backdrop = backdrop,
            visible = deleteVisible,
            dismissAction = CouiDialogAction(
                text = "取消",
                onClick = { deleteVisible = false },
            ),
            confirmAction = CouiDialogAction(
                text = "删除",
                style = CouiDialogActionStyle.Primary,
                colors = CouiDialogDefaults.primaryActionColors(CouiDialogDefaults.colors(dark).destructive),
                onClick = {
                    custom = ModelPresets.remove(custom, preset.id)
                    store.setString(ModuleSettings.MODEL_CUSTOM_PRESETS, custom)
                    if (selected == preset.id) {
                        selected = ModelPresets.DEFAULT_ID
                        store.setString(ModuleSettings.MODEL_PRESET_ID, selected)
                    }
                    deleteVisible = false
                },
            ),
        )
    }
}
