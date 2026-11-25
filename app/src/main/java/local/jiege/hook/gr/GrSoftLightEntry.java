package local.jiege.hook.gr;

import android.app.Activity;
import android.app.Application;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Reflection;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.filterpanel.ExpandedFilterMenu;

/**
 * GR port: the soft light icon in the top bar (shown while a soft light is on) opens the soft light bar.
 *
 * The icon's click writes "off" to the soft light seek bar key and relies on the face beauty feature's
 * listener on that key to open the bar and to carry it as an expanded panel; GR has no face beauty
 * feature, so nothing listens. The write is the signal here: in GR it opens the bar through the filter
 * presenter (as the dropdown entry does) and expands the effect panel for it (ExpandedFilterMenu), so
 * the controls below are hidden and a tap on the preview closes the bar. The expansion ends when the
 * bar's container hides.
 */
final class GrSoftLightEntry {
    private static final String TAG = "RicohGrPort";
    private static final String SEEK_BAR_KEY = "pref_soft_light_seek_bar";
    private static final String MENU_KEY = "pref_soft_light_menu_switch";
    private static final String CONTAINER = "com.oplus.camera.common.view.BottomEffectContainer";

    private static boolean installed;
    /** True while the bar was opened from the top icon (the expansion is ours to end). */
    private static boolean expanded;

    private GrSoftLightEntry() {}

    static void install(final Symbols symbols, final ClassLoader classLoader) {
        ExpandedFilterMenu.install(symbols);
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (installed) return;
                installed = true;
                try {
                    XC_MethodHook signal = new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam write) throws Throwable {
                            Object key = write.args[0];
                            if (key == null || !"off".equals(write.args[1]) || !GrState.isGrMode() || !Reflection.hasStringField(key, SEEK_BAR_KEY)) return;
                            open(symbols);
                        }
                    };
                    XposedBridge.hookMethod(symbols.method("DataManager.putSync"), signal);
                    XposedBridge.hookMethod(symbols.method("DataManager.put"), signal);
                    hookHide(symbols, classLoader);
                    Log.i(TAG, "GR soft light icon installed");
                } catch (Throwable error) {
                    Log.e(TAG, "GR soft light icon skipped", error);
                }
            }
        });
    }

    private static void open(final Symbols symbols) {
        try {
            Activity activity = (Activity) GrModeHooks.modeActivity(GrState.currentMode);
            if (activity == null) return;
            Object features = symbols.call("FeatureManager.of", null, activity);
            final Object presenter = features == null ? null : symbols.call("FeatureManager.filter", features);
            if (presenter == null) return;
            symbols.call("FilterPresenter.openSoftLight", presenter, Boolean.TRUE);
            ExpandedFilterMenu.expand(symbols, activity, MENU_KEY, () -> hideBar(symbols, presenter));
            expanded = ExpandedFilterMenu.expanded();
        } catch (Throwable error) {
            Log.e(TAG, "GR soft light bar not opened", error);
        }
    }

    /** A revoke (a tap on the preview) hides the bar as a tap above the menu does. */
    private static void hideBar(Symbols symbols, Object presenter) {
        try {
            symbols.call("FilterMenu.hideAnimated", menuOf(symbols, presenter), Boolean.TRUE);
        } catch (Throwable error) {
            Log.e(TAG, "GR soft light bar not hidden", error);
        }
    }

    /** Ends the expansion when the bar's container fades out, whoever hides it. */
    private static void hookHide(final Symbols symbols, ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(CONTAINER, classLoader, "setVisibilityState", XposedHelpers.findClass(CONTAINER + "$VisibilityState", classLoader),
            new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!expanded) return;
                    String state = String.valueOf(param.args[0]);
                    if (!"FADING_OUT".equals(state) && !"HIDDEN".equals(state)) return;
                    try {
                        Activity activity = (Activity) GrModeHooks.modeActivity(GrState.currentMode);
                        Object features = activity == null ? null : symbols.call("FeatureManager.of", null, activity);
                        Object presenter = features == null ? null : symbols.call("FeatureManager.filter", features);
                        if (presenter == null || Reflection.findFieldByType(menuOf(symbols, presenter), CONTAINER) != param.thisObject) return;
                        expanded = false;
                        ExpandedFilterMenu.collapse(symbols);
                    } catch (Throwable error) {
                        Log.e(TAG, "GR soft light bar close check failed", error);
                    }
                }
            });
    }

    private static Object menuOf(Symbols symbols, Object presenter) throws Throwable {
        return symbols.call("FilterMenuHolder.menu", symbols.get("FilterPresenter.menus", presenter));
    }
}
