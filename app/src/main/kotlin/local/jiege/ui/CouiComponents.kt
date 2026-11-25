package local.jiege.ui

import android.graphics.BlendMode as AndroidBlendMode
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import com.itos.coui.backdrop.CouiBackdrop
import com.itos.coui.backdrop.couiBackdrop
import com.itos.coui.backdrop.rememberCouiBackdrop
import com.itos.coui.chip.ChipTabRow
import com.itos.coui.chip.CouiChipTabItem
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogActionStyle
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.dialog.CouiDialogMaterial
import com.itos.coui.dialog.Dialog
import com.itos.coui.effect.CouiFloatingMaterial
import com.itos.coui.effect.CouiFloatingRenderer
import com.itos.coui.effect.CouiFloatingShadow
import com.itos.coui.effect.CouiFloatingSurface
import com.itos.coui.effect.LocalCouiContourGlowEnabled
import com.itos.coui.effect.SpotlightPreset
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.effect.couiFloatingPair
import com.itos.coui.effect.couiFloatingSurface
import com.itos.coui.effect.couiNavigationFeedback
import com.itos.coui.effect.rememberCouiSpotlight
import com.itos.coui.icons.CouiIcons
import androidx.compose.ui.input.pointer.PointerEvent
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Footer
import com.itos.coui.layout.Item
import com.itos.coui.menu.CouiMenuDefaults
import com.itos.coui.menu.rememberCouiWindowBackdrop
import com.itos.coui.motion.CouiPressMotion
import com.itos.coui.motion.CouiTouchMotion
import com.itos.coui.motion.couiPressMotion
import com.itos.coui.motion.couiTouchMotion
import com.itos.coui.motion.rememberCouiPressMotion
import com.itos.coui.motion.rememberCouiTouchMotion
import com.itos.coui.radiobutton.RadioButton
import com.itos.coui.scroll.couiVerticalScroll
import com.itos.coui.scroll.rememberCouiOverscrollEffect
import com.itos.coui.segmentbutton.SegmentedControl
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.shape.CouiShape
import com.itos.coui.slider.Slider
import com.itos.coui.switch.Switch
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text
import com.itos.coui.theme.CouiTheme
import com.itos.coui.theme.LocalCouiContentColor
import com.itos.coui.theme.LocalCouiTextStyle
import com.itos.coui.topappbar.CouiTopAppBarButtonDefaults
import com.itos.coui.topappbar.TopAppBarButton
import com.itos.coui.topappbar.TopAppBarSurface
import local.jiege.settings.MasterStyle
import local.jiege.settings.ModuleSettings
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Tab descriptor for [OmniFloatingBar]. */
internal data class OmniTabItem(
    val title: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)

@Composable
internal fun UiAppBarTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    val color = LocalCouiContentColor.current.takeOrElse { CouiTheme.colorScheme.onSurface }
    BasicText(
        text = text,
        modifier = modifier,
        style = LocalCouiTextStyle.current.copy(color = color),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Colour-by-luminance swatch matrix shown inside [CouiInfoDialog]. */
internal data class FilmSwatchCardData(
    val topText: String,
    val bottomText: String,
    val refColors: List<Color>,
    val filmColors: List<Color>,
    val cols: Int = 11,
    val rows: Int = 9,
)

/** Structured explanation shown in [CouiInfoDialog]. */
internal data class InfoGuideEntry(
    val title: String,
    val badge: String? = null,
    val sections: List<Pair<String, String>>,
    val moreSections: List<Pair<String, String>> = emptyList(),
    val swatchCard: FilmSwatchCardData? = null,
)

@Composable
internal fun CouiInfoIconButton(
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = CouiTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        UiIcon(
            imageVector = InfoIcon,
            tint = scheme.onSurfaceVariant.copy(alpha = 0.78f),
            modifier = Modifier.size(17.dp),
            contentDescription = contentDescription,
        )
    }
}

@Composable
internal fun rememberOmniDialogMaterial(
    dark: Boolean = CouiTheme.isDark,
): Pair<CouiDialogMaterial?, CouiBackdrop?> {
    val backdrop = rememberCouiWindowBackdrop()
    val material = if (CouiDialogDefaults.isMaterialSupported() && backdrop != null) {
        CouiDialogDefaults.material(dark)
    } else {
        null
    }
    return material to backdrop
}

@Composable
private fun FilmSwatchCard(card: FilmSwatchCardData) {
    val scheme = CouiTheme.colorScheme
    val dark = CouiTheme.isDark
    val shape = CouiShape(21.dp)
    val cardBg = if (dark) Color(0xFF28282C) else Color(0xFFF3F4F6)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cardBg, shape),
    ) {
        UiText(
            text = card.topText,
            kind = CouiTextKind.ItemSummary,
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(card.cols.toFloat() / card.rows.toFloat())
                .clipToBounds(),
        ) {
            val triPath = Path()
            val total = minOf(card.refColors.size, card.filmColors.size, card.cols * card.rows)
            for (i in 0 until total) {
                val r = i / card.cols
                val c = i % card.cols
                val x0 = c * size.width / card.cols
                val y0 = r * size.height / card.rows
                val x1 = (c + 1) * size.width / card.cols
                val y1 = (r + 1) * size.height / card.rows
                drawRect(
                    color = card.refColors[i],
                    topLeft = Offset(x0, y0),
                    size = Size((x1 - x0) + 0.5f, (y1 - y0) + 0.5f),
                )
                triPath.reset()
                triPath.moveTo(x1, y0)
                triPath.lineTo(x1, y1)
                triPath.lineTo(x0, y1)
                triPath.close()
                drawPath(path = triPath, color = card.filmColors[i])
            }
        }
        UiText(
            text = card.bottomText,
            kind = CouiTextKind.GroupFooter,
            color = scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
internal fun CouiInfoDialog(
    entry: InfoGuideEntry?,
    onDismiss: () -> Unit,
) {
    var retained by remember { mutableStateOf(entry) }
    var expanded by remember { mutableStateOf(false) }
    var showSwatch by remember { mutableStateOf(false) }
    var wasVisible by remember { mutableStateOf(false) }
    if (entry != null) {
        if (!wasVisible || entry != retained) {
            expanded = false
            showSwatch = false
        }
        retained = entry
        wasVisible = true
    } else {
        wasVisible = false
    }
    val shown = retained ?: return
    val scheme = CouiTheme.colorScheme
    val dark = CouiTheme.isDark
    val dialogColors = CouiDialogDefaults.themeColors(dark)
    val hasMore = shown.moreSections.isNotEmpty()
    val hasSwatch = shown.swatchCard != null
    val activeSections = if (hasMore && expanded) shown.moreSections else shown.sections
    val scrollState = androidx.compose.runtime.key(shown, expanded, showSwatch) { rememberScrollState() }
    Dialog(
        onDismissRequest = onDismiss,
        title = null,
        dark = dark,
        colors = dialogColors,
        visible = entry != null,
        dismissAction = when {
            hasSwatch -> CouiDialogAction(
                text = if (showSwatch) "简介" else "色卡",
                onClick = { showSwatch = !showSwatch },
            )
            hasMore && !expanded -> CouiDialogAction(
                text = "更多信息",
                onClick = { expanded = true },
            )
            else -> null
        },
        confirmAction = CouiDialogAction(
            text = "知道了",
            style = CouiDialogActionStyle.Primary,
            colors = CouiDialogDefaults.primaryActionColors(scheme.accent),
            onClick = onDismiss,
        ),
    ) {
        val compact = LocalConfiguration.current.fontScale > 1.15f
        val density = LocalDensity.current
        val defaultHeaderPx = with(density) { (CouiDialogDefaults.titleTop + 26.dp + CouiDialogDefaults.titleBottom).toPx().roundToInt() }
        var headerHeightPx by remember(shown.title) { mutableIntStateOf(defaultHeaderPx) }
        val headerHeightDp = with(density) { headerHeightPx.toDp() }
        val scrollBackdrop = rememberCouiBackdrop()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 440.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .couiBackdrop(scrollBackdrop)
                    .background(dialogColors.surface),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(
                            start = CouiDialogDefaults.contentPadding,
                            top = headerHeightDp,
                            end = CouiDialogDefaults.contentPadding,
                            bottom = if (compact) 10.dp else CouiDialogDefaults.messageBottom,
                        ),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
                ) {
                    if (!showSwatch && !shown.badge.isNullOrBlank()) {
                        UiCaptionText(
                            text = shown.badge,
                            color = scheme.onSurfaceVariant,
                            fontSizeSp = 12f,
                            fontWeight = FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (showSwatch && shown.swatchCard != null) {
                        FilmSwatchCard(shown.swatchCard)
                    } else {
                        activeSections.forEach { (heading, body) ->
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp),
                            ) {
                                if (heading.isNotBlank()) {
                                    UiCaptionText(
                                        text = heading,
                                        color = scheme.onSurface,
                                        fontSizeSp = 13f,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.Start,
                                        maxLines = 2,
                                    )
                                }
                                UiText(
                                    text = body,
                                    kind = CouiTextKind.ItemSummary,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            TopAppBarSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .onSizeChanged { headerHeightPx = it.height },
                backdrop = scrollBackdrop,
                gradientColor = dialogColors.surface,
                dark = dark,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = CouiDialogDefaults.contentPadding,
                            top = CouiDialogDefaults.titleTop,
                            end = CouiDialogDefaults.contentPadding,
                            bottom = CouiDialogDefaults.titleBottom,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    UiText(
                        text = shown.title,
                        kind = CouiTextKind.TopBar,
                        color = dialogColors.title,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
internal fun UiInfoActionPill(
    text: String,
    onClick: () -> Unit,
) {
    val scheme = CouiTheme.colorScheme
    val dark = CouiTheme.isDark
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .clip(CouiCapsuleShape)
            .background(scheme.accent.copy(alpha = if (dark) 0.22f else 0.12f))
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        UiCaptionText(
            text = text,
            color = scheme.accentOnSurface,
            fontSizeSp = 11f,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
internal fun UiText(
    text: String,
    kind: CouiTextKind,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val resolved = color.takeOrElse {
        LocalCouiContentColor.current.takeOrElse { CouiTheme.colorScheme.onSurface }
    }
    Text(
        modifier = modifier,
        text = text,
        kind = kind,
        color = resolved,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Composable
internal fun UiCaptionText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = CouiTheme.colorScheme.onSurfaceVariant,
    fontSizeSp: Float = 10f,
    fontWeight: FontWeight = FontWeight.Medium,
    textAlign: TextAlign = TextAlign.Center,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = LocalCouiTextStyle.current.copy(
            color = color,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp + 2f).sp,
            fontWeight = fontWeight,
            textAlign = textAlign,
        ),
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Composable
internal fun UiIcon(
    imageVector: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    contentDescription: String? = null,
) {
    val resolved = tint.takeOrElse {
        LocalCouiContentColor.current.takeOrElse { CouiTheme.colorScheme.onSurface }
    }
    Image(
        painter = rememberVectorPainter(imageVector),
        contentDescription = contentDescription,
        modifier = modifier,
        colorFilter = if (resolved == Color.Unspecified) null else ColorFilter.tint(resolved),
    )
}

internal val UiCardShape = CouiShape(24.dp, 2.5f)

@Composable
internal fun UiGroup(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    headerActions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CouiPageDefaults.themeColors()
    val topSpacing = if (title.isNullOrEmpty()) CouiPageDefaults.groupSpacing else CouiPageDefaults.categoryTopSpacing
    Column(modifier.fillMaxWidth().padding(top = topSpacing)) {
        if (!title.isNullOrEmpty()) {
            Row(
                Modifier.padding(horizontal = 32.dp, vertical = 8.dp).heightIn(min = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    kind = CouiTextKind.GroupTitle,
                    color = colors.summary,
                    modifier = Modifier.weight(1f).semantics { heading() },
                    textAlign = TextAlign.Start,
                )
                headerActions()
            }
        }
        Column(
            Modifier.padding(horizontal = CouiPageDefaults.horizontalMargin).fillMaxWidth()
                .clip(UiCardShape).background(colors.card),
            content = content,
        )
        if (!footer.isNullOrEmpty()) Footer(text = footer, color = colors.summary)
    }
}

@Composable
internal fun UiDivider(modifier: Modifier = Modifier) {
    Divider(modifier = modifier)
}

/** Row metrics of the system settings lists: a single-line row is 52dp tall. */
internal val UiRowPadding = PaddingValues(horizontal = CouiPageDefaults.rowPadding, vertical = 12.dp)
internal val UiRowMinHeight = 52.dp

@Composable
internal fun UiInfoRow(
    title: String,
    summary: String? = null,
    value: String? = null,
    valueColor: Color = CouiTheme.colorScheme.onSurfaceVariant,
    summaryColor: Color = CouiTheme.colorScheme.onSurfaceVariant,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    Item(
        icon = icon?.let { vec -> { UiIcon(vec, modifier = Modifier.size(22.dp)) } },
        title = { UiText(title, CouiTextKind.ItemTitle) },
        summary = summary?.let { s ->
            { UiText(s, CouiTextKind.ItemSummary, color = summaryColor) }
        },
        trailing = {
            if (value != null) {
                Spacer(Modifier.width(8.dp))
                UiText(value, CouiTextKind.ItemValue, color = valueColor)
            }
        },
        onClick = onClick,
        modifier = Modifier.heightIn(min = UiRowMinHeight),
        contentPadding = UiRowPadding,
    )
}

@Composable
internal fun CouiSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val scheme = CouiTheme.colorScheme
    val titleColor = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.35f)
    val summaryColor = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.45f)
    Item(
        enabled = enabled && !loading,
        onClick = if (enabled && !loading) { { onCheckedChange(!checked) } } else null,
        title = { UiText(title, CouiTextKind.ItemTitle, color = titleColor) },
        summary = summary?.let { s ->
            { UiText(s, CouiTextKind.ItemSummary, color = summaryColor) }
        },
        trailing = {
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = if (enabled && !loading) onCheckedChange else null,
                enabled = enabled,
                loading = loading,
                loadingDescription = if (loading) "加载中" else null,
            )
        },
    )
}

@Composable
internal fun CouiSettingSwitch(
    store: SettingsStore,
    key: String,
    title: String,
    summary: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit = {},
) {
    var checked by remember(key) { mutableStateOf(store.get(key)) }
    CouiSwitchRow(
        title = title,
        summary = summary,
        checked = checked,
        enabled = enabled,
        onCheckedChange = { next ->
            checked = next
            store.set(key, next)
            onChange(next)
        },
    )
}

@Composable
internal fun CouiRadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
    trailingExtra: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val scheme = CouiTheme.colorScheme
    val titleColor = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.35f)
    val summaryColor = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.45f)
    Item(
        modifier = modifier,
        enabled = enabled,
        onClick = if (enabled) onClick else null,
        title = { UiText(title, CouiTextKind.ItemTitle, color = titleColor) },
        summary = summary?.let { s ->
            { UiText(s, CouiTextKind.ItemSummary, color = summaryColor) }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                trailingExtra?.invoke(this)
                RadioButton(selected = selected, enabled = enabled)
            }
        },
    )
}

@Composable
internal fun CouiCategoryEntryRow(
    title: String,
    summary: String,
    selected: Boolean,
    onSelectRadio: () -> Unit,
    onOpen: () -> Unit,
) {
    val scheme = CouiTheme.colorScheme
    val radioSource = remember { MutableInteractionSource() }
    Item(
        onClick = onOpen,
        iconSize = 36.dp,
        icon = {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = radioSource,
                        indication = null,
                        role = Role.RadioButton,
                        onClick = onSelectRadio,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                RadioButton(selected = selected, enabled = true)
            }
        },
        title = { UiText(title, CouiTextKind.ItemTitle) },
        summary = {
            UiText(
                text = summary,
                kind = CouiTextKind.ItemSummary,
                color = if (selected) scheme.accentOnSurface else scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailing = {
            val dark = CouiTheme.isDark
            Spacer(Modifier.width(8.dp))
            UiIcon(
                imageVector = CouiIcons.Filled.Next,
                tint = Color(if (dark) 0x4DFFFFFF else 0x42000000),
                modifier = Modifier.size(width = 12.dp, height = 24.dp),
            )
        },
    )
}

@Composable
internal fun CouiNavRow(
    title: String,
    summary: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val scheme = CouiTheme.colorScheme
    val dark = CouiTheme.isDark
    val titleColor = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.35f)
    val summaryColor = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.45f)
    Item(
        enabled = enabled,
        onClick = if (enabled) onClick else null,
        title = { UiText(title, CouiTextKind.ItemTitle, color = titleColor) },
        summary = summary?.let { s ->
            { UiText(s, CouiTextKind.ItemSummary, color = summaryColor) }
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (value != null) {
                    UiText(
                        text = value,
                        kind = CouiTextKind.ItemValue,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 188.dp),
                    )
                }
                UiIcon(
                    imageVector = CouiIcons.Filled.Next,
                    tint = Color(
                        if (dark) {
                            if (enabled) 0x4DFFFFFF else 0x26FFFFFF
                        } else {
                            if (enabled) 0x42000000 else 0x21000000
                        },
                    ),
                    modifier = Modifier.size(width = 12.dp, height = 24.dp),
                )
            }
        },
        modifier = Modifier.heightIn(min = UiRowMinHeight),
        contentPadding = UiRowPadding,
    )
}

private val MenuItemHeight = 40.dp
private val MenuOuterShape = CouiShape(28.dp, 2.5f)
private val MenuHighlightShape = CouiShape(20.dp, 2.5f)
private val MenuMinWidth = 168.dp
private val MenuMaxWidth = 280.dp

internal data class CouiChoiceMenuRequest(
    val id: Any,
    val items: List<String>,
    val selectedIndex: Int,
    val anchorBounds: Rect,
    val anchorTapInRoot: Offset? = null,
    val onSelect: (Int) -> Unit,
)

@Stable
internal class CouiChoiceMenuState {
    var request by mutableStateOf<CouiChoiceMenuRequest?>(null)
        private set

    fun open(
        id: Any,
        items: List<String>,
        selectedIndex: Int,
        anchorBounds: Rect,
        anchorTapInRoot: Offset? = null,
        onSelect: (Int) -> Unit,
    ) {
        if (items.isEmpty()) return
        request = CouiChoiceMenuRequest(
            id = id,
            items = items,
            selectedIndex = selectedIndex,
            anchorBounds = anchorBounds,
            anchorTapInRoot = anchorTapInRoot,
            onSelect = onSelect,
        )
    }

    fun dismiss() {
        request = null
    }
}

private val LocalCouiChoiceMenuHost = compositionLocalOf<CouiChoiceMenuState?> { null }

@Composable
internal fun ProvideCouiChoiceMenuHost(content: @Composable () -> Unit) {
    val state = remember { CouiChoiceMenuState() }
    CompositionLocalProvider(LocalCouiChoiceMenuHost provides state, content = content)
}

@Composable
internal fun CouiChoiceMenuOverlay(backdrop: CouiBackdrop?) {
    val state = LocalCouiChoiceMenuHost.current ?: return
    val currentRequest = state.request
    var retained by remember { mutableStateOf<CouiChoiceMenuRequest?>(null) }
    if (currentRequest != null) {
        retained = currentRequest
    }
    val visible = currentRequest != null
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.82f,
        animationSpec = if (visible) {
            spring(dampingRatio = 0.8f, stiffness = 360f)
        } else {
            spring(dampingRatio = 1f, stiffness = 890f)
        },
        label = "choiceMenuScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = if (visible) 120 else 110),
        label = "choiceMenuAlpha",
    )
    LaunchedEffect(visible, alpha) {
        if (!visible && alpha <= 0.01f) {
            retained = null
        }
    }
    val req = retained ?: return

    BackHandler(enabled = visible) { state.dismiss() }

    val dark = CouiTheme.isDark
    val scheme = CouiTheme.colorScheme
    val density = LocalDensity.current
    val view = LocalView.current
    val haptic = LocalHapticFeedback.current
    val viewConfig = LocalViewConfiguration.current

    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var popupSize by remember { mutableStateOf(IntSize.Zero) }
    var pressedIndex by remember(req.id) { mutableStateOf<Int?>(null) }
    val pillTopAnim = remember(req.id) { Animatable(0.dp, Dp.VectorConverter) }
    var hadActivePress by remember(req.id) { mutableStateOf(false) }
    LaunchedEffect(req.id, pressedIndex) {
        val idx = pressedIndex
        if (idx == null) {
            hadActivePress = false
        } else {
            val targetTop = MenuItemHeight * idx
            if (!hadActivePress) {
                hadActivePress = true
                pillTopAnim.snapTo(targetTop)
            } else {
                pillTopAnim.animateTo(
                    targetValue = targetTop,
                    animationSpec = spring(dampingRatio = 1f, stiffness = 1754.6f),
                )
            }
        }
    }

    val layoutDirection = LocalLayoutDirection.current
    val rtl = layoutDirection == LayoutDirection.Rtl
    val topSafePx = WindowInsets.tappableElement.getTop(density)
    val bottomSafePx = WindowInsets.tappableElement.getBottom(density)
    val hMarginPx = with(density) { 8.dp.roundToPx() }
    val vMarginTopPx = maxOf(with(density) { 48.dp.roundToPx() }, topSafePx + with(density) { 8.dp.roundToPx() })
    val vMarginBottomPx = maxOf(with(density) { 48.dp.roundToPx() }, bottomSafePx + with(density) { 8.dp.roundToPx() })

    val anchor = req.anchorBounds
    val tap = req.anchorTapInRoot
    val maxPopupHeightDp = with(density) {
        val availPx = (rootSize.height - vMarginTopPx - vMarginBottomPx).coerceAtLeast(with(density) { 160.dp.roundToPx() })
        availPx.toDp()
    }

    fun pickPosition(candidates: IntArray, size: Int, window: Int, margin: Int): Int {
        for (c in candidates) {
            if (c >= margin && c + size <= window - margin) return c
        }
        return if (size >= window - 2 * margin) {
            ((window - size) / 2).coerceAtLeast(0)
        } else {
            candidates.last().coerceIn(margin, (window - margin - size).coerceAtLeast(margin))
        }
    }

    val aLeft = tap?.x?.roundToInt() ?: anchor.left.roundToInt()
    val aRight = tap?.x?.roundToInt() ?: anchor.right.roundToInt()
    val aTop = tap?.y?.roundToInt() ?: anchor.top.roundToInt()
    val aBottom = tap?.y?.roundToInt() ?: anchor.bottom.roundToInt()
    val aCenterX = (aLeft + aRight) / 2

    val posX = if (popupSize.width > 0 && rootSize.width > 0) {
        val w = popupSize.width
        val winW = rootSize.width
        val xCandidates = intArrayOf(
            if (rtl) aRight - w else aLeft,
            if (rtl) aLeft else aRight - w,
            if (aCenterX < winW / 2) 0 else winW - w,
        )
        pickPosition(xCandidates, w, winW, hMarginPx)
    } else {
        hMarginPx
    }
    val posY = if (popupSize.height > 0 && rootSize.height > 0) {
        val h = popupSize.height
        val winH = rootSize.height
        if (tap != null) {
            val below = aTop
            val above = below - h
            val maxTop = (winH - vMarginBottomPx - h).coerceAtLeast(vMarginTopPx)
            when {
                below >= vMarginTopPx && below + h <= winH - vMarginBottomPx -> below
                above >= vMarginTopPx && above + h <= winH - vMarginBottomPx -> above
                else -> below.coerceIn(vMarginTopPx, maxTop)
            }
        } else {
            val aCenterY = (aTop + aBottom) / 2
            val yCandidates = intArrayOf(
                aBottom,
                aTop - h,
                aTop - h / 2,
                if (aCenterY < winH / 2) 0 else winH - h,
            )
            // Enforce asymmetric safe insets when falling back without a tap point.
            val maxTop = (winH - vMarginBottomPx - h).coerceAtLeast(vMarginTopPx)
            yCandidates.firstOrNull { it >= vMarginTopPx && it + h <= winH - vMarginBottomPx }
                ?: if (h >= winH - vMarginTopPx - vMarginBottomPx) {
                    vMarginTopPx
                } else {
                    yCandidates.last().coerceIn(vMarginTopPx, maxTop)
                }
        }
    } else {
        vMarginTopPx
    }
    val pivotAnchor = tap ?: anchor.center
    val pivotX = if (popupSize.width > 0) {
        ((pivotAnchor.x - posX) / popupSize.width.toFloat()).coerceIn(0f, 1f)
    } else {
        0.5f
    }
    val pivotY = if (popupSize.height > 0) {
        ((pivotAnchor.y - posY) / popupSize.height.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val renderer = remember {
        if (Build.VERSION.SDK_INT >= 34) runCatching { CouiFloatingRenderer.standalone() }.getOrNull() else null
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { rootSize = it },
    ) {
        if (visible) {
            // Earlier sibling behind popup bounds so touches inside the menu never dismiss it.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(req.id) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            down.consume()
                            state.dismiss()
                        }
                    },
            )
        }
        val scrollState = rememberScrollState()
        LaunchedEffect(req.id) { scrollState.scrollTo(0) }
        val isScrollableMenu by remember {
            derivedStateOf { scrollState.maxValue in 1 until Int.MAX_VALUE }
        }
        val menuOverscroll = rememberCouiOverscrollEffect(hapticFeedbackEnabled = true)
        val itemHeightPx = with(density) { MenuItemHeight.roundToPx() }
        val listContent: @Composable () -> Unit = {
            val pillAlpha by animateFloatAsState(
                targetValue = if (pressedIndex != null) 1f else 0f,
                animationSpec = tween(durationMillis = if (pressedIndex != null) 45 else 150),
                label = "choiceMenuPillAlpha",
            )
            val pillTop = pillTopAnim.value
            val menuTextStyle = CouiMenuDefaults.style(dark).itemTextStyle
            val checkPainter = painterResource(local.jiege.camera.R.drawable.coui_menu_ic_checkbox_selected_new)

            Column(
                modifier = Modifier
                    .couiVerticalScroll(
                        state = scrollState,
                        enabled = isScrollableMenu,
                        overscrollEffect = menuOverscroll,
                    )
                    .padding(vertical = 8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (!isScrollableMenu) {
                                Modifier.pointerInput(req.id, req.items.size, visible) {
                                    if (!visible) return@pointerInput
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val downIdx = (down.position.y.toInt() / itemHeightPx)
                                            .takeIf { it in req.items.indices } ?: return@awaitEachGesture
                                        pressedIndex = downIdx
                                        down.consume()
                                        var latestIdx: Int? = downIdx
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Main)
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (change.changedToUpIgnoreConsumed()) {
                                                pressedIndex = null
                                                if (latestIdx != null) {
                                                    change.consume()
                                                    state.dismiss()
                                                    req.onSelect(latestIdx)
                                                }
                                                break
                                            }
                                            val insideX = change.position.x in 0f..size.width.toFloat()
                                            val insideY = change.position.y in 0f..size.height.toFloat()
                                            val hovered = if (insideX && insideY) {
                                                (change.position.y.toInt() / itemHeightPx).takeIf { it in req.items.indices }
                                            } else {
                                                null
                                            }
                                            if (hovered != latestIdx && hovered != null) {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                            latestIdx = hovered
                                            pressedIndex = hovered
                                            change.consume()
                                        }
                                    }
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    if (pillAlpha > 0.01f) {
                        Box(
                            modifier = Modifier
                                .offset(y = pillTop)
                                .fillMaxWidth()
                                .height(MenuItemHeight)
                                .padding(horizontal = 8.dp, vertical = 1.5.dp)
                                .clip(MenuHighlightShape)
                                .background(
                                    scheme.accentOnSurface.copy(
                                        alpha = (if (dark) 0.24f else 0.16f) * pillAlpha,
                                    ),
                                ),
                        )
                    }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        req.items.forEachIndexed { index, label ->
                            val isSelected = index == req.selectedIndex
                            val rowInteraction = if (isScrollableMenu) {
                                val src = remember(req.id, index) { MutableInteractionSource() }
                                val isRowPressed by src.collectIsPressedAsState()
                                LaunchedEffect(req.id, index, isRowPressed) {
                                    if (isRowPressed) {
                                        pressedIndex = index
                                    } else if (pressedIndex == index) {
                                        pressedIndex = null
                                    }
                                }
                                Modifier.clickable(
                                    interactionSource = src,
                                    indication = null,
                                    enabled = visible,
                                    role = Role.RadioButton,
                                ) {
                                    pressedIndex = index
                                    state.dismiss()
                                    req.onSelect(index)
                                }
                            } else {
                                Modifier
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(MenuItemHeight)
                                    .then(rowInteraction)
                                    .padding(horizontal = 20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                BasicText(
                                    text = label,
                                    style = menuTextStyle.copy(color = scheme.onSurface),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Spacer(Modifier.width(12.dp))
                                Box(
                                    modifier = Modifier.size(24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Image(
                                            painter = checkPainter,
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                            colorFilter = ColorFilter.tint(scheme.onSurface),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val containerBase = Modifier
            .offset { IntOffset(posX, posY) }
            .width(IntrinsicSize.Max)
            .widthIn(min = MenuMinWidth, max = MenuMaxWidth)
            .heightIn(max = maxPopupHeightDp)
            .onSizeChanged { popupSize = it }
            .pointerInput(req.id) {}

        if (Build.VERSION.SDK_INT >= 34 && backdrop != null && renderer != null) {
            val activeBackdrop = backdrop
            val activeRenderer = renderer
            val lightColor = Color.White
            val contour = LocalCouiContourGlowEnabled.current
            val themeTint = CouiTheme.materialTintFor(dark)
            val cornerRadiusPx = with(density) { 28.dp.toPx() }
            val material = remember(dark, themeTint, density) {
                val ext = with(density) { 4.dp.roundToPx() }
                CouiFloatingMaterial(
                    firstColor = if (dark) Color(0x80666666) else Color(0xA3666666),
                    firstBlendMode = if (dark) AndroidBlendMode.OVERLAY else AndroidBlendMode.COLOR_DODGE,
                    secondColor = if (dark) Color(0xC6202024) else Color(0xCCF5F5F7),
                    edgeAlpha = if (dark) 0.72f else 0.25f,
                    innerGlowAlpha = if (dark) 0.03f else 0.1f,
                    index = 1,
                    extension = ext,
                    lightColor = lightColor,
                    themeTint = themeTint,
                )
            }
            val shadow = remember(density, lightColor, dark, contour) {
                fun pixelOffset(dp: Float): Float = (dp * density.density).toInt().toFloat()
                fun pixelSize(dp: Float): Float {
                    val px = dp * density.density
                    return (if (px >= 0f) px + 0.5f else px - 0.5f).toInt().toFloat()
                }
                val projectionColor = when {
                    contour -> Color(0x2D000000)
                    dark -> Color(0x88000000)
                    else -> Color(0x2E000000)
                }
                CouiFloatingShadow(
                    elevation = pixelSize(if (contour) 24f else 320f),
                    color = projectionColor,
                    lightY = pixelSize(if (contour) -320f else -140f),
                    lightZ = if (contour) 0f else pixelSize(10000f),
                    lightRadius = pixelSize(if (contour) 1600f else 2000f),
                    causticColor = lightColor.copy(alpha = 0x3A / 255f),
                    layout = Rect(0f, 0f, 0f, pixelOffset(4.5f)),
                    sdf = pixelOffset(10.0f),
                    smoothCorner = true,
                )
            }
            val contentLayer: GraphicsLayer = rememberGraphicsLayer()
            val motion = rememberCouiTouchMotion(visible)
            val (lightInput, drawLight) = rememberCouiSpotlight(
                interactive = visible,
                preset = SpotlightPreset.OpaqueSmall2,
                dark = dark,
                color = lightColor,
            )
            Box(
                modifier = containerBase
                    .couiFloatingSurface(
                        backdrop = activeBackdrop,
                        renderer = activeRenderer,
                        material = material,
                        shadow = shadow,
                        shape = MenuOuterShape,
                        contentLayer = contentLayer,
                        contourGlowEnabled = contour,
                        foregroundComposition = true,
                        beforeContent = { path ->
                            if (drawLight != null) {
                                clipPath(path) { drawLight(this) }
                            }
                        },
                        surface = {
                            val w = popupSize.width.toFloat().coerceAtLeast(1f)
                            val h = popupSize.height.toFloat().coerceAtLeast(1f)
                            CouiFloatingSurface(
                                bounds = Rect(0f, 0f, w, h).translate(
                                    Offset(motion.translationX.value, motion.translationY.value),
                                ),
                                scaleX = scale * motion.scaleX.value,
                                scaleY = scale * motion.scaleY.value,
                                cornerWeight = 2.5f,
                                alpha = alpha,
                                cornerRadius = cornerRadiusPx,
                            )
                        },
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            translationX = motion.translationX.value
                            translationY = motion.translationY.value
                            scaleX = scale * motion.scaleX.value
                            scaleY = scale * motion.scaleY.value
                            this.alpha = alpha
                            transformOrigin = TransformOrigin(pivotX, pivotY)
                        }
                        .couiTouchMotion(motion, visible)
                        .then(lightInput)
                        .drawWithContent {
                            contentLayer.record { this@drawWithContent.drawContent() }
                        }
                        .clip(MenuOuterShape),
                ) {
                    listContent()
                }
            }
        } else {
            val fallbackBg = if (dark) Color(0xEE222226) else Color(0xF6F6F7FA)
            Box(
                modifier = containerBase
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                        transformOrigin = TransformOrigin(pivotX, pivotY)
                    }
                    .clip(MenuOuterShape)
                    .background(fallbackBg, MenuOuterShape),
            ) {
                listContent()
            }
        }
    }
}

@Composable
internal fun CouiMenuRow(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
) {
    val dark = CouiTheme.isDark
    val pageColors = CouiPageDefaults.themeColors(dark)
    val host = LocalCouiChoiceMenuHost.current
    val menuId = remember { Any() }
    val source = remember { MutableInteractionSource() }
    var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var rowBounds by remember { mutableStateOf(Rect.Zero) }
    var lastTapRoot by remember { mutableStateOf<Offset?>(null) }
    val expanded = host?.request?.id === menuId
    val currentLabel = items.getOrNull(selectedIndex) ?: items.firstOrNull().orEmpty()
    val latestItems by rememberUpdatedState(items)
    val latestSelected by rememberUpdatedState(selectedIndex)
    val latestOnSelect by rememberUpdatedState(onSelect)

    Item(
        modifier = Modifier
            .onGloballyPositioned { coords ->
                rowCoords = coords
                if (coords.isAttached) rowBounds = coords.boundsInRoot()
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val down = ev.changes.firstOrNull { it.pressed && !it.previousPressed }
                        if (down != null) {
                            lastTapRoot = rowCoords?.takeIf { it.isAttached }?.localToRoot(down.position)
                        }
                    }
                }
            }
            .couiButtonFeedback(dark, source, enabled, list = true, hoverLocked = expanded)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled && items.isNotEmpty(),
                role = Role.Button,
            ) {
                host?.open(
                    id = menuId,
                    items = latestItems,
                    selectedIndex = latestSelected,
                    anchorBounds = rowBounds,
                    anchorTapInRoot = lastTapRoot,
                    onSelect = { idx -> latestOnSelect(idx) },
                )
            },
        enabled = enabled,
        dark = dark,
        onClick = null,
        title = {
            UiText(
                title,
                CouiTextKind.ItemTitle,
                color = if (enabled) pageColors.title else pageColors.disabled,
            )
        },
        summary = summary?.let { s ->
            {
                UiText(
                    s,
                    CouiTextKind.ItemSummary,
                    color = if (enabled) pageColors.summary else pageColors.disabled,
                )
            }
        },
        trailing = {
            Row(
                modifier = Modifier.padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UiText(
                    text = currentLabel,
                    kind = CouiTextKind.ItemValue,
                    color = if (enabled) pageColors.summary else pageColors.disabled,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
                Image(
                    painter = painterResource(com.itos.coui.R.drawable.coui_pop_up_next),
                    contentDescription = null,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(width = 14.dp, height = 24.dp),
                    colorFilter = ColorFilter.tint(Color(if (dark) 0x4DFFFFFF else 0x42000000)),
                )
            }
        },
    )
}

@Composable
internal fun CouiSliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    steps: Int = 0,
    showTickMarks: Boolean = false,
    summary: String? = null,
    enabled: Boolean = true,
    resetLabel: String? = null,
    onReset: (() -> Unit)? = null,
    onInfoClick: (() -> Unit)? = null,
) {
    val scheme = CouiTheme.colorScheme
    val titleColor = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = 0.35f)
    val summaryColor = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.45f)
    Item(
        enabled = enabled,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    UiText(
                        text = title,
                        kind = CouiTextKind.ItemTitle,
                        color = titleColor,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (onInfoClick != null) {
                        CouiInfoIconButton(
                            contentDescription = "了解 $title",
                            onClick = onInfoClick,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (resetLabel != null && onReset != null) {
                        val dark = CouiTheme.isDark
                        val resetSource = remember { MutableInteractionSource() }
                        Box(
                            modifier = Modifier
                                .clip(CouiCapsuleShape)
                                .background(scheme.accent.copy(alpha = if (dark) 0.22f else 0.12f))
                                .clickable(
                                    interactionSource = resetSource,
                                    indication = null,
                                    enabled = enabled,
                                    role = Role.Button,
                                    onClick = onReset,
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            UiCaptionText(
                                text = resetLabel,
                                color = scheme.accentOnSurface,
                                fontSizeSp = 11f,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    UiText(valueLabel, CouiTextKind.ItemValue, color = if (enabled) scheme.accentOnSurface else summaryColor)
                }
            }
        },
        summary = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!summary.isNullOrEmpty()) {
                    UiText(summary, CouiTextKind.ItemSummary, color = summaryColor)
                }
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = value.coerceIn(valueRange.start, valueRange.endInclusive),
                    onValueChange = onValueChange,
                    onValueChangeFinished = onValueChangeFinished,
                    valueRange = valueRange,
                    steps = steps,
                    showTickMarks = showTickMarks,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

/** The label of the capsule that puts a slider back to its default. COUI has no such control of its own. */
private const val RESTORE_LABEL = "恢复"

@Composable
internal fun CouiSettingPercent(
    store: SettingsStore,
    key: String,
    title: String,
    enabled: Boolean = true,
    summary: String? = null,
    resettable: Boolean = false,
) {
    var rawValue by remember(key) {
        mutableFloatStateOf(store.getInt(key).coerceIn(0, 100) / 100f)
    }
    var dirty by remember(key) { mutableStateOf(false) }
    val intValue = (rawValue * 100f).roundToInt().coerceIn(0, 100)
    val latestInt by rememberUpdatedState(intValue)
    val latestDirty by rememberUpdatedState(dirty)
    DisposableEffect(store, key) {
        onDispose {
            if (latestDirty) store.setInt(key, latestInt)
        }
    }
    val default = remember(key) { ModuleSettings.defaultInt(key) }
    val restorable = resettable && intValue != default
    CouiSliderRow(
        title = title,
        valueLabel = "$intValue%",
        value = rawValue,
        valueRange = 0f..1f,
        enabled = enabled,
        summary = summary,
        resetLabel = if (restorable) RESTORE_LABEL else null,
        onReset = if (restorable) {
            {
                dirty = false
                rawValue = default / 100f
                store.setInt(key, default)
            }
        } else null,
        onValueChange = {
            rawValue = it.coerceIn(0f, 1f)
            dirty = true
        },
        onValueChangeFinished = {
            dirty = false
            store.setInt(key, latestInt)
        },
    )
}

@Composable
internal fun CouiSettingParam(
    store: SettingsStore,
    param: MasterStyle.Param,
    revision: Int = 0,
    enabled: Boolean = true,
    onInfoClick: (() -> Unit)? = null,
    onChanged: (() -> Unit)? = null,
) {
    val range = param.min.toFloat()..param.max.toFloat()
    var rawValue by remember(param.key, revision) {
        mutableFloatStateOf(store.getInt(param.key).coerceIn(param.min, param.max).toFloat())
    }
    var dirty by remember(param.key, revision) { mutableStateOf(false) }
    val intValue = if (param.step > 0) {
        (param.min + ((rawValue - param.min) / param.step).roundToInt() * param.step).coerceIn(param.min, param.max)
    } else {
        rawValue.roundToInt().coerceIn(param.min, param.max)
    }
    val latestInt by rememberUpdatedState(intValue)
    val latestDirty by rememberUpdatedState(dirty)
    DisposableEffect(store, param.key, revision) {
        onDispose {
            if (latestDirty) store.setInt(param.key, latestInt)
        }
    }
    val shown = if (param.scale == 1 || param.unit == "%") {
        if (param.step > 0 && intValue != 0) String.format(Locale.ROOT, "%+d", intValue) + param.unit else "$intValue${param.unit}"
    } else if (param.step > 0) {
        if (intValue == 0) "0" + param.unit else String.format(Locale.ROOT, "%+.1f", intValue / param.scale.toFloat()) + param.unit
    } else {
        String.format(Locale.ROOT, "%.2f", intValue / param.scale.toFloat()) + param.unit
    }
    CouiSliderRow(
        title = param.title,
        valueLabel = shown,
        value = rawValue,
        valueRange = range,
        steps = if (param.step > 0) (param.max - param.min) / param.step - 1 else 0,
        showTickMarks = param.step > 0,
        enabled = enabled,
        summary = param.hint.takeIf { it.isNotEmpty() },
        resetLabel = if (intValue != param.def) RESTORE_LABEL else null,
        onInfoClick = onInfoClick,
        onReset = if (intValue != param.def) {
            {
                dirty = false
                rawValue = param.def.toFloat()
                store.setInt(param.key, param.def)
                onChanged?.invoke()
            }
        } else null,
        onValueChange = {
            rawValue = it.coerceIn(range.start, range.endInclusive)
            dirty = true
        },
        onValueChangeFinished = {
            dirty = false
            store.setInt(param.key, latestInt)
            onChanged?.invoke()
        },
    )
}

@Composable
internal fun CouiTopIconAction(
    onClick: () -> Unit,
    contentDescription: String,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val descModifier = Modifier.semantics { this.contentDescription = contentDescription }
    if (Build.VERSION.SDK_INT >= 34) {
        TopAppBarButton(
            modifier = descModifier,
            onClick = onClick,
            enabled = enabled,
            content = content,
        )
    } else {
        val scheme = CouiTheme.colorScheme
        val source = remember { MutableInteractionSource() }
        val dark = CouiTheme.isDark
        Box(
            modifier = descModifier
                .size(40.dp)
                .clip(CircleShape)
                .background(scheme.container.copy(alpha = 0.85f))
                .couiButtonFeedback(dark, source, enabled)
                .clickable(
                    interactionSource = source,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}

@Composable
internal fun CouiSegmentedBar(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (Build.VERSION.SDK_INT >= 34) {
        SegmentedControl(
            modifier = modifier.fillMaxWidth(),
            labels = labels,
            selectedIndex = selectedIndex.coerceIn(0, (labels.size - 1).coerceAtLeast(0)),
            onSelected = onSelected,
            backdrop = null,
            enabled = enabled,
        )
    } else {
        val scheme = CouiTheme.colorScheme
        val dark = CouiTheme.isDark
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(CouiCapsuleShape)
                .background(if (dark) Color(0x1FFFFFFF) else Color(0x14000000))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            labels.forEachIndexed { index, label ->
                val active = index == selectedIndex
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CouiCapsuleShape)
                        .background(if (active) scheme.container else Color.Transparent)
                        .clickable(enabled = enabled) { onSelected(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    UiText(
                        text = label,
                        kind = CouiTextKind.ItemSummary,
                        color = if (active) scheme.onSurface else scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CouiChipFilterBar(
    items: List<CouiChipTabItem>,
    selectedKey: Any,
    onSelectedKeyChange: (Any) -> Unit,
    modifier: Modifier = Modifier,
    backdropBackground: Color = CouiTheme.colorScheme.background,
) {
    if (Build.VERSION.SDK_INT >= 34) {
        val chipBackdrop = rememberCouiBackdrop()
        Box(modifier = modifier.fillMaxWidth().heightIn(min = 44.dp)) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .couiBackdrop(chipBackdrop)
                    .background(backdropBackground),
            )
            ChipTabRow(
                modifier = Modifier.fillMaxWidth(),
                items = items,
                selectedKey = selectedKey,
                onSelectedKeyChange = onSelectedKeyChange,
                backdrop = chipBackdrop,
            )
        }
    } else {
        val scheme = CouiTheme.colorScheme
        val dark = CouiTheme.isDark
        Row(
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach { item ->
                val active = item.key == selectedKey
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(CouiCapsuleShape)
                        .background(
                            if (active) scheme.accent
                            else if (dark) Color(0x26FFFFFF) else Color(0x14000000),
                        )
                        .clickable(enabled = item.enabled) { onSelectedKeyChange(item.key) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    UiCaptionText(
                        text = item.label,
                        color = if (active) scheme.onAccent else scheme.onSurface,
                        fontSizeSp = 13f,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CouiCapsuleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    containerColor: Color = Color.Unspecified,
    leadingIcon: ImageVector? = null,
    height: Dp = 40.dp,
) {
    val scheme = CouiTheme.colorScheme
    val dark = CouiTheme.isDark
    val source = remember { MutableInteractionSource() }
    val bgColor = when {
        primary -> scheme.accent
        containerColor != Color.Unspecified -> containerColor
        dark -> Color(0x24FFFFFF)
        else -> Color(0x14000000)
    }
    val textColor = when {
        primary -> scheme.onAccent
        else -> scheme.onSurface
    }
    Box(
        modifier = modifier
            .height(height)
            .clip(CouiCapsuleShape)
            .background(if (enabled) bgColor else bgColor.copy(alpha = 0.45f))
            .alpha(if (enabled) 1f else 0.55f)
            .couiButtonFeedback(dark, source, enabled)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (leadingIcon != null) {
                UiIcon(
                    imageVector = leadingIcon,
                    tint = textColor,
                    modifier = Modifier.size(16.dp),
                )
            }
            UiCaptionText(
                text = text,
                color = textColor,
                fontSizeSp = 13.5f,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Floating COUI glass-surface capsule button for placement above rich optical backdrops.
 * Falls back to [CouiCapsuleButton] on Android < 14 where [CouiFloatingRenderer] is unavailable.
 */
@Composable
internal fun CouiFloatingCapsuleButton(
    text: String,
    onClick: () -> Unit,
    backdrop: CouiBackdrop,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    height: Dp = 44.dp,
) {
    val renderer = rememberCouiFloatingRenderer()
    if (renderer == null || Build.VERSION.SDK_INT < 34) {
        CouiCapsuleButton(
            text = text,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            leadingIcon = leadingIcon,
            height = height,
        )
        return
    }
    val textColor = CouiTheme.colorScheme.onSurface
    CouiFloatingCapsule(
        onClick = onClick,
        backdrop = backdrop,
        renderer = renderer,
        modifier = modifier.height(height),
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (leadingIcon != null) {
            UiIcon(
                imageVector = leadingIcon,
                tint = textColor,
                modifier = Modifier.size(16.dp),
            )
        }
        UiCaptionText(
            text = text,
            color = textColor,
            fontSizeSp = 13.5f,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The renderer for floating glass surfaces; null below Android 14 or where it cannot be created. */
@Composable
internal fun rememberCouiFloatingRenderer(): CouiFloatingRenderer? = remember {
    if (Build.VERSION.SDK_INT >= 34) runCatching { CouiFloatingRenderer.standalone() }.getOrNull() else null
}

/**
 * The floating glass capsule (凝光) of the toolbar buttons, around any row content: the surface effect
 * is not clipped (its edge light and shadow reach outside the bounds), the finger drags and scales the
 * capsule, and the spotlight follows the finger over the finished surface. Size it through [modifier].
 */
@RequiresApi(34)
@Composable
internal fun CouiFloatingCapsule(
    onClick: () -> Unit,
    backdrop: CouiBackdrop,
    renderer: CouiFloatingRenderer,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Center,
    content: @Composable RowScope.() -> Unit,
) {
    val dark = CouiTheme.isDark
    val density = LocalDensity.current
    val contour = LocalCouiContourGlowEnabled.current
    // The toolbar preset has no blur of its own (the toolbar blurs elsewhere); the card needs the backdrop blur.
    val material = remember(dark) { CouiTopAppBarButtonDefaults.material(dark, capsule = true).copy(blurRadius = 25f) }
    val shadow = remember(dark, density, contour) {
        CouiTopAppBarButtonDefaults.shadow(dark, capsule = true, density = density.density, contour = contour)
    }
    var boundsSize by remember { mutableStateOf(IntSize.Zero) }
    val contentLayer = rememberGraphicsLayer()
    val motion = rememberCouiTouchMotion(enabled)
    val press = rememberCouiPressMotion()
    val source = remember { MutableInteractionSource() }
    val (lightInput, drawLight) = rememberCouiSpotlight(
        interactive = enabled,
        preset = SpotlightPreset.OpaqueSmall2,
        dark = dark,
    )

    Box(
        modifier = modifier
            .onSizeChanged { boundsSize = it }
            .drawWithCache {
                val lightClip = Path().apply {
                    addOutline(CouiCapsuleShape.createOutline(size, layoutDirection, this@drawWithCache))
                }
                onDrawWithContent {
                    drawContent()
                    if (drawLight != null) {
                        withTransform({
                            translate(motion.translationX.value, motion.translationY.value)
                            scale(motion.scaleX.value + press.scale.value - 1f, motion.scaleY.value + press.scale.value - 1f, center)
                        }) {
                            clipPath(lightClip) { drawLight.invoke(this) }
                        }
                    }
                }
            }
            .couiFloatingSurface(
                backdrop = backdrop,
                renderer = renderer,
                material = material,
                shadow = shadow,
                shape = CouiCapsuleShape,
                contentLayer = contentLayer,
                contourGlowEnabled = contour,
                foregroundComposition = true,
            ) {
                CouiFloatingSurface(
                    bounds = Rect(
                        Offset(motion.translationX.value, motion.translationY.value),
                        Size(boundsSize.width.toFloat(), boundsSize.height.toFloat()),
                    ),
                    scaleX = motion.scaleX.value + press.scale.value - 1f,
                    scaleY = motion.scaleY.value + press.scale.value - 1f,
                    cornerWeight = 1f,
                )
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = motion.translationX.value
                    translationY = motion.translationY.value
                    scaleX = motion.scaleX.value + press.scale.value - 1f
                    scaleY = motion.scaleY.value + press.scale.value - 1f
                }
                .alpha(if (enabled) 1f else 0.55f)
                .couiTouchMotion(motion, enabled)
                .couiPressMotion(press, enabled = enabled)
                .then(lightInput)
                .drawWithContent { contentLayer.record { this@drawWithContent.drawContent() } }
                .clip(CouiCapsuleShape)
                .clickable(
                    interactionSource = source,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(contentPadding),
            horizontalArrangement = horizontalArrangement,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** The renderer for two floating glass surfaces that melt into each other; null below Android 14 or where it cannot be created. */
@Composable
internal fun rememberCouiFloatingPairRenderer(): CouiFloatingRenderer? = remember {
    if (Build.VERSION.SDK_INT >= 34) runCatching { CouiFloatingRenderer.pair() }.getOrNull() else null
}

/**
 * Two floating glass capsules (凝光) side by side that melt into one: at [merge] 1 the second has shrunk
 * into the first one's place, at 0 they are two. The bodies are COUI's two-body metaball, as the search bar
 * and its cancel button: close to each other they pull a neck. Each keeps the finger drag, press scale and
 * spotlight of [CouiFloatingCapsule]; the second one takes no touch once it is more than half merged.
 */
@Composable
internal fun CouiFloatingCapsulePair(
    merge: Float,
    onFirstClick: () -> Unit,
    onSecondClick: () -> Unit,
    backdrop: CouiBackdrop,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    gap: Dp = 10.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp),
    first: @Composable RowScope.() -> Unit,
    second: @Composable RowScope.() -> Unit,
) {
    val renderer = rememberCouiFloatingPairRenderer()
    if (renderer == null || Build.VERSION.SDK_INT < 34) {
        val scheme = CouiTheme.colorScheme
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
            for ((index, body) in listOf(first, second).withIndex()) {
                val live = enabled && (index == 0 || merge < 0.5f)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .alpha(if (index == 1 && merge >= 0.5f) 0f else 1f)
                        .clip(CouiCapsuleShape)
                        .background(scheme.card.copy(alpha = 0.7f))
                        .clickable(enabled = live, role = Role.Button, onClick = if (index == 0) onFirstClick else onSecondClick)
                        .padding(contentPadding),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    content = body,
                )
            }
        }
        return
    }
    CouiFloatingCapsulePairApi34(
        merge = merge,
        onFirstClick = onFirstClick,
        onSecondClick = onSecondClick,
        backdrop = backdrop,
        renderer = renderer,
        modifier = modifier,
        enabled = enabled,
        gap = gap,
        contentPadding = contentPadding,
        first = first,
        second = second,
    )
}

@RequiresApi(34)
@Composable
private fun CouiFloatingCapsulePairApi34(
    merge: Float,
    onFirstClick: () -> Unit,
    onSecondClick: () -> Unit,
    backdrop: CouiBackdrop,
    renderer: CouiFloatingRenderer,
    modifier: Modifier,
    enabled: Boolean,
    gap: Dp,
    contentPadding: PaddingValues,
    first: @Composable RowScope.() -> Unit,
    second: @Composable RowScope.() -> Unit,
) {
    val dark = CouiTheme.isDark
    val density = LocalDensity.current
    val contour = LocalCouiContourGlowEnabled.current
    val extension = with(density) { 4.dp.roundToPx() }
    val materials = remember(dark, extension) {
        listOf(1, 2).map { index ->
            CouiTopAppBarButtonDefaults.material(dark, capsule = true).copy(blurRadius = 25f, index = index, extension = extension)
        }
    }
    val shadow = remember(dark, density, contour) {
        CouiTopAppBarButtonDefaults.shadow(dark, capsule = true, density = density.density, contour = contour)
    }
    val merged = merge.coerceIn(0f, 1f)
    val secondLive = enabled && merged < 0.5f
    var size by remember { mutableStateOf(IntSize.Zero) }
    val gapPx = with(density) { gap.toPx() }
    val layers = listOf(rememberGraphicsLayer(), rememberGraphicsLayer())
    val touch = listOf(rememberCouiTouchMotion(enabled), rememberCouiTouchMotion(secondLive))
    val press = listOf(rememberCouiPressMotion(), rememberCouiPressMotion())
    val lights = listOf(
        rememberCouiSpotlight(interactive = enabled, preset = SpotlightPreset.OpaqueSmall2, dark = dark),
        rememberCouiSpotlight(interactive = secondLive, preset = SpotlightPreset.OpaqueSmall2, dark = dark),
    )
    val bodyWidth = ((size.width - gapPx) / 2f).coerceAtLeast(1f)

    // The second body slides to the first one's place and shrinks to half its size, fading in the last fifth
    // of the way (as COUI's search bar and cancel button); the first one stays.
    fun surface(index: Int): CouiFloatingSurface {
        val restLeft = if (index == 0) 0f else bodyWidth + gapPx
        val toFirst = if (index == 1) -restLeft * merged else 0f
        val shrink = if (index == 1) 1f - 0.5f * merged else 1f
        val motion = touch[index]
        val pressScale = press[index].scale.value
        return CouiFloatingSurface(
            bounds = Rect(
                Offset(restLeft + toFirst + motion.translationX.value, motion.translationY.value),
                Size(bodyWidth, size.height.toFloat()),
            ),
            scaleX = shrink * (motion.scaleX.value + pressScale - 1f),
            scaleY = shrink * (motion.scaleY.value + pressScale - 1f),
            cornerWeight = 1f,
            alpha = if (index == 1) 1f - ((merged - 0.8f) / 0.2f).coerceIn(0f, 1f) else 1f,
            visible = index == 0 || merged < 0.999f,
        )
    }

    val drawing = Modifier.couiFloatingPair(
        backdrop = backdrop,
        renderer = renderer,
        materials = materials,
        shadows = listOf(shadow, shadow),
        shapes = listOf(CouiCapsuleShape, CouiCapsuleShape),
        contentLayers = layers,
        contourGlowEnabled = contour,
        drawOrder = listOf(1, 0),
        foregroundComposition = true,
        beforeContent = { index, path ->
            val body = surface(index)
            val light = lights[index].second
            if (light != null && body.visible) {
                inset(body.bounds.left, body.bounds.top, this.size.width - body.bounds.right, this.size.height - body.bounds.bottom) {
                    withTransform({ scale(body.scaleX, body.scaleY, center) }) { clipPath(path) { light() } }
                }
            }
        },
        surfaces = { listOf(surface(0), surface(1)) },
    )

    Box(modifier.onSizeChanged { size = it }.then(drawing)) {
        val width = with(density) { bodyWidth.toDp() }
        // The second body is composed first: the first one, which stays, takes the touch where they overlap.
        PairBody(
            surface = { surface(1) },
            width = width,
            layer = layers[1],
            touch = touch[1],
            press = press[1],
            lightInput = lights[1].first,
            enabled = secondLive,
            dimmed = !enabled,
            onClick = onSecondClick,
            contentPadding = contentPadding,
            content = second,
        )
        PairBody(
            surface = { surface(0) },
            width = width,
            layer = layers[0],
            touch = touch[0],
            press = press[0],
            lightInput = lights[0].first,
            enabled = enabled,
            dimmed = !enabled,
            onClick = onFirstClick,
            contentPadding = contentPadding,
            content = first,
        )
    }
}

/** The content of one body of the pair, laid at the origin and moved, scaled and faded to follow its surface. */
@Composable
private fun PairBody(
    surface: () -> CouiFloatingSurface,
    width: Dp,
    layer: GraphicsLayer,
    touch: CouiTouchMotion,
    press: CouiPressMotion,
    lightInput: Modifier,
    enabled: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .graphicsLayer {
                val body = surface()
                translationX = body.bounds.left
                translationY = body.bounds.top
                scaleX = body.scaleX
                scaleY = body.scaleY
                alpha = body.alpha * if (dimmed) 0.55f else 1f
            }
            .couiTouchMotion(touch, enabled)
            .couiPressMotion(press, enabled = enabled)
            .then(lightInput)
            .drawWithContent { layer.record { this@drawWithContent.drawContent() } }
            .clip(CouiCapsuleShape)
            .clickable(
                interactionSource = source,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * 5-tab COUI floating glass capsule bottom bar without the upstream 136dp FAB reservation,
 * giving each slot ~62dp so 4-character tab labels ("色彩引擎", "其他模式") render with icons cleanly.
 */
@Composable
internal fun OmniFloatingBar(
    items: List<OmniTabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: CouiBackdrop,
    modifier: Modifier = Modifier,
    renderer: CouiFloatingRenderer? = null,
) {
    val dark = CouiTheme.isDark
    val density = LocalDensity.current
    val bottomMargin = if (WindowInsets.tappableElement.getBottom(density) > 0) 0.dp else 8.dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.tappableElement.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp + bottomMargin),
        contentAlignment = Alignment.Center,
    ) {
        if (Build.VERSION.SDK_INT >= 34) {
            val fallbackRenderer = remember(renderer) {
                if (renderer == null) runCatching { CouiFloatingRenderer.standalone() }.getOrNull() else null
            }
            val activeRenderer = renderer ?: fallbackRenderer
            if (activeRenderer != null) {
                OmniFloatingCapsuleApi34(
                    items = items,
                    selectedIndex = selectedIndex,
                    onSelect = onSelect,
                    backdrop = backdrop,
                    renderer = activeRenderer,
                    dark = dark,
                )
                return@Box
            }
        }
        OmniFloatingCapsuleFallback(
            items = items,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            dark = dark,
        )
    }
}

private fun omniNavigationReleaseTarget(
    preview: Int,
    pending: Int?,
    down: Int?,
    up: Int?,
    cancelled: Boolean,
): Int? {
    if (cancelled) return null
    if (up != null && up != down && up != preview) return up
    if (pending != null && pending == preview) return pending
    if (up != null && up == down && up != preview) return up
    return null
}

@RequiresApi(34)
@Composable
private fun OmniFloatingCapsuleApi34(
    items: List<OmniTabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: CouiBackdrop,
    renderer: CouiFloatingRenderer,
    dark: Boolean,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val scheme = CouiTheme.colorSchemeFor(dark)
    val selectedColor = scheme.accentOnSurface
    val unselectedColor = if (dark) Color(0x8CFFFFFF) else Color(0x8C000000)
    val lightColor = Color.White
    val contour = LocalCouiContourGlowEnabled.current
    val themeTint = CouiTheme.materialTintFor(dark)
    val material = remember(dark, themeTint, density) {
        val ext = with(density) { 4.dp.roundToPx() }
        CouiFloatingMaterial(
            firstColor = if (dark) Color(0x80666666) else Color(0xA3666666),
            firstBlendMode = if (dark) AndroidBlendMode.OVERLAY else AndroidBlendMode.COLOR_DODGE,
            secondColor = if (dark) Color(0xB3262626) else Color(0x80F0F0F0),
            edgeAlpha = if (dark) 0.8f else 0.25f,
            innerGlowAlpha = if (dark) 0.03f else 0.1f,
            index = 1,
            extension = ext,
            lightColor = lightColor,
            themeTint = themeTint,
        )
    }
    val shadow = remember(density, lightColor, dark, contour) {
        fun pixelOffset(dp: Float): Float = (dp * density.density).toInt().toFloat()
        fun pixelSize(dp: Float): Float {
            val px = dp * density.density
            return (if (px >= 0f) px + 0.5f else px - 0.5f).toInt().toFloat()
        }
        val projectionColor = when {
            contour -> Color(0x2D000000)
            dark -> Color(0x78000000)
            else -> Color(0x28000000)
        }
        CouiFloatingShadow(
            elevation = pixelSize(if (contour) 24f else 350f),
            color = projectionColor,
            lightY = pixelSize(if (contour) -320f else -160f),
            lightZ = if (contour) 0f else pixelSize(10000f),
            lightRadius = pixelSize(if (contour) 1600f else 2000f),
            causticColor = lightColor.copy(alpha = 0x42 / 255f),
            layout = Rect(0f, 0f, 0f, pixelOffset(4.9f)),
            sdf = pixelOffset(10.4f),
            smoothCorner = true,
        )
    }

    val motion = rememberCouiTouchMotion(true)
    val press = rememberCouiPressMotion()
    val contentLayer: GraphicsLayer = rememberGraphicsLayer()
    val (lightInput, drawLight) = rememberCouiSpotlight(
        interactive = true,
        preset = SpotlightPreset.OpaqueSmall2,
        dark = dark,
        color = lightColor,
    )
    var barSizePx by remember { mutableStateOf(Rect.Zero) }
    var rowCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val itemCoords = remember(items.size) { arrayOfNulls<LayoutCoordinates>(items.size) }
    var previewSelection by remember { mutableIntStateOf(selectedIndex) }
    val currentSelection by rememberUpdatedState(selectedIndex)
    val onSelectLatest by rememberUpdatedState(onSelect)
    LaunchedEffect(selectedIndex) { previewSelection = selectedIndex }

    BoxWithConstraints(modifier = Modifier.widthIn(max = 392.dp).fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .onGloballyPositioned { coords ->
                    barSizePx = Rect(0f, 0f, coords.size.width.toFloat(), coords.size.height.toFloat())
                }
                .couiFloatingSurface(
                    backdrop = backdrop,
                    renderer = renderer,
                    material = material,
                    shadow = shadow,
                    shape = CouiCapsuleShape,
                    contentLayer = contentLayer,
                    contourGlowEnabled = contour,
                    beforeContent = { path ->
                        if (drawLight != null) {
                            clipPath(path) { drawLight(this) }
                        }
                    },
                    surface = {
                        CouiFloatingSurface(
                            bounds = barSizePx.translate(Offset(motion.translationX.value, motion.translationY.value)),
                            scaleX = motion.scaleX.value + press.scale.value - 1f,
                            scaleY = motion.scaleY.value + press.scale.value - 1f,
                            cornerWeight = 1f,
                        )
                    },
                )
                .graphicsLayer {
                    translationX = motion.translationX.value
                    translationY = motion.translationY.value
                    scaleX = motion.scaleX.value + press.scale.value - 1f
                    scaleY = motion.scaleY.value + press.scale.value - 1f
                }
                .couiTouchMotion(motion, true)
                .couiPressMotion(press, enabled = true)
                .then(lightInput)
                .drawWithContent {
                    contentLayer.record { this@drawWithContent.drawContent() }
                }
                .clip(CouiCapsuleShape),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .selectableGroup()
                    .onGloballyPositioned { rowCoords = it }
                    .pointerInput(items.size) {
                        var active: PointerId? = null
                        var pending: Int? = null
                        var downItem: Int? = null
                        fun hit(position: Offset): Int? {
                            val root = rowCoords?.takeIf { it.isAttached } ?: return null
                            for (i in itemCoords.indices) {
                                val node = itemCoords[i]?.takeIf { it.isAttached } ?: continue
                                if (root.localBoundingBoxOf(node, false).contains(position)) return i
                            }
                            return null
                        }
                        fun finish(cancelled: Boolean, position: Offset?) {
                            val target = omniNavigationReleaseTarget(
                                preview = previewSelection,
                                pending = pending,
                                down = downItem,
                                up = position?.let(::hit),
                                cancelled = cancelled,
                            )
                            if (target != null && target != currentSelection) {
                                previewSelection = target
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onSelectLatest(target)
                            } else {
                                previewSelection = currentSelection
                            }
                            active = null
                            pending = null
                            downItem = null
                        }
                        try {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val touchCancelled = event.changes.any { it.previousPressed } && event.changes.all {
                                        !it.pressed && (!it.previousPressed ||
                                            (it.isConsumed && it.uptimeMillis == it.previousUptimeMillis &&
                                                it.position == it.previousPosition))
                                    }
                                    if (touchCancelled) {
                                        finish(true, null)
                                        continue
                                    }
                                    val tracked = event.changes.firstOrNull { it.id == active }
                                    val pointer = tracked?.takeIf { it.pressed }
                                        ?: event.changes.firstOrNull { it.pressed }
                                        ?: tracked ?: continue
                                    if (active == null) {
                                        downItem = hit(pointer.position)
                                    } else if (pointer.pressed && pointer.position != pointer.previousPosition) {
                                        hit(pointer.position)?.let { index ->
                                            if (previewSelection != index) {
                                                previewSelection = index
                                                pending = index
                                            }
                                        }
                                    }
                                    active = pointer.id
                                    if (!pointer.pressed) finish(false, pointer.position)
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } finally {
                            previewSelection = currentSelection
                        }
                    }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, item ->
                    val isSelected = previewSelection == index
                    val tint = if (isSelected) selectedColor else unselectedColor
                    val interactionSource = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .onGloballyPositioned { node -> itemCoords[index] = node }
                            .semantics(mergeDescendants = true) {
                                role = Role.Tab
                                selected = isSelected
                                onClick {
                                    if (index != currentSelection) onSelectLatest(index)
                                    true
                                }
                            }
                            .onKeyEvent {
                                if (it.type == KeyEventType.KeyUp && (it.key == Key.Enter || it.key == Key.Spacebar)) {
                                    if (index != currentSelection) onSelectLatest(index)
                                    true
                                } else false
                            }
                            .focusable(interactionSource = interactionSource),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .couiNavigationFeedback(dark, interactionSource)
                                .padding(top = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Top,
                        ) {
                            UiIcon(
                                imageVector = if (isSelected) item.selectedIcon else item.icon,
                                modifier = Modifier.size(22.dp),
                                tint = tint,
                            )
                            Spacer(Modifier.height(2.dp))
                            UiCaptionText(
                                text = item.title,
                                color = tint,
                                fontSizeSp = 10f,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OmniFloatingCapsuleFallback(
    items: List<OmniTabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    dark: Boolean,
) {
    val scheme = CouiTheme.colorSchemeFor(dark)
    val selectedColor = scheme.accentOnSurface
    val unselectedColor = scheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .widthIn(max = 392.dp)
            .fillMaxWidth()
            .height(56.dp)
            .clip(CouiCapsuleShape)
            .background(if (dark) Color(0xE6262626) else Color(0xEBFFFFFF))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            val isSelected = selectedIndex == index
            val tint = if (isSelected) selectedColor else unselectedColor
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(CouiCapsuleShape)
                    .clickable(role = Role.Tab) { onSelect(index) }
                    .padding(top = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                UiIcon(
                    imageVector = if (isSelected) item.selectedIcon else item.icon,
                    modifier = Modifier.size(22.dp),
                    tint = tint,
                )
                Spacer(Modifier.height(2.dp))
                UiCaptionText(
                    text = item.title,
                    color = tint,
                    fontSizeSp = 10f,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}
