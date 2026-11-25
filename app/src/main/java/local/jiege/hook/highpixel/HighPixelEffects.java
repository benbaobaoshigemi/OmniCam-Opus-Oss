package local.jiege.hook.highpixel;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.softlight.StockSoftLight;
import local.jiege.settings.ModuleSettings;

/**
 * 哈苏超清 (high pixel mode): the photo mode's filter, palette and soft light.
 *
 * The mode is built without them. The merged configs give it what the backend needs (the filter and beauty
 * features in the feature graph, the palette capture node in the algorithm switch config; capture already has a
 * filter node). What is left are the places in the app that decide by mode name, hooked here so the mode is
 * treated like photo mode, all behind the HIGH_PIXEL_EFFECTS switch except the beauty model (the graph adds its
 * feature unconditionally):
 * - the mode reports the filter menu, filter process and palette as supported;
 * - the hard-coded "modes that use the normal filter" list and the soft light lists include it;
 * - the right-hand enter button (BaseMode.Li) is shown, and its beauty UI manager can look the mode up;
 * - the viewfinder palette LUT: gate (Lut3dDataManager), texture engine flag and LUT texture request;
 * - soft light is opened in the capture request (the presenter only does it for a few mode names).
 */
public final class HighPixelEffects {
    private static final String TAG = "HighPixelEffects";
    private static final String MODE = "highPixel";
    private static final String FILTER_PROCESS = "pref_filter_process_key";
    private static final String FILTER_MENU = "pref_filter_menu";
    private static final String PALETTE = "key_support_palette";

    private static final Set<String> answered = Collections.synchronizedSet(new HashSet<>());

    private HighPixelEffects() {}

    public static void installCamera(ClassLoader classLoader) {
        final Symbols symbols = Symbols.get();
        Log.guard(TAG, "mode entries", () -> hookEntries(symbols.cls("HighPixelMode"), symbols.method("Lut3dData.paletteMode")));
        Log.guard(TAG, "preview palette", () -> hookPreviewPalette(symbols.method("Lut3dData.paletteMode")));
        Log.guard(TAG, "filter modes", () -> hookFilterModes(symbols.<Method>all("FilterModes.isFilterMode")));
        Log.guard(TAG, "preview palette engine", () -> hookPalettePreview(symbols.cls("PalettePreview"), symbols.field("PalettePreview.flag"),
            symbols.field("PalettePreview.request"), symbols.method("Lut3dData.paletteMode")));
        Log.guard(TAG, "preview lut texture", () -> hookLutTexture(symbols.method("Mode.needBasicToneLut"), symbols.cls("HighPixelMode")));
        Log.guard(TAG, "soft light types", () -> StockSoftLight.installLists(symbols, MODE, "common", () -> Settings.get(ModuleSettings.HIGH_PIXEL_EFFECTS)));
        Log.guard(TAG, "soft light capture", () -> StockSoftLight.installCapture(symbols, MODE, () -> Settings.get(ModuleSettings.HIGH_PIXEL_EFFECTS)));
        Log.guard(TAG, "beauty model", () -> hookBeautyModel(symbols.cls("BeautyModel")));
        Log.guard(TAG, "entry button", () -> hookEntryButton(symbols.method("Mode.effectEntry"), symbols.cls("HighPixelMode")));
        Log.guard(TAG, "request log", () -> HighPixelDiagnostics.install(classLoader));
    }

    /** The photo mode's answer to "palette supported" without the mode restriction (the config flag). */
    private static boolean paletteConfigured(Method paletteMode) throws Throwable {
        return (Boolean) XposedBridge.invokeOriginalMethod(paletteMode, null, new Object[] {"common"});
    }

    private static void hookEntries(Class<?> mode, final Method paletteMode) {
        XposedHelpers.findAndHookMethod(mode, "getSupportFunction", String.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                String key = (String) param.args[0];
                if (!FILTER_PROCESS.equals(key) && !FILTER_MENU.equals(key) && !PALETTE.equals(key)) return;
                if (!Settings.get(ModuleSettings.HIGH_PIXEL_EFFECTS)) return;
                Boolean answer = PALETTE.equals(key) ? paletteConfigured(paletteMode) : Boolean.TRUE;
                param.setResult(answer);
                if (answered.add(key + "=" + answer)) Log.i(TAG, "mode answers " + key + " = " + answer);
            }
        });
        Log.i(TAG, "mode entries installed");
    }

    /**
     * The viewfinder texture engine that applies the palette LUT only runs for a fixed list of modes (its flag is set in
     * createEngine); the engine already sizes its texture for 哈苏超清. Switch the flag on for the mode.
     */
    private static void hookPalettePreview(Class<?> engine, final java.lang.reflect.Field flag, final java.lang.reflect.Field request,
            final Method paletteMode) throws Throwable {
        flag.setAccessible(true);
        request.setAccessible(true);
        XposedBridge.hookAllMethods(engine, "createEngine", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object owner = request.get(param.thisObject);
                if (owner == null || !MODE.equals(XposedHelpers.callMethod(owner, "getCapModeName"))) return;
                if (!Settings.get(ModuleSettings.HIGH_PIXEL_EFFECTS) || !paletteConfigured(paletteMode)) return;
                if (!flag.getBoolean(param.thisObject)) {
                    flag.setBoolean(param.thisObject, true);
                    if (answered.add("previewEngine")) Log.i(TAG, "preview palette engine switched on for the mode");
                }
            }
        });
        Log.i(TAG, "preview palette engine installed");
    }

    /**
     * Whether the viewfinder needs the palette LUT texture: photo mode answers from the palette flag, but the 哈苏超清
     * class overrides it with false, so the palette never reached the preview (capture was fine). Where the mode class
     * declares its own override, it answers like the photo mode.
     */
    private static void hookLutTexture(Method base, Class<?> mode) {
        final Method own;
        try {
            own = mode.getDeclaredMethod(base.getName());
        } catch (NoSuchMethodException inherited) {
            Log.i(TAG, "preview lut texture: mode inherits " + base.getName() + ", nothing to do");
            return;
        }
        XposedBridge.hookMethod(own, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(param.getResult()) || !Settings.get(ModuleSettings.HIGH_PIXEL_EFFECTS)) return;
                if (Boolean.TRUE.equals(XposedHelpers.callMethod(param.thisObject, "getSupportFunction", PALETTE))) {
                    param.setResult(Boolean.TRUE);
                    if (answered.add("lutTexture")) Log.i(TAG, "preview lut texture enabled for the mode");
                }
            }
        });
        Log.i(TAG, "preview lut texture installed on " + own.getName());
    }

    /**
     * The face beauty model (its settings, keys and defaults) looks everything up by mode name and throws for a mode it
     * does not list. The feature graph gives 哈苏超清 the beauty feature (always, whatever the switch says), which uses photo
     * mode's settings, so every lookup with "highPixel" is answered as "common".
     */
    private static void hookBeautyModel(Class<?> model) {
        int hooked = 0;
        for (Method method : model.getDeclaredMethods()) {
            Class<?>[] types = method.getParameterTypes();
            boolean hasString = false;
            for (Class<?> type : types) hasString |= type == String.class;
            if (!hasString || method.isSynthetic()) continue;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    for (int i = 0; i < param.args.length; i++) {
                        if (MODE.equals(param.args[i])) param.args[i] = "common";
                    }
                }
            });
            hooked++;
        }
        if (hooked == 0) throw new IllegalStateException("beauty model: no methods");
        Log.i(TAG, "beauty model: " + hooked + " methods answer highPixel as common");
    }

    /**
     * Mode.effectEntry is the switch behind the right-hand enter button (the filter / palette entry in photo mode; a
     * wrapper returns it). The base implementation needs a device check that the photo mode answers from the device but
     * 哈苏超清 answers false, so the button was never shown. The button and its panel are the ones photo mode uses.
     */
    private static void hookEntryButton(Method entry, final Class<?> mode) {
        XposedBridge.hookMethod(entry, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!mode.isInstance(param.thisObject) || !Settings.get(ModuleSettings.HIGH_PIXEL_EFFECTS)) return;
                if (Boolean.TRUE.equals(param.getResult())) return;
                param.setResult(Boolean.TRUE);
                if (answered.add("entryButton")) Log.i(TAG, "right-hand entry button enabled for the mode");
            }
        });
        Log.i(TAG, "entry button installed");
    }

    /** The hard-coded list of modes that use the normal filter (and its palette): photo mode's, plus 哈苏超清. */
    private static void hookFilterModes(java.util.List<Method> candidates) {
        int hooked = 0;
        for (Method method : candidates) {
            // The statistics helper has the same mode strings; only the filter class's method is meant.
            if (method.getDeclaringClass().getName().startsWith("com.oplus.camera.statistics.")) continue;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (MODE.equals(param.args[0]) && Settings.get(ModuleSettings.HIGH_PIXEL_EFFECTS)) param.setResult(Boolean.TRUE);
                }
            });
            hooked++;
        }
        if (hooked != 1) throw new IllegalStateException("filter mode list: " + hooked + " candidates");
        Log.i(TAG, "filter modes installed");
    }

    private static void hookPreviewPalette(final Method paletteMode) {
        XposedBridge.hookMethod(paletteMode, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!MODE.equals(param.args[0]) || !Settings.get(ModuleSettings.HIGH_PIXEL_EFFECTS)) return;
                Boolean answer = paletteConfigured(paletteMode);
                param.setResult(answer);
                if (answered.add("previewPalette=" + answer)) Log.i(TAG, "preview palette gate for highPixel = " + answer);
            }
        });
        Log.i(TAG, "preview palette installed");
    }
}
