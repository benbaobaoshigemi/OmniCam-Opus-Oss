package local.jiege.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.itos.coui.backdrop.couiBackdrop
import com.itos.coui.backdrop.rememberCouiBackdrop
import com.itos.coui.effect.CouiFloatingRenderer
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.Scaffold
import com.itos.coui.menu.ProvideCouiMenuBackdrop
import com.itos.coui.menu.rememberCouiWindowBackdrop
import com.itos.coui.scroll.rememberCouiOverscrollEffect
import com.itos.coui.theme.CouiPresets
import com.itos.coui.theme.CouiTheme
import com.itos.coui.topappbar.CouiTopAppBarDefaults
import com.itos.coui.topappbar.LargeTopAppBar

private const val TAB_STATUS = 0
private const val TAB_COLOR = 1
private const val TAB_FILTER = 2
private const val TAB_MODES = 3
private const val TAB_INTERACTION = 4

internal enum class SubPage(val title: String) {
    LOG("日志与调试"),
    LOOK_OPPO("OPPO 原厂色彩"),
    LOOK_CAMERA("数码相机"),
    LOOK_FILM("胶片模拟"),
    LOOK_ADOBE("Adobe 色彩"),
    LOOK_TONEMAP("色调映射"),
    SOFT_LIGHT("光学柔光模拟"),
    OPTICAL_VIGNETTE("光学暗角模拟"),
    KEY_MODE("启动模式"),
    WATERMARK_MODES("独立保存水印"),
    MODEL_PRESETS("机型预设"),
}

private val MAIN_TABS = listOf(
    OmniTabItem("状态", StatusTabOutlined, StatusTabFilled),
    OmniTabItem("色彩引擎", ColorEngineTabOutlined, ColorEngineTabFilled),
    OmniTabItem("滤镜", FilterTabOutlined, FilterTabFilled),
    OmniTabItem("其他模式", ModesTabOutlined, ModesTabFilled),
    OmniTabItem("交互", InteractionTabOutlined, InteractionTabFilled),
)

private val TAB_TOP_TITLES = listOf(
    "状态",
    "大师色彩引擎",
    "滤镜与光学",
    "其他模式",
    "交互体验",
)

private val HasselbladOrange = Color(0xFFF26B21)
private val OmniLightColorScheme = CouiPresets.Light.copy(
    accent = HasselbladOrange,
    onAccent = Color.White,
    accentOnSurface = Color(0xFFE05A10),
    checkboxAccent = HasselbladOrange,
    menuCheckboxAccent = Color(0xFFE05A10),
)
private val OmniDarkColorScheme = CouiPresets.Dark.copy(
    accent = HasselbladOrange,
    onAccent = Color.White,
    accentOnSurface = Color(0xFFFF7A2F),
    checkboxAccent = HasselbladOrange,
    menuCheckboxAccent = Color(0xFFFF7A2F),
)

private val NoOpNestedScroll = object : NestedScrollConnection {}

abstract class OmniHostActivity : ComponentActivity() {
    @Composable
    internal abstract fun Page(dark: Boolean, store: SettingsStore)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val store = SettingsStore(this)
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            CouiTheme(
                darkTheme = dark,
                colorScheme = if (dark) OmniDarkColorScheme else OmniLightColorScheme,
            ) {
                ProvideCouiMenuBackdrop(isolateContent = false) { windowSource ->
                    ProvideCouiChoiceMenuHost {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().then(windowSource)) {
                                Page(dark, store)
                            }
                            CouiChoiceMenuOverlay(backdrop = rememberCouiWindowBackdrop())
                        }
                    }
                }
            }
        }
    }
}

class SettingsActivity : OmniHostActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        preloadHomeDiagnostics(applicationContext)
        super.onCreate(savedInstanceState)
    }

    @Composable
    internal override fun Page(dark: Boolean, store: SettingsStore) {
        SettingsRoot(
            dark = dark,
            store = store,
            openSubPage = { subPage -> SecondarySettingsActivity.open(this, subPage) },
        )
    }
}

class SecondarySettingsActivity : OmniHostActivity() {
    @Composable
    internal override fun Page(dark: Boolean, store: SettingsStore) {
        val subPage = intent.getStringExtra(EXTRA_SUB_PAGE)
            ?.let { runCatching { SubPage.valueOf(it) }.getOrNull() }
            ?: SubPage.LOG
        SecondaryRoot(
            dark = dark,
            subPage = subPage,
            store = store,
            onBack = { finish() },
        )
    }

    companion object {
        private const val EXTRA_SUB_PAGE = "local.jiege.ui.SUB_PAGE"

        internal fun open(context: Context, page: SubPage) {
            val intent = Intent(context, SecondarySettingsActivity::class.java)
                .putExtra(EXTRA_SUB_PAGE, page.name)
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsRoot(
    dark: Boolean,
    store: SettingsStore,
    openSubPage: (SubPage) -> Unit,
) {
    val density = LocalDensity.current
    val scheme = CouiTheme.colorScheme
    var tab by rememberSaveable { mutableIntStateOf(TAB_STATUS) }
    var filterSegment by rememberSaveable { mutableIntStateOf(0) }
    var filterChipOrdinal by rememberSaveable { mutableIntStateOf(0) }
    val pageState = rememberSaveableStateHolder()
    val tabListStates = List(MAIN_TABS.size) { index ->
        key(index) { rememberLazyListState() }
    }
    val tabScrollBehaviors = List(MAIN_TABS.size) { index ->
        key(index) {
            CouiTopAppBarDefaults.exitUntilCollapsedScrollBehavior {
                tabListStates[index].canScrollForward || tabListStates[index].canScrollBackward
            }
        }
    }
    val scrollBehavior = tabScrollBehaviors[tab]
    val floatingRenderer = remember {
        if (Build.VERSION.SDK_INT >= 34) runCatching { CouiFloatingRenderer.standalone() }.getOrNull() else null
    }
    val filterActions = remember { FilterScreenActions() }
    val bodyBackdrop = rememberCouiBackdrop()
    val imeModifier = if (LocalWindowInfo.current.isWindowFocused) Modifier.imePadding() else Modifier

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .then(imeModifier)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = scheme.background,
        topBar = {
            LargeTopAppBar(
                title = { UiAppBarTitle(TAB_TOP_TITLES[tab]) },
                actions = {
                    if (tab == TAB_FILTER) {
                        FilterTopBarActions(filterActions)
                    }
                },
                scrollBehavior = scrollBehavior,
                dark = dark,
            )
        },
        bottomBar = {
            OmniFloatingBar(
                items = MAIN_TABS,
                selectedIndex = tab,
                onSelect = { next -> if (next != tab) tab = next },
                backdrop = bodyBackdrop,
                renderer = floatingRenderer,
            )
        },
    ) { innerPadding ->
        val contentPadding = remember(scrollBehavior.state, innerPadding, density) {
            scrollBehavior.state.contentPadding(innerPadding, density)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .couiBackdrop(bodyBackdrop)
                .background(scheme.background),
        ) {
            pageState.SaveableStateProvider(tab) {
                val overscroll = rememberCouiOverscrollEffect()
                when (tab) {
                    TAB_STATUS -> HomeScreen(
                        contentPadding = contentPadding,
                        nestedScroll = NoOpNestedScroll,
                        overscrollEffect = overscroll,
                        listState = tabListStates[tab],
                        onOpenLog = { openSubPage(SubPage.LOG) },
                    )
                    TAB_COLOR -> MasterLookScreen(
                        contentPadding = contentPadding,
                        nestedScroll = NoOpNestedScroll,
                        overscrollEffect = overscroll,
                        listState = tabListStates[tab],
                        store = store,
                        onOpenCategory = { cat ->
                            openSubPage(
                                when (cat) {
                                    LookCategory.OPPO -> SubPage.LOOK_OPPO
                                    LookCategory.CAMERA -> SubPage.LOOK_CAMERA
                                    LookCategory.FILM -> SubPage.LOOK_FILM
                                    LookCategory.ADOBE -> SubPage.LOOK_ADOBE
                                    LookCategory.TONEMAP -> SubPage.LOOK_TONEMAP
                                },
                            )
                        },
                    )
                    TAB_FILTER -> MasterFilterScreen(
                        contentPadding = contentPadding,
                        nestedScroll = NoOpNestedScroll,
                        overscrollEffect = overscroll,
                        listState = tabListStates[tab],
                        segment = filterSegment,
                        onSegmentChange = { filterSegment = it },
                        chipOrdinal = filterChipOrdinal,
                        onChipOrdinalChange = { filterChipOrdinal = it },
                        settings = store,
                        actions = filterActions,
                        onOpenSoftLight = { openSubPage(SubPage.SOFT_LIGHT) },
                        onOpenOpticalVignette = { openSubPage(SubPage.OPTICAL_VIGNETTE) },
                    )
                    TAB_MODES -> OtherModesScreen(
                        contentPadding = contentPadding,
                        nestedScroll = NoOpNestedScroll,
                        overscrollEffect = overscroll,
                        listState = tabListStates[tab],
                        store = store,
                    )
                    TAB_INTERACTION -> InteractionScreen(
                        contentPadding = contentPadding,
                        nestedScroll = NoOpNestedScroll,
                        overscrollEffect = overscroll,
                        listState = tabListStates[tab],
                        store = store,
                        onOpenKeyModes = { openSubPage(SubPage.KEY_MODE) },
                        onOpenWatermarkModes = { openSubPage(SubPage.WATERMARK_MODES) },
                        onOpenPresets = { openSubPage(SubPage.MODEL_PRESETS) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecondaryRoot(
    dark: Boolean,
    subPage: SubPage,
    store: SettingsStore,
    onBack: () -> Unit,
) {
    val density = LocalDensity.current
    val scheme = CouiTheme.colorScheme
    val listState = rememberLazyListState()
    val scrollBehavior = CouiTopAppBarDefaults.exitUntilCollapsedScrollBehavior {
        listState.canScrollForward || listState.canScrollBackward
    }
    val presetActions = remember { PresetScreenActions() }
    val context = LocalContext.current
    var cameraBrand by rememberSaveable { mutableIntStateOf(initialCameraBrand(context, store)) }
    // 有配方的引擎页（胶片、X100VI）才有底栏; 各引擎的配方与页签互不相干。
    val recipes = when (subPage) {
        SubPage.LOOK_FILM -> LookRecipes.FILM
        SubPage.LOOK_CAMERA -> LookRecipes.of(cameraBrand)
        else -> null
    }
    val recipeActions = remember(recipes) { RecipeActions() }
    var recipeTab by rememberSaveable(recipes?.title) { mutableIntStateOf(RECIPE_TAB_ADJUST) }
    val recipeBackdrop = rememberCouiBackdrop()
    val showRecipeList = recipes != null && recipeTab == RECIPE_TAB_LIST
    val imeModifier = if (LocalWindowInfo.current.isWindowFocused) Modifier.imePadding() else Modifier

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .then(imeModifier)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = scheme.background,
        bottomBar = {
            if (recipes != null) {
                RecipeFloatingBar(
                    tab = recipeTab,
                    onTabChange = { recipeTab = it },
                    backdrop = recipeBackdrop,
                    store = store,
                    recipes = recipes,
                    actions = recipeActions,
                )
            }
        },
        topBar = {
            LargeTopAppBar(
                title = { UiAppBarTitle(subPage.title) },
                navigationIcon = {
                    CouiTopIconAction(
                        onClick = onBack,
                        contentDescription = "返回",
                    ) {
                        UiIcon(imageVector = CouiIcons.Filled.Back, modifier = Modifier.size(20.dp))
                    }
                },
                actions = {
                    if (subPage == SubPage.MODEL_PRESETS) {
                        CouiTopIconAction(
                            onClick = { presetActions.addTrigger++ },
                            contentDescription = "新增预设",
                        ) {
                            UiIcon(imageVector = CouiIcons.Filled.Add, modifier = Modifier.size(20.dp))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                dark = dark,
            )
        },
    ) { innerPadding ->
        val overscroll = rememberCouiOverscrollEffect()
        val contentPadding = remember(scrollBehavior.state, innerPadding, density) {
            scrollBehavior.state.contentPadding(innerPadding, density)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .then(
                    if (recipes != null) {
                        Modifier.couiBackdrop(recipeBackdrop).background(scheme.background)
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (showRecipeList) RecipeListScreen(
                contentPadding = contentPadding,
                nestedScroll = NoOpNestedScroll,
                overscrollEffect = overscroll,
                listState = listState,
                store = store,
                recipes = recipes!!,
                actions = recipeActions,
            ) else when (subPage) {
                SubPage.LOG -> LogScreen(
                    contentPadding = contentPadding,
                    nestedScroll = NoOpNestedScroll,
                    overscrollEffect = overscroll,
                    listState = listState,
                )
                SubPage.LOOK_OPPO -> OppoLookScreen(
                    contentPadding = contentPadding,
                    nestedScroll = NoOpNestedScroll,
                    overscrollEffect = overscroll,
                    listState = listState,
                    store = store,
                )
                SubPage.LOOK_CAMERA -> CameraLookScreen(
                    contentPadding = contentPadding,
                    nestedScroll = NoOpNestedScroll,
                    overscrollEffect = overscroll,
                    listState = listState,
                    store = store,
                    tabBrand = cameraBrand,
                    onTabBrandChange = { cameraBrand = it },
                )
                SubPage.LOOK_FILM -> FilmLookScreen(
                    contentPadding = contentPadding,
                    nestedScroll = NoOpNestedScroll,
                    overscrollEffect = overscroll,
                    listState = listState,
                    store = store,
                )
                SubPage.LOOK_ADOBE -> AdobeLookScreen(
                    contentPadding = contentPadding,
                    nestedScroll = NoOpNestedScroll,
                    overscrollEffect = overscroll,
                    listState = listState,
                    store = store,
                )
                SubPage.LOOK_TONEMAP -> ToneMapLookScreen(
                    contentPadding = contentPadding,
                    nestedScroll = NoOpNestedScroll,
                    overscrollEffect = overscroll,
                    listState = listState,
                    store = store,
                )
                SubPage.SOFT_LIGHT -> SoftLightScreen(
                    contentPadding = contentPadding,
                    nestedScroll = NoOpNestedScroll,
                    overscrollEffect = overscroll,
                    store = store,
                    listState = listState,
                )
                SubPage.OPTICAL_VIGNETTE -> OpticalVignetteScreen(
                    contentPadding = contentPadding,
                    nestedScroll = NoOpNestedScroll,
                    overscrollEffect = overscroll,
                    store = store,
                    listState = listState,
                )
                SubPage.KEY_MODE -> KeyLaunchModeScreen(
                    contentPadding = contentPadding,
                    nestedScroll = NoOpNestedScroll,
                    overscrollEffect = overscroll,
                    store = store,
                    listState = listState,
                )
                SubPage.WATERMARK_MODES -> WatermarkModesScreen(
                    contentPadding = contentPadding,
                    nestedScroll = NoOpNestedScroll,
                    overscrollEffect = overscroll,
                    store = store,
                    listState = listState,
                )
                SubPage.MODEL_PRESETS -> ModelPresetScreen(
                    contentPadding = contentPadding,
                    nestedScroll = NoOpNestedScroll,
                    overscrollEffect = overscroll,
                    store = store,
                    actions = presetActions,
                    listState = listState,
                )
            }
        }
    }
}
