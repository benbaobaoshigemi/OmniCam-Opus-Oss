package local.jiege.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogActionStyle
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.dialog.Dialog
import com.itos.coui.dialog.InputDialog
import com.itos.coui.menu.CouiMenuDefaults
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import kotlinx.coroutines.withTimeoutOrNull
import com.itos.coui.backdrop.CouiBackdrop
import com.itos.coui.effect.CouiFloatingRenderer
import com.itos.coui.theme.CouiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal const val RECIPE_TAB_ADJUST = 0
internal const val RECIPE_TAB_LIST = 1

/** How long an accessory stays melted into the capsule: the collapse spring plus a beat. */
private const val ABSORB_MILLIS = 750L

private val RECIPE_TABS = listOf(
    OmniTabItem("调整", EditIcon, EditIcon),
    OmniTabItem("配方", RecipeIcon, RecipeIcon),
)

/** What a recipe page's floating bar changes in the recipe list; the recipe tab reads [revision]. */
internal class RecipeActions {
    var revision by mutableIntStateOf(0)
}

/**
 * The floating bar of an engine page that has recipes, laid out as the system's own (Phone, Gallery): 调整 | 配方 in a centred capsule, the
 * buttons at the screen margins. 保存 is the glass ball on the left; the right one is the accent ball and follows the
 * tab: 分享 on 调整 sends the current look out as it is, 导入 on 配方 takes a recipe in from the clipboard.
 * A ball that has put a recipe into the list melts into the capsule (the recipes tab) and comes back; nothing is announced.
 */
@Composable
internal fun RecipeFloatingBar(
    tab: Int,
    onTabChange: (Int) -> Unit,
    backdrop: CouiBackdrop,
    store: SettingsStore,
    recipes: LookRecipes,
    actions: RecipeActions,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf<RecipeNaming?>(null) }
    // 保存球只在当前参数还不在配方里时露出; 存进去或调回已存的参数, 它就融进胶囊。
    var settingsRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(store) {
        val stop = store.observe { settingsRevision++ }
        onDispose { stop() }
    }
    val leftShown = remember(settingsRevision, actions.revision) {
        val current = recipes.capture(store)
        recipes.list(context).none { it.values == current }
    }
    var rightShown by remember { mutableStateOf(true) }
    val renderer = remember {
        if (Build.VERSION.SDK_INT >= 34) runCatching { CouiFloatingRenderer() }.getOrNull() else null
    }
    val adjust = tab == RECIPE_TAB_ADJUST
    if (renderer == null) {
        // Below Android 14 there is no glass to draw: the tabs alone.
        OmniFloatingBar(items = RECIPE_TABS, selectedIndex = tab, onSelect = onTabChange, backdrop = backdrop)
        return
    }
    Box(Modifier.fillMaxWidth()) {
        OmniNavigationBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            backdrop = backdrop,
            renderer = renderer,
            items = RECIPE_TABS,
            selectedIndex = tab,
            leftIcon = SaveIcon,
            leftDescription = "保存当前配方",
            rightIcon = if (adjust) ShareIcon else ImportIcon,
            rightDescription = if (adjust) "分享当前配方" else "从剪贴板导入配方",
            leftVisible = leftShown,
            rightVisible = rightShown,
            onLeftClick = { naming = RecipeNaming(recipes.defaultName(store), recipes.capture(store)) },
            onRightClick = {
                if (adjust) {
                    shareCurrent(context, store, recipes)
                } else {
                    val recipe = recipes.decode(clipboardText(context))
                    if (recipe != null && recipes.add(context, recipe.name, recipe.values)) {
                        actions.revision++
                        scope.launch {
                            rightShown = false
                            delay(ABSORB_MILLIS)
                            rightShown = true
                        }
                    }
                }
            },
            onSelection = onTabChange,
        )
    }
    RecipeNameDialog(
        title = "保存配方",
        initial = naming?.name,
        onConfirm = { name ->
            naming?.let { if (recipes.add(context, name, it.values)) actions.revision++ }
        },
        onClose = { naming = null },
    )
}

/** The look being saved and the name it is offered under, both taken when 保存 is tapped. */
private class RecipeNaming(val name: String, val values: Map<String, String>)

@Composable
internal fun RecipeListScreen(
    contentPadding: PaddingValues,
    nestedScroll: NestedScrollConnection,
    overscrollEffect: OverscrollEffect,
    listState: LazyListState,
    store: SettingsStore,
    recipes: LookRecipes,
    actions: RecipeActions,
) {
    val context = LocalContext.current
    var applied by remember { mutableIntStateOf(0) }
    val saved = remember(actions.revision) { recipes.list(context) }
    val current = remember(applied, actions.revision) { recipes.capture(store) }
    var renaming by remember { mutableStateOf<LookRecipe?>(null) }
    var deleting by remember { mutableStateOf<LookRecipe?>(null) }
    var deleteVisible by remember { mutableStateOf(false) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().nestedScroll(nestedScroll),
        contentPadding = contentPadding,
        overscrollEffect = overscrollEffect,
    ) {
        if (saved.isEmpty()) {
            item(key = "empty") {
                UiGroup(footer = "在「调整」里调好${recipes.title}，点左边的球存入这里；收到别人分享的配方，复制后点右边的球导入。") {
                    UiInfoRow(title = "还没有配方")
                }
            }
        } else {
            item(key = "recipes") {
                UiGroup(footer = "点一条即可套用，长按可重命名或删除；只改${recipes.title}引擎，不影响其他引擎。") {
                    saved.forEachIndexed { pos, recipe ->
                        RecipeRow(
                            recipe = recipe,
                            summary = recipes.summary(recipe),
                            selected = recipe.values == current,
                            onApply = {
                                recipes.apply(store, recipe)
                                applied++
                            },
                            onRename = { renaming = recipe },
                            onDelete = {
                                deleting = recipe
                                deleteVisible = true
                            },
                        )
                        if (pos < saved.lastIndex) UiDivider()
                    }
                }
            }
        }
        item(key = "bottom_space") { Spacer(Modifier.height(16.dp)) }
    }
    RecipeNameDialog(
        title = "重命名配方",
        initial = renaming?.name,
        onConfirm = { name ->
            renaming?.let {
                if (name != it.name) {
                    recipes.rename(context, it.id, name)
                    actions.revision++
                }
            }
        },
        onClose = { renaming = null },
    )
    deleting?.let { recipe ->
        val dark = CouiTheme.isDark
        val (material, backdrop) = rememberOmniDialogMaterial(dark)
        Dialog(
            onDismissRequest = { deleteVisible = false },
            title = "删除配方",
            message = "删除“${recipe.name}”？",
            dark = dark,
            material = material,
            backdrop = backdrop,
            visible = deleteVisible,
            dismissAction = CouiDialogAction(text = "取消", onClick = { deleteVisible = false }),
            confirmAction = CouiDialogAction(
                text = "删除",
                style = CouiDialogActionStyle.Primary,
                colors = CouiDialogDefaults.primaryActionColors(CouiDialogDefaults.colors(dark).destructive),
                onClick = {
                    deleteVisible = false
                    recipes.remove(context, recipe.id)
                    actions.revision++
                },
            ),
        )
    }
}

private enum class RecipeAction { RENAME, DELETE }

/** A saved recipe; a long press opens 重命名 / 删除 where the finger is. */
@Composable
private fun RecipeRow(
    recipe: LookRecipe,
    summary: String,
    selected: Boolean,
    onApply: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val anchor = rememberCouiMenuAnchor()
    var menuOpen by remember { mutableStateOf(false) }
    Box(Modifier.couiMenuAnchor(anchor)) {
        CouiRadioRow(
            title = recipe.name,
            summary = summary,
            selected = selected,
            onClick = onApply,
            modifier = Modifier.longPressKeepingTap { anchor.open { menuOpen = true } },
        )
        Menu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            anchor = anchor,
            style = CouiMenuDefaults.style(CouiTheme.isDark),
            items = listOf(
                CouiMenuItem(RecipeAction.RENAME, "重命名"),
                CouiMenuItem(RecipeAction.DELETE, "删除"),
            ),
            onItemClick = { item ->
                menuOpen = false
                when (item.value) {
                    RecipeAction.RENAME -> onRename()
                    RecipeAction.DELETE -> onDelete()
                }
            },
        )
    }
}

/**
 * The COUI name input for a recipe. It opens when [initial] becomes non-null, with [initial] filled in and selected,
 * so typing replaces it and 保存 keeps it; [onClose] runs once it is dismissed either way.
 */
@Composable
private fun RecipeNameDialog(
    title: String,
    initial: String?,
    onConfirm: (String) -> Unit,
    onClose: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf(TextFieldValue("")) }
    LaunchedEffect(initial) {
        if (initial != null) {
            value = TextFieldValue(initial, TextRange(0, initial.length))
            open = true
            visible = true
        }
    }
    if (!open) return
    val dark = CouiTheme.isDark
    val scheme = CouiTheme.colorScheme
    val (material, backdrop) = rememberOmniDialogMaterial(dark)
    val maxLength = 32
    val name = value.text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
    val tooLong = value.text.length > maxLength
    fun close() {
        visible = false
        onClose()
    }
    InputDialog(
        value = value,
        onValueChange = { value = it },
        onDismissRequest = { close() },
        title = title,
        placeholder = "输入配方名称",
        clearContentDescription = "清空",
        counter = "${value.text.length}/$maxLength",
        counterIsError = tooLong,
        dark = dark,
        accent = scheme.accent,
        material = material,
        backdrop = backdrop,
        visible = visible,
        confirmAction = CouiDialogAction(
            text = "保存",
            enabled = name.isNotEmpty() && !tooLong,
            style = CouiDialogActionStyle.Primary,
            colors = CouiDialogDefaults.primaryActionColors(scheme.accent),
            onClick = {
                onConfirm(name)
                close()
            },
        ),
        dismissAction = CouiDialogAction(text = "取消", onClick = { close() }),
    )
}

/**
 * A long press that leaves the tap to the row: the row keeps its own press feedback and click, and once the press
 * has lasted the system long-press time [onLongPress] runs and the rest of the gesture is consumed, so no click follows.
 */
private fun Modifier.longPressKeepingTap(onLongPress: () -> Unit): Modifier = pointerInput(onLongPress) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var done = false
            while (!done) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                done = change == null || !change.pressed ||
                    (change.position - down.position).getDistance() > viewConfiguration.touchSlop
            }
            true
        }
        if (ended != null) return@awaitEachGesture
        onLongPress()
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

private fun shareCurrent(context: Context, store: SettingsStore, recipes: LookRecipes) {
    val text = recipes.shareText(recipes.defaultName(store), recipes.capture(store))
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    val chooser = Intent.createChooser(send, "分享${recipes.title}配方")
    if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

private fun clipboardText(context: Context): String {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = manager.primaryClip ?: return ""
    return (0 until clip.itemCount).joinToString("\n") { clip.getItemAt(it).coerceToText(context) }
}
