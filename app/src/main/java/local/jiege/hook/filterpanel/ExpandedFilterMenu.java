package local.jiege.hook.filterpanel;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Reflection;
import local.jiege.hook.common.Symbols;

/**
 * The camera's expanded-panel protocol for a filter menu shown in a mode without the beauty feature
 * (master mode, GR): a menu shown by the filter presenter alone sits on the screen as a stray view.
 *
 * The beauty menu is what hides the controls below the preview (FaceBeautyUIManager.t ends with
 * CameraUIManager.F9 -> CameraControlUI.Wg(true)) and what makes a tap on the preview close the menu
 * (TouchEventManager.d8 revokes the effect panel only while EffectPanelUIContainer.isExpanded(),
 * which only the beauty menu answers true; the filter menu neither answers it nor handles the
 * revoke). Without the beauty feature, expand() tells the effect panel that the menu is expanded
 * (EffectPanelUi.expand: mode bar and entry buttons hidden) and hides the controls after recording
 * which were visible. While expanded, the panel answers isExpanded() true and a revoke runs the
 * menu's hide. collapse() brings back the controls that were visible and collapses the panel.
 * Every menu opened this way is shown through expand() and ended through collapse() on each hide.
 */
public final class ExpandedFilterMenu {
    private static final String TAG = "ExpandedFilterMenu";
    private static final String CAMERA = "com.oplus.camera";
    /** Animation of the controls coming back, as CameraControlUI.Wg hides them. */
    private static final int CONTROLS_ANIMATION = 150;

    private static final Map<Object, WeakReference<Object>> effectPanels = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, WeakReference<Object>> controlUis = Collections.synchronizedMap(new WeakHashMap<>());
    private static boolean installed;
    /** Controls hidden by the expanded menu, and what was visible before (null: none hidden). */
    private static HiddenControls hiddenControls;
    /** The effect panel told that the menu is expanded, its key and the menu's hide, or none. */
    private static WeakReference<Object> expandedPanel = new WeakReference<>(null);
    private static String expandedKey;
    private static Runnable hideMenu;

    private ExpandedFilterMenu() {}

    /** Tracks the effect panel and control UI per activity and answers the panel's expanded state. Once. */
    public static synchronized void install(Symbols symbols) {
        if (installed) return;
        installed = true;
        trackByActivity(symbols.cls("EffectPanelUi"), effectPanels);
        trackByActivity(symbols.cls("ControlUi"), controlUis);
        XposedHelpers.findAndHookMethod(symbols.cls("EffectPanelBase"), "isExpanded", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (hideMenu != null && param.thisObject == expandedPanel.get()) param.setResult(Boolean.TRUE);
            }
        });
        XposedBridge.hookMethod(symbols.method("EffectPanelBase.revoke"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Runnable hide = hideMenu;
                if (hide == null || param.thisObject != expandedPanel.get()) return;
                try {
                    hide.run();
                } catch (Throwable error) {
                    Log.e(TAG, "menu revoke failed", error);
                }
            }
        });
    }

    public static Object effectPanel(Activity activity) {
        return of(effectPanels, activity);
    }

    /** True while a menu is expanded. */
    public static boolean expanded() {
        return hideMenu != null;
    }

    /** Expands the effect panel for {@code key} and hides the controls; {@code hide} hides the menu on a revoke. */
    public static void expand(Symbols symbols, Activity activity, String key, Runnable hide) throws Throwable {
        Object effectPanel = of(effectPanels, activity);
        if (effectPanel == null) {
            Log.i(TAG, "effect panel unknown, menu stays a stray view");
            return;
        }
        if (hideMenu != null) collapse(symbols);
        symbols.call("EffectPanelUi.expand", effectPanel, key);
        expandedPanel = new WeakReference<>(effectPanel);
        expandedKey = key;
        hideMenu = hide;
        hideControls(symbols, activity);
    }

    /** Ends the expansion: the controls that were visible come back and the panel collapses. */
    public static void collapse(Symbols symbols) {
        Object panel = expandedPanel.get();
        String key = expandedKey;
        expandedPanel = new WeakReference<>(null);
        expandedKey = null;
        hideMenu = null;
        if (panel == null) return;
        try {
            restoreControls(symbols);
            symbols.call("EffectPanelUi.collapse", panel, key, Boolean.TRUE, Boolean.TRUE);
        } catch (Throwable error) {
            Log.e(TAG, "effect panel collapse failed", error);
        }
    }

    /** What the menu hid, so that only the controls visible before come back. */
    private static final class HiddenControls {
        final WeakReference<Object> control;
        final boolean mainShutter, thumbnail, switchCamera, sideShutter, sideShutterEnabled;

        HiddenControls(Object control, Activity activity, View side) {
            this.control = new WeakReference<>(control);
            mainShutter = visible(activity, "shutter_button");
            thumbnail = visible(activity, "thumbnail");
            switchCamera = visible(activity, "switch_camera_button");
            sideShutter = side != null && side.getVisibility() == View.VISIBLE;
            sideShutterEnabled = side == null || side.isEnabled();
        }

        private static boolean visible(Activity activity, String name) {
            int id = activity.getResources().getIdentifier(name, "id", CAMERA);
            View view = id == 0 ? null : activity.findViewById(id);
            return view != null && view.getVisibility() == View.VISIBLE;
        }
    }

    /** The controls below the preview hidden as the beauty menu hides them (CameraControlUI.Wg(true)). */
    private static void hideControls(Symbols symbols, Activity activity) throws Throwable {
        Object control = of(controlUis, activity);
        if (control == null) {
            Log.i(TAG, "control UI unknown, controls stay");
            return;
        }
        hiddenControls = new HiddenControls(control, activity, (View) symbols.get("ControlUi.sideShutterView", control));
        symbols.call("ControlUi.hideControls", control, Boolean.TRUE);
    }

    private static void restoreControls(Symbols symbols) throws Throwable {
        HiddenControls hidden = hiddenControls;
        hiddenControls = null;
        Object control = hidden == null ? null : hidden.control.get();
        if (control == null) return;
        if (hidden.mainShutter) symbols.call("ControlUi.mainShutter", control, View.VISIBLE, CONTROLS_ANIMATION);
        if (hidden.thumbnail) symbols.call("ControlUi.thumbnail", control, View.VISIBLE, CONTROLS_ANIMATION);
        if (hidden.switchCamera) symbols.call("ControlUi.switchCamera", control, View.VISIBLE, CONTROLS_ANIMATION);
        if (hidden.sideShutter) symbols.call("ControlUi.sideShutter", control, View.VISIBLE);
        symbols.call("ControlUi.sideShutterEnabled", control, hidden.sideShutterEnabled);
    }

    private static void trackByActivity(Class<?> type, final Map<Object, WeakReference<Object>> instances) {
        XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                // The constructors get the application context or an object that holds the activity.
                for (Object arg : param.args) {
                    Activity activity = arg instanceof Context ? activityOf((Context) arg) : null;
                    if (activity == null && arg != null && !(arg instanceof Context)) {
                        try {
                            activity = (Activity) Reflection.findFieldByType(arg, "android.app.Activity");
                        } catch (Throwable ignored) {
                            // not a presenter
                        }
                    }
                    if (activity != null) {
                        instances.put(activity, new WeakReference<>(param.thisObject));
                        return;
                    }
                }
            }
        });
    }

    private static Object of(Map<Object, WeakReference<Object>> instances, Activity activity) {
        WeakReference<Object> reference = activity == null ? null : instances.get(activity);
        return reference == null ? null : reference.get();
    }

    static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
