package local.jiege.ui

import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.itos.coui.backdrop.CouiBackdrop
import com.itos.coui.backdrop.couiBackdrop
import com.itos.coui.backdrop.rememberCouiBackdrop
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogActionStyle
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.dialog.Dialog
import com.itos.coui.dialog.LoadingDialog
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.shape.CouiCapsuleShape
import com.itos.coui.text.CouiTextKind
import com.itos.coui.theme.CouiColor
import com.itos.coui.theme.CouiTheme
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.jiege.settings.DiagnosticsContract
import local.jiege.settings.ModuleSettings

private const val CAMERA_PACKAGE = "com.oplus.camera"
private val DIAGNOSTIC_PLACEHOLDERS = listOf("LSPosed", "KSU 模块", "相机注入", "相册注入")
private val APERTURE_OPEN_RATIOS = listOf(0.46f, 0.37f, 0.28f, 0.20f, 0.12f, 0.06f)

/** The leaf shutter of the Hasselblad XCD V and P lenses has ten blades. */
private const val IRIS_BLADES = 10

/**
 * The scale around the lens: [SHUTTER_TICKS] ticks all the way round (canvas degrees, clockwise from
 * 3 o'clock), a long one every 30 degrees, a middle one every 5, short ones between. The whole scale is
 * turned about the lens by [TICK_GRID_DEG] so that long ticks sit on the radii of the text. A frame of a
 * roll of [SHUTTER_ROLL_FRAMES] is 10 degrees of it, counted from [TICK_FIRST_DEG] (upper left, where the
 * card shows the rim) down the left side; the card cuts the ring at the top and on the right.
 * The count is written on two radii 7.5 degrees apart, both below the horizontal one and sloping up toward
 * the lens, 5 and 12.5 degrees off it: the caption on [SHUTTER_CAPTION_DEG], the value on
 * [SHUTTER_VALUE_DEG]. Both ticks are long.
 */
private const val SHUTTER_ROLL_FRAMES = 36
private const val SHUTTER_TICKS = 144
private const val TICK_GRID_DEG = 0f
private const val TICK_FIRST_DEG = 210f
private const val SHUTTER_CAPTION_DEG = 175f
private const val SHUTTER_VALUE_DEG = 167.5f

/** [busyTarget] while both processes are being ended (the other values are package names). */
private const val INIT_TARGET = "initialise"

/** A tap within this distance of the lens centre (the iris and its scale) turns the iris. */
private val LENS_TAP_RADIUS = 124.dp

/** Shared holder so restart actions in Filter or Home refresh live diagnostics. */
internal class InjectionHolder {
    var value by mutableStateOf<InjectionState?>(null)
    var revision by mutableIntStateOf(0)
}

private val injectionHolder = InjectionHolder()
private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val preloadStarted = AtomicBoolean(false)
@Volatile private var preloadJob: Job? = null

internal fun preloadHomeDiagnostics(appContext: Context) {
    if (!preloadStarted.compareAndSet(false, true)) return
    preloadJob = preloadScope.launch {
        injectionHolder.value = InjectionState.read(appContext.applicationContext)
    }
}

@Composable
internal fun rememberInjectionState(): InjectionHolder {
    val context = LocalContext.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    var wasResumed by remember { mutableStateOf(false) }
    LaunchedEffect(resumed, injectionHolder.revision) {
        if (resumed) {
            val activePreload = preloadJob
            if (injectionHolder.value == null && activePreload != null && activePreload.isActive) {
                activePreload.join()
            } else if (!wasResumed || injectionHolder.revision > 0 || injectionHolder.value == null) {
                if (injectionHolder.value == null || wasResumed || injectionHolder.revision > 0 || injectionHolder.value?.isStale() == true) {
                    injectionHolder.value = withContext(Dispatchers.IO) {
                        InjectionState.read(context.applicationContext)
                    }
                }
            }
        }
        wasResumed = resumed
    }
    return injectionHolder
}

internal fun requestInjectionRefresh() {
    injectionHolder.revision++
}

@Composable
internal fun HomeScreen(
    contentPadding: PaddingValues,
    nestedScroll: NestedScrollConnection,
    overscrollEffect: OverscrollEffect,
    listState: LazyListState = rememberLazyListState(),
    onOpenLog: () -> Unit,
) {
    val context = LocalContext.current
    val state = rememberInjectionState()
    val moduleVersion = rememberVersionName(context.packageName)
    val cameraVersion = rememberVersionName(CAMERA_PACKAGE)
    val galleryVersion = rememberVersionName(DiagnosticsContract.GALLERY_PACKAGE)
    val cardModifier = Modifier.fillMaxWidth()

    var showFortune by remember { mutableStateOf(false) }
    var fortuneEverOpened by remember { mutableStateOf(false) }
    if (showFortune) fortuneEverOpened = true

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(nestedScroll),
        contentPadding = contentPadding,
        overscrollEffect = overscrollEffect,
    ) {
        item(key = "status_banner") {
            StatusBannerCard(
                version = moduleVersion,
                state = state,
                onOpenEasterEgg = { showFortune = true },
                modifier = Modifier
                    .padding(horizontal = CouiPageDefaults.horizontalMargin)
                    .padding(top = 12.dp)
                    .fillMaxWidth(),
            )
        }
        item(key = "diagnostics") {
            val checks = state.value?.checks
            val red = if (CouiTheme.isDark) CouiColor.Night.RED else CouiColor.Day.RED
            UiGroup(title = "运行诊断", modifier = cardModifier) {
                if (checks == null) {
                    DIAGNOSTIC_PLACEHOLDERS.forEachIndexed { idx, title ->
                        UiInfoRow(
                            title = title,
                            summary = "正在读取运行状态",
                            value = "检查中",
                        )
                        if (idx < DIAGNOSTIC_PLACEHOLDERS.lastIndex) UiDivider()
                    }
                } else {
                    checks.forEachIndexed { idx, check ->
                        UiInfoRow(
                            title = check.title,
                            summary = check.detail,
                            summaryColor = if (check.ok) CouiTheme.colorScheme.onSurfaceVariant else red,
                            value = if (check.ok) "正常" else "异常",
                            valueColor = if (check.ok) CouiTheme.colorScheme.onSurfaceVariant else red,
                        )
                        if (idx < checks.lastIndex) UiDivider()
                    }
                }
                UiDivider()
                CouiNavRow(
                    title = "日志与调试",
                    summary = "系统日志、诊断包导出与帧转储",
                    onClick = onOpenLog,
                )
            }
        }
        item(key = "verification") {
            DistributionUi.HomeVerificationSlot(cardModifier)
        }
        item(key = "device_info") {
            UiGroup(title = "版本与设备", modifier = cardModifier) {
                UiInfoRow(icon = InfoModuleIcon, title = "模块版本", value = moduleVersion)
                UiDivider()
                UiInfoRow(icon = CameraIcon, title = "相机版本", value = cameraVersion)
                UiDivider()
                UiInfoRow(icon = GalleryIcon, title = "相册版本", value = galleryVersion)
                UiDivider()
                UiInfoRow(icon = PhoneIcon, title = "设备型号", value = "${Build.MANUFACTURER} ${Build.MODEL}")
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (fortuneEverOpened) {
        FilmFortuneEasterEggDialog(
            visible = showFortune,
            shutterToday = state.value?.shutterToday,
            onDismiss = { showFortune = false },
        )
    }
}

@Composable
private fun StatusBannerCard(
    version: String,
    state: InjectionHolder,
    onOpenEasterEgg: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val dark = CouiTheme.isDark
    val scheme = CouiTheme.colorScheme
    val health = state.value?.health
    val cardBackdrop = rememberCouiBackdrop()
    val shutterCount = state.value?.shutterCount
    val textMeasurer = rememberTextMeasurer()
    val litFrames = shutterCount?.takeIf { it > 0 }?.let { ((it - 1) % SHUTTER_ROLL_FRAMES + 1).toFloat() } ?: 0f
    val litTicks = remember { Animatable(0f) }
    LaunchedEffect(litFrames) {
        litTicks.animateTo(litFrames, animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing))
    }
    val edition = DistributionUi.BRAND_TITLE.substringAfterLast(' ', "")
    val displayVersion = if (edition.isNotEmpty() && version.startsWith("$edition ", ignoreCase = true)) {
        "v" + version.substring(edition.length + 1).trimStart('v', 'V')
    } else {
        version
    }
    val (statusColor, statusBg) = when (health) {
        Health.OK -> if (dark) Color(0xFF38CC66) to Color(0x2438CC66) else Color(0xFF129A43) to Color(0x14129A43)
        Health.FAILED -> if (dark) Color(0xFFFF6157) to Color(0x24FF6157) else Color(0xFFE03B32) to Color(0x14E03B32)
        Health.PENDING, null -> if (dark) Color(0xFFF59A38) to Color(0x24F59A38) else Color(0xFFD9730D) to Color(0x16D9730D)
    }

    val ringOuterPath = remember { Path() }
    val ringInnerPath = remember { Path() }
    val ringPath = remember { Path() }
    val diskBladePath = remember { Path() }
    val diskNeighborPath = remember { Path() }
    val diskHolePath = remember { Path() }
    val tempBladePath = remember { Path() }
    val bladePath = remember { Path() }

    var busyTarget by remember { mutableStateOf<String?>(null) }
    var tapStep by remember { mutableIntStateOf(0) }
    var totalTaps by remember { mutableIntStateOf(0) }
    var triggerPending by remember { mutableStateOf(false) }

    val animatedStatusColor by animateColorAsState(
        targetValue = statusColor,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "heroStatusTint",
    )

    val introOffset = remember { Animatable(0.14f) }
    val restartSnap = remember { Animatable(0f) }
    val activeWaves = remember { mutableStateListOf<Animatable<Float, AnimationVector1D>>() }
    LaunchedEffect(Unit) {
        introOffset.animateTo(0f, animationSpec = tween(durationMillis = 680, easing = FastOutSlowInEasing))
    }

    val baseOpenRatio by animateFloatAsState(
        targetValue = APERTURE_OPEN_RATIOS[tapStep.coerceIn(0, APERTURE_OPEN_RATIOS.lastIndex)],
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "heroApertureOpenRatio",
    )
    val openRatio = (baseOpenRatio + introOffset.value - restartSnap.value).coerceIn(0.05f, 0.56f)
    val irisScale by animateFloatAsState(
        targetValue = 1f - tapStep * 0.015f - restartSnap.value * 0.08f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "heroApertureScale",
    )
    val irisRotation by animateFloatAsState(
        targetValue = totalTaps * 13f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
        label = "heroApertureRotation",
    )

    // Only the lens answers a tap (the iris turns, a ring runs out, five taps open the easter egg).
    fun onLensTap() {
        if (triggerPending) return
        // The egg pays out draws from today's shutters: read the count again before the fifth tap.
        if (tapStep == 0) state.revision++
        totalTaps++
        scope.launch {
            val wave = Animatable(0f)
            activeWaves.add(wave)
            try {
                wave.animateTo(1f, animationSpec = tween(2400, easing = LinearEasing))
            } finally {
                activeWaves.remove(wave)
            }
        }
        val next = tapStep + 1
        if (next < 5) {
            tapStep = next
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        } else {
            tapStep = 5
            triggerPending = true
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            scope.launch {
                delay(150)
                onOpenEasterEgg()
                tapStep = 0
                triggerPending = false
            }
        }
    }

    Box(
        modifier = modifier
            .clip(UiCardShape)
            .semantics { contentDescription = "运行状态" }
            .pointerInput(onOpenEasterEgg) {
                detectTapGestures { tap ->
                    // The lens as drawn below: centred 34dp in from the right edge and 76dp down, out to its scale.
                    val reach = LENS_TAP_RADIUS.toPx()
                    if (hypot(tap.x - (size.width - 34.dp.toPx()), tap.y - 76.dp.toPx()) <= reach) onLensTap()
                }
            },
    ) {
        val tapProgress = (tapStep / 5f).coerceIn(0f, 1f)
        val baseTone = if (dark) Color(0xFFE8ECF2) else Color(0xFF1D2129)
        val irisTone = lerp(baseTone, scheme.accent, tapProgress * 0.55f)
        val evenBladeColor = irisTone.copy(alpha = (if (dark) 0.115f else 0.082f) + tapStep * (if (dark) 0.018f else 0.014f))
        val oddBladeColor = irisTone.copy(alpha = (if (dark) 0.085f else 0.058f) + tapStep * (if (dark) 0.015f else 0.011f))
        val barrelColor = irisTone.copy(alpha = (if (dark) 0.090f else 0.068f) + tapStep * 0.012f)
        val hairlineColor = irisTone.copy(alpha = (if (dark) 0.140f else 0.105f) + tapStep * 0.014f)
        val mountDotColor = scheme.accent.copy(alpha = if (dark) 0.85f else 0.78f)

        // Clipped card surface: status-tinted backdrop + 10-blade optical iris + shutter frame scale.
        // Uses matchParentSize so decorative geometry never inflates the card height.
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .zIndex(0f)
                .clip(UiCardShape)
                .couiBackdrop(cardBackdrop)
                .background(scheme.card),
        ) {
            val cx = size.width - 34.dp.toPx()
            val cy = 76.dp.toPx()
            val outerR = 108.dp.toPx() * irisScale

            drawRect(
                brush = Brush.radialGradient(
                    0f to animatedStatusColor.copy(alpha = if (dark) 0.18f else 0.12f),
                    0.55f to animatedStatusColor.copy(alpha = if (dark) 0.085f else 0.055f),
                    1f to animatedStatusColor.copy(alpha = if (dark) 0.015f else 0.01f),
                    center = Offset(0f, size.height),
                    radius = size.width * 1.15f,
                ),
            )

            rotate(degrees = irisRotation, pivot = Offset(cx, cy)) {
                val barrelThickness = outerR * 0.115f
                val innerBarrelR = outerR - barrelThickness
                val ringGap = outerR * 0.028f
                val bladeR = innerBarrelR - ringGap
                val curveR = bladeR * 1.65f
                val slitGap = outerR * 0.022f
                val r1 = bladeR * openRatio
                val strokePx = 0.85.dp.toPx()

                ringOuterPath.reset()
                ringOuterPath.addOval(Rect(cx - outerR, cy - outerR, cx + outerR, cy + outerR))
                ringInnerPath.reset()
                ringInnerPath.addOval(Rect(cx - innerBarrelR, cy - innerBarrelR, cx + innerBarrelR, cy + innerBarrelR))
                ringPath.op(ringOuterPath, ringInnerPath, PathOperation.Difference)
                drawPath(path = ringPath, color = barrelColor)

                drawCircle(color = hairlineColor, radius = outerR, center = Offset(cx, cy), style = Stroke(width = strokePx))
                drawCircle(color = hairlineColor, radius = innerBarrelR, center = Offset(cx, cy), style = Stroke(width = strokePx))
                drawCircle(
                    color = hairlineColor.copy(alpha = hairlineColor.alpha * 0.75f),
                    radius = bladeR + ringGap * 0.45f,
                    center = Offset(cx, cy),
                    style = Stroke(width = strokePx * 0.85f),
                )

                val dotAngle = 148.0 * (PI / 180.0)
                val dotTrackR = outerR - barrelThickness * 0.5f
                val dotCenter = Offset(
                    x = cx + dotTrackR * cos(dotAngle).toFloat(),
                    y = cy + dotTrackR * sin(dotAngle).toFloat(),
                )
                drawCircle(
                    color = mountDotColor,
                    radius = 2.2.dp.toPx(),
                    center = dotCenter,
                )

                diskBladePath.reset()
                diskBladePath.addOval(Rect(cx - bladeR, cy - bladeR, cx + bladeR, cy + bladeR))

                val bladeStep = (2.0 * PI) / IRIS_BLADES
                for (k in 0 until IRIS_BLADES) {
                    val th0 = k * bladeStep
                    val th1 = ((k + 1) % IRIS_BLADES) * bladeStep
                    val q0x = cx - (curveR - r1) * cos(th0).toFloat()
                    val q0y = cy - (curveR - r1) * sin(th0).toFloat()
                    val q1x = cx - (curveR - r1) * cos(th1).toFloat()
                    val q1y = cy - (curveR - r1) * sin(th1).toFloat()
                    val neighborR = curveR - slitGap

                    diskNeighborPath.reset()
                    diskNeighborPath.addOval(Rect(q1x - neighborR, q1y - neighborR, q1x + neighborR, q1y + neighborR))
                    diskHolePath.reset()
                    diskHolePath.addOval(Rect(q0x - curveR, q0y - curveR, q0x + curveR, q0y + curveR))

                    tempBladePath.op(diskBladePath, diskNeighborPath, PathOperation.Intersect)
                    bladePath.op(tempBladePath, diskHolePath, PathOperation.Difference)
                    drawPath(path = bladePath, color = if (k % 2 == 0) evenBladeColor else oddBladeColor)
                }
            }

            // Frame scale around the lens (fixed: the iris turns, the scale does not) and the total on a radius.
            val tickInner = outerR + 5.dp.toPx()
            val tickLong = 11.dp.toPx()
            val unlitTick = irisTone.copy(alpha = if (dark) 0.26f else 0.22f)
            val tickStep = 360f / SHUTTER_TICKS
            val frontDeg = litTicks.value * (360f / SHUTTER_ROLL_FRAMES)
            val valueTick = Math.round((SHUTTER_VALUE_DEG - TICK_GRID_DEG) / tickStep)
            for (j in 0 until SHUTTER_TICKS) {
                val degrees = TICK_GRID_DEG + j * tickStep
                val phi = degrees * (PI / 180.0)
                // How far down the left side this tick lies from the first frame mark.
                val swept = ((TICK_FIRST_DEG - degrees) % 360f + 360f) % 360f
                val lit = swept < frontDeg - 0.01f
                val newest = lit && swept >= frontDeg - tickStep - 0.01f
                val labelled = abs(degrees - SHUTTER_CAPTION_DEG) < 0.01f || abs(degrees - SHUTTER_VALUE_DEG) < 0.01f
                val length = when {
                    Math.floorMod(j - valueTick, 12) == 0 || labelled -> tickLong
                    Math.floorMod(j - valueTick, 2) == 0 -> 8.dp.toPx()
                    else -> 4.5.dp.toPx()
                } + if (newest) 2.dp.toPx() else 0f
                val ux = cos(phi).toFloat()
                val uy = sin(phi).toFloat()
                drawLine(
                    color = if (lit) scheme.accent else unlitTick,
                    start = Offset(cx + ux * tickInner, cy + uy * tickInner),
                    end = Offset(cx + ux * (tickInner + length), cy + uy * (tickInner + length)),
                    strokeWidth = (if (lit) 1.3f else 0.9f).dp.toPx(),
                    cap = StrokeCap.Butt,
                )
            }
            if (shutterCount != null) {
                val captionStyle = TextStyle(
                    color = scheme.onSurfaceVariant,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.6.sp,
                )
                val valueStyle = TextStyle(
                    color = scheme.accent,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.4.sp,
                )
                // Right where the long ticks end, on the radius of the tick each line belongs to.
                val radius = tickInner + tickLong + 4.dp.toPx()
                val lines = listOf(
                    textMeasurer.measure("有效快门", captionStyle) to SHUTTER_CAPTION_DEG,
                    textMeasurer.measure("${shutterCount}次", valueStyle) to SHUTTER_VALUE_DEG,
                )
                for ((layout, degrees) in lines) {
                    val phi = degrees * (PI / 180.0)
                    val anchor = Offset(cx + cos(phi).toFloat() * radius, cy + sin(phi).toFloat() * radius)
                    // Baseline along the radius, reading left to right toward the lens: its right end at the anchor.
                    withTransform({ rotate(degrees = degrees - 180f, pivot = anchor) }) {
                        drawText(
                            textLayoutResult = layout,
                            topLeft = Offset(anchor.x - layout.size.width, anchor.y - layout.size.height / 2f),
                        )
                    }
                }
            }
        }

        // Card-clipped ripple layer below foreground text & buttons (zIndex 0.5 < 1.0):
        // expands linearly from the iris outer ring all the way to the card edges and fades out there.
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .zIndex(0.5f),
        ) {
            if (activeWaves.isNotEmpty()) {
                val cx = size.width - 34.dp.toPx()
                val cy = 76.dp.toPx()
                val outerR = 108.dp.toPx() * irisScale
                val maxTravel = (size.width - outerR * 0.35f).coerceAtLeast(180.dp.toPx())
                for (wave in activeWaves) {
                    val pulse = wave.value
                    if (pulse > 0f && pulse < 1f) {
                        drawCircle(
                            color = scheme.accent.copy(alpha = (1f - pulse) * (1f - pulse * 0.25f) * 0.62f),
                            radius = outerR + pulse * maxTravel,
                            center = Offset(cx, cy),
                            style = Stroke(width = (2.2f - pulse * 1.2f).dp.toPx()),
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(1f)
                .padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 20.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 96.dp),
            ) {
                UiCaptionText(
                    text = DistributionUi.BRAND_TITLE,
                    color = scheme.onSurface,
                    fontSizeSp = 22f,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                VersionTag(
                    text = displayVersion,
                    color = statusColor,
                    background = statusBg,
                    backdrop = cardBackdrop,
                    onClick = { state.revision++ },
                )
            }
            Spacer(Modifier.height(50.dp))
            // The camera and the gallery both still run the previous hooks: the gallery button melts into the
            // camera one, which then ends both processes (they start again with the new hooks when next used).
            val initialising = busyTarget == INIT_TARGET
            val mergeTarget = if (state.value?.bothStale == true || initialising) 1f else 0f
            // COUI's own two-body pair (the search bar and its cancel button): bounce 0.2, response 0.45 s to
            // merge, 0.55 s to part.
            val mergeResponse = if (mergeTarget > 0.5f) 0.45f else 0.55f
            val merge by animateFloatAsState(
                targetValue = mergeTarget,
                animationSpec = spring(
                    dampingRatio = 0.8f,
                    stiffness = ((2.0 * PI / mergeResponse) * (2.0 * PI / mergeResponse)).toFloat(),
                ),
                label = "heroButtonsMerge",
            )
            val merging = merge > 0.5f
            val buttonTextColor = scheme.onSurface

            fun snapIris() {
                scope.launch {
                    restartSnap.animateTo(0.18f, animationSpec = tween(140))
                    restartSnap.animateTo(0f, animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow))
                }
            }

            fun restartApp(packageName: String, label: String) {
                busyTarget = packageName
                snapIris()
                scope.launch {
                    val problem = withContext(Dispatchers.IO) {
                        AppRestart.restart(context.applicationContext, packageName, label)
                    }
                    busyTarget = null
                    if (problem != null) {
                        Toast.makeText(context, problem, Toast.LENGTH_LONG).show()
                    }
                    delay(6000)
                    state.revision++
                }
            }

            fun initialise() {
                busyTarget = INIT_TARGET
                snapIris()
                scope.launch {
                    val problem = withContext(Dispatchers.IO) {
                        AppRestart.stop(listOf(CAMERA_PACKAGE to "相机", DiagnosticsContract.GALLERY_PACKAGE to "相册"))
                    }
                    if (problem != null) {
                        Toast.makeText(context, problem, Toast.LENGTH_LONG).show()
                    }
                    delay(1500)
                    busyTarget = null
                    // Reading the state starts both processes again; the buttons part once neither is stale.
                    state.revision++
                }
            }

            CouiFloatingCapsulePair(
                merge = merge,
                backdrop = cardBackdrop,
                enabled = busyTarget == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                onFirstClick = { if (merging) initialise() else restartApp(CAMERA_PACKAGE, "相机") },
                onSecondClick = { restartApp(DiagnosticsContract.GALLERY_PACKAGE, "相册") },
                first = {
                    if (!merging) {
                        UiIcon(imageVector = CameraIcon, tint = buttonTextColor, modifier = Modifier.size(16.dp))
                    }
                    UiCaptionText(
                        text = when {
                            initialising -> "正在初始化…"
                            merging -> "初始化"
                            busyTarget == CAMERA_PACKAGE -> "正在重启…"
                            else -> "重启相机"
                        },
                        color = buttonTextColor,
                        fontSizeSp = 13.5f,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                second = {
                    UiIcon(imageVector = GalleryIcon, tint = buttonTextColor, modifier = Modifier.size(16.dp))
                    UiCaptionText(
                        text = if (busyTarget == DiagnosticsContract.GALLERY_PACKAGE) "正在重启…" else "重启相册",
                        color = buttonTextColor,
                        fontSizeSp = 13.5f,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

/** The version tag: a 凝光 glass capsule with the status dot. Tapping it reads the diagnostics again. */
@Composable
private fun VersionTag(
    text: String,
    color: Color,
    background: Color,
    backdrop: CouiBackdrop,
    onClick: () -> Unit,
) {
    val content: @Composable RowScope.() -> Unit = {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(color),
        )
        UiCaptionText(
            text = text,
            color = color,
            fontSizeSp = 16f,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Start,
            maxLines = 1,
        )
    }
    val renderer = rememberCouiFloatingRenderer()
    if (renderer != null && Build.VERSION.SDK_INT >= 34) {
        CouiFloatingCapsule(
            onClick = onClick,
            backdrop = backdrop,
            renderer = renderer,
            modifier = Modifier.height(37.dp).width(IntrinsicSize.Max),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    } else {
        Row(
            modifier = Modifier
                .clip(CouiCapsuleShape)
                .background(background)
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun FilmFortuneEasterEggDialog(
    visible: Boolean,
    shutterToday: Long?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val dark = CouiTheme.isDark
    val scheme = CouiTheme.colorScheme
    val (material, backdrop) = rememberOmniDialogMaterial(dark)
    val draws = remember(context) { FortuneDraws(context, CAMERA_FORTUNES.size) }
    // The draws, the card on the table and the collection are read again after each action and each time the egg opens.
    var revision by remember { mutableIntStateOf(0) }
    val pending = remember(revision, visible) { draws.pending() }
    val collected = remember(revision, visible) { draws.collected() }
    // Cards already taken can always be looked through, newest first, whether or not a draw is left.
    var viewPos by remember(visible) { mutableIntStateOf(0) }
    val newestFirst = collected.asReversed()
    val browsed = if (pending == null && newestFirst.isNotEmpty()) newestFirst[viewPos.coerceIn(0, newestFirst.lastIndex)] else null
    val shownCard = pending ?: browsed
    val entry = shownCard?.let { CAMERA_FORTUNES.getOrNull(it) }
    val available = if (shutterToday == null) 0 else draws.available(shutterToday)
    val primaryColors = CouiDialogDefaults.primaryActionColors(scheme.accent)

    val drawStatus = when {
        shutterToday == null -> "读不到快门计数"
        available > 0 -> "可抽 $available 次"
        else -> "再拍 ${draws.shotsToNext(shutterToday)} 张可抽一次"
    }
    val footer = when {
        pending != null -> "已收 ${collected.size}/${CAMERA_FORTUNES.size}" + if (pending in collected) " · 这张已经有了" else ""
        browsed != null -> "第 ${viewPos.coerceIn(0, newestFirst.lastIndex) + 1}/${newestFirst.size} 张 · $drawStatus"
        else -> ""
    }

    fun drawOne() {
        shutterToday?.let { draws.draw(it) }
        viewPos = 0
        revision++
    }

    fun nextCard() {
        if (newestFirst.isNotEmpty()) viewPos = (viewPos + 1) % newestFirst.size
    }

    val close = CouiDialogAction(text = "关闭", onClick = onDismiss)
    val drawAction = CouiDialogAction(
        text = "抽一张",
        enabled = available > 0,
        style = CouiDialogActionStyle.Primary,
        colors = primaryColors,
        onClick = ::drawOne,
    )
    val confirm: CouiDialogAction
    val dismiss: CouiDialogAction?
    val neutral: CouiDialogAction?
    when {
        // A card on the table: it counts only when it is taken.
        pending != null -> {
            confirm = CouiDialogAction(
                text = "收下",
                style = CouiDialogActionStyle.Primary,
                colors = primaryColors,
                onClick = {
                    draws.take()
                    revision++
                },
            )
            dismiss = CouiDialogAction(
                text = "放弃",
                onClick = {
                    draws.putBack()
                    revision++
                },
            )
            neutral = null
        }
        browsed != null && available > 0 -> {
            confirm = drawAction
            dismiss = close
            neutral = if (newestFirst.size > 1) CouiDialogAction(text = "下一张", onClick = ::nextCard) else null
        }
        browsed != null && newestFirst.size > 1 -> {
            confirm = CouiDialogAction(
                text = "下一张",
                style = CouiDialogActionStyle.Primary,
                colors = primaryColors,
                onClick = ::nextCard,
            )
            dismiss = close
            neutral = null
        }
        browsed != null -> {
            confirm = CouiDialogAction(
                text = "关闭",
                style = CouiDialogActionStyle.Primary,
                colors = primaryColors,
                onClick = onDismiss,
            )
            dismiss = null
            neutral = null
        }
        else -> {
            confirm = drawAction
            dismiss = close
            neutral = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        title = entry?.title ?: "抽一张卡",
        dark = dark,
        material = material,
        backdrop = backdrop,
        visible = visible,
        confirmAction = confirm,
        dismissAction = dismiss,
        neutralAction = neutral,
    ) {
        val compact = LocalConfiguration.current.fontScale > 1.15f
        val padding = PaddingValues(
            start = CouiDialogDefaults.contentPadding,
            top = 0.dp,
            end = CouiDialogDefaults.contentPadding,
            bottom = if (compact) 14.dp else 20.dp,
        )
        if (entry != null) {
            FortuneCard(entry = entry, footer = footer, compact = compact, padding = padding)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (shutterToday == null) {
                    UiCaptionText(
                        text = "读不到快门计数，先打开相机拍几张。",
                        color = scheme.onSurfaceVariant,
                        fontSizeSp = 14f,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                    )
                } else {
                    UiCaptionText(
                        text = "今天有效快门 $shutterToday 张",
                        color = scheme.onSurface.copy(alpha = 0.88f),
                        fontSizeSp = 14.5f,
                        fontWeight = FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    UiCaptionText(
                        text = drawStatus,
                        color = if (available > 0) scheme.accent else scheme.onSurfaceVariant,
                        fontSizeSp = 16f,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
                UiCaptionText(
                    text = "大师、XPAN 与理光模式每 $FORTUNE_DRAW_EVERY 张可抽一次，收下的卡才算数",
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSizeSp = 12f,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

/** One card: what it is, its story and the line it ends on, and a small [footer] line. */
@Composable
private fun FortuneCard(
    entry: FortuneEntry,
    footer: String,
    compact: Boolean,
    padding: PaddingValues,
) {
    val dark = CouiTheme.isDark
    val scheme = CouiTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 380.dp)
            .verticalScroll(rememberScrollState())
            .padding(padding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        UiCaptionText(
            text = entry.subtitle,
            color = scheme.onSurfaceVariant,
            fontSizeSp = 13.5f,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(if (compact) 12.dp else 14.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(scheme.onSurface.copy(alpha = if (dark) 0.12f else 0.09f)),
        )
        Spacer(Modifier.height(if (compact) 12.dp else 14.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 14.dp),
        ) {
            UiCaptionText(
                text = entry.story,
                color = scheme.onSurface.copy(alpha = 0.88f),
                fontSizeSp = 14.5f,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = Int.MAX_VALUE,
            )
            UiCaptionText(
                text = entry.mood,
                color = scheme.onSurfaceVariant,
                fontSizeSp = 13.5f,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 4,
            )
            UiCaptionText(
                text = footer,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSizeSp = 12f,
                fontWeight = FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun LogScreen(
    contentPadding: PaddingValues,
    nestedScroll: NestedScrollConnection,
    overscrollEffect: OverscrollEffect,
    listState: LazyListState = rememberLazyListState(),
) {
    val context = LocalContext.current
    val store = remember(context) { SettingsStore(context) }
    val scope = rememberCoroutineScope()
    val dark = CouiTheme.isDark
    val (material, backdrop) = rememberOmniDialogMaterial(dark)
    var recording by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        recording = withContext(Dispatchers.IO) { LogCapture.running(context.applicationContext) }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exporting = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)!!.use { LogExport.write(context.applicationContext, it) }
                }
            }
            exporting = false
            Toast.makeText(
                context,
                if (result.isSuccess) "日志已导出" else "导出失败：${result.exceptionOrNull()?.message}",
                Toast.LENGTH_SHORT,
            ).show()
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
        item(key = "system_logs") {
            UiGroup(title = "系统日志") {
                CouiSwitchRow(
                    title = "开启日志",
                    summary = "后台持续记录，复现问题后导出",
                    checked = recording,
                    enabled = !exporting,
                    loading = busy,
                    onCheckedChange = { on ->
                        busy = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                if (on) LogCapture.start(context.applicationContext)
                                else {
                                    LogCapture.stop(context.applicationContext)
                                    true
                                }
                            }
                            recording = withContext(Dispatchers.IO) { LogCapture.running(context.applicationContext) }
                            busy = false
                            if (!ok) Toast.makeText(context, "没有 Root 授权，无法记录", Toast.LENGTH_SHORT).show()
                        }
                    },
                )
                UiDivider()
                CouiNavRow(
                    title = "导出日志",
                    summary = "打包运行状态、相机日志与当前设置",
                    enabled = !busy && !exporting,
                    onClick = { exportLauncher.launch(LogExport.fileName()) },
                )
            }
        }
        item(key = "dumps") {
            UiGroup(
                title = "底层成片转储",
                footer = "调试专用，单张帧体积巨大，日常拍摄请保持关闭。",
            ) {
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.DUMP_RAW,
                    title = "RAW 转储",
                    summary = "HybridRaw 逐帧原始输入",
                )
                UiDivider()
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.DUMP_CCM,
                    title = "ccmVec 转储",
                    summary = "白平衡与 CCM 后、Gamma 前的线性 RGB",
                )
                UiDivider()
                CouiSettingSwitch(
                    store = store,
                    key = ModuleSettings.DUMP_HDRT,
                    title = "HDRTransform 转储",
                    summary = "大师主图与快图的 HDR 输入及 SDR 输出",
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    LoadingDialog(
        visible = exporting,
        message = "正在打包诊断日志…",
        dark = dark,
        material = material,
        backdrop = backdrop,
    )
}

@Composable
private fun rememberVersionName(packageName: String): String {
    val context = LocalContext.current
    return remember(packageName) {
        try {
            context.packageManager.getPackageInfo(packageName, 0).versionName ?: "未知"
        } catch (_: Exception) {
            "未安装"
        }
    }
}
