package local.jiege.hook.softlight;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Reflection;
import local.jiege.hook.common.Symbols;

/**
 * The camera's own soft light (pull-down panel entry, soft light bar, preview and capture) for a mode the camera
 * does not list. Another mode's soft light lists serve the mode, and the filter presenter's capture request stage,
 * which opens soft light only for a fixed list of mode names, opens it for the mode once a soft light type is chosen.
 */
public final class StockSoftLight {
    private static final String TAG = "StockSoftLight";
    private static final String CONTAINER = "com.oplus.camera.common.view.BottomEffectContainer";
    private static final Set<String> answered = Collections.synchronizedSet(new HashSet<>());

    private StockSoftLight() {}

    /** The soft light lists (types and UI names) are empty for every mode but a few; {@code mode} gets the lists of {@code listsOf}. */
    public static void installLists(Symbols symbols, final String mode, final String listsOf, final BooleanSupplier enabled) {
        List<Method> lists = symbols.all("SoftLight.typesForMode");
        for (Method method : lists) {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (mode.equals(param.args[1]) && enabled.getAsBoolean()) param.args[1] = listsOf;
                }
            });
        }
        Log.i(TAG, "soft light lists installed for " + mode + ": " + lists.size());
    }

    /**
     * The soft light popup is the filter effect menu's bottom container; the camera shows it from showFilterPanel
     * and returns early for GR (GR has its own style panel). The container then sits on screen without having been
     * shown: its flag stays false, so the soft light bar swallows every touch, and the panel manager does not count
     * it as expanded, so a tap outside never revokes it. While the menu is in soft light mode, showFilterPanel
     * is called as {@code panelAs} (a mode without special cases) for {@code mode}.
     *
     * The camera's own hide and pause cleanup resets FilterMenu.isSoftLight and FilterPresenter.softLightMenu only
     * while palette mode is active, so when {@code mode}'s soft light container fades out or the mode switches away,
     * both flags are reset here.
     */
    @SuppressWarnings("unchecked")
    public static void installPanel(final Symbols symbols, final String mode, final String panelAs) {
        final boolean[] softLight = new boolean[1];
        final boolean[] opening = new boolean[1];
        final WeakReference<Object>[] openPresenter = new WeakReference[1];
        XposedBridge.hookMethod(symbols.method("FilterPresenter.openSoftLight"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!mode.equals(symbols.get("FeaturePresenter.modeName", param.thisObject))) return;
                opening[0] = true;
                openPresenter[0] = new WeakReference<>(param.thisObject);
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                opening[0] = false;
            }
        });
        XposedBridge.hookMethod(symbols.method("FilterMenu.setSoftLight"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                softLight[0] = (Boolean) param.args[0];
                if (!softLight[0]) openPresenter[0] = null;
            }
        });
        XposedBridge.hookMethod(symbols.method("FilterMenu.show"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (softLight[0] && mode.equals(param.args[0])) param.args[0] = panelAs;
            }
        });
        ClassLoader classLoader = symbols.cls("FilterMenu").getClassLoader();
        XposedHelpers.findAndHookMethod(CONTAINER, classLoader, "setVisibilityState",
            XposedHelpers.findClass(CONTAINER + "$VisibilityState", classLoader), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (opening[0] || openPresenter[0] == null) return;
                    String state = String.valueOf(param.args[0]);
                    if (!"FADING_OUT".equals(state) && !"HIDDEN".equals(state)) return;
                    Object presenter = openPresenter[0].get();
                    if (presenter == null) {
                        openPresenter[0] = null;
                        return;
                    }
                    try {
                        if (Reflection.findFieldByType(menuOf(symbols, presenter), CONTAINER) != param.thisObject) return;
                        clearSoftLightMenu(symbols, presenter, openPresenter);
                    } catch (Throwable error) {
                        Log.e(TAG, "soft light hide cleanup failed", error);
                    }
                }
            });
        XposedBridge.hookMethod(symbols.method("CaptureParams.onModeSwitch"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (openPresenter[0] == null) return;
                Object presenter = openPresenter[0].get();
                if (presenter == null) {
                    openPresenter[0] = null;
                    return;
                }
                try {
                    Object stage = param.args[0];
                    if (stage == null || (Integer) symbols.get("ModeSwitchStage.stage", stage) != 2) return;
                    if (mode.equals(symbols.get("ModeSwitchStage.toMode", stage))) return;
                    clearSoftLightMenu(symbols, presenter, openPresenter);
                } catch (Throwable error) {
                    Log.e(TAG, "soft light mode switch cleanup failed", error);
                }
            }
        });
        Log.i(TAG, "soft light panel installed for " + mode);
    }

    private static Object menuOf(Symbols symbols, Object presenter) throws Throwable {
        return symbols.call("FilterMenuHolder.menu", symbols.get("FilterPresenter.menus", presenter));
    }

    private static void clearSoftLightMenu(Symbols symbols, Object presenter, WeakReference<Object>[] openPresenter) throws Throwable {
        openPresenter[0] = null;
        Object menu = menuOf(symbols, presenter);
        if (menu != null) symbols.call("FilterMenu.setSoftLight", menu, Boolean.FALSE);
        symbols.set("FilterPresenter.softLightMenu", presenter, Boolean.FALSE);
    }

    /** After the capture request stage has run, soft light is opened for {@code mode} when a soft light type is chosen. */
    public static void installCapture(Symbols symbols, final String mode, final BooleanSupplier enabled) throws Throwable {
        final Method capture = symbols.method("FilterPresenter.captureRequest");
        final Method softLightIndex = symbols.method("FilterPresenter.softLightIndex");
        final Field modeName = symbols.field("FeaturePresenter.modeName");
        modeName.setAccessible(true);
        final Class<?> util = XposedHelpers.findClass("com.oplus.camera.filter.FilterUtil", capture.getDeclaringClass().getClassLoader());
        final Class<?> parameter = XposedHelpers.findClass("com.oplus.ocs.camera.CameraParameter", capture.getDeclaringClass().getClassLoader());
        final Object openKey = XposedHelpers.getStaticObjectField(parameter, "SOFT_LIGHT_OPEN");
        XposedBridge.hookMethod(capture, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.hasThrowable() || !mode.equals(modeName.get(param.thisObject)) || !enabled.getAsBoolean()) return;
                int index = (Integer) softLightIndex.invoke(param.thisObject);
                if (index == XposedHelpers.getStaticIntField(util, "sSoftLightNoneIndex")) return;
                symbols.call("StageRequest.put", symbols.get("Stage.builder", param.args[0]), openKey, Boolean.TRUE);
                if (answered.add("capture " + mode)) Log.i(TAG, "soft light opened for " + mode + "'s capture request, index=" + index);
            }
        });
        Log.i(TAG, "soft light capture installed for " + mode);
    }
}
