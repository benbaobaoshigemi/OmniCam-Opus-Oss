package local.jiege.hook.modestate;

import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import local.jiege.hook.common.DataStore;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.ProParamStore;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.filters.ProParamsRecord;

/**
 * Keeps the Pro gear parameters (white balance, colour tone, ISO, exposure compensation, shutter, focus, with their
 * manual flags) apart for master and GR. Both modes run the same Pro implementation and read one set of stored
 * parameters; here each mode has its own. On a mode switch the parameters of the mode left are saved as one text
 * under a per-mode key and the entered mode's saved parameters are put back; a mode without a record starts on Auto.
 * The Auto/Pro gear itself is kept apart by ModeStateSplit. After the switch's preview setup the parameter bar is
 * refreshed to show the entered mode's values.
 */
public final class ProParamsSplit {
    private static final String TAG = "ProParamsSplit";
    private static final String RECORD_PREFIX = "pref_jiege_pro_params_mode_";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Set when a switch has put another mode's parameters in place and the bar still shows the old ones. */
    private static volatile boolean pending;

    private ProParamsSplit() {}

    public static void installCamera(ClassLoader classLoader) {
        try {
            install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "pro parameter split unavailable", error);
        }
    }

    private static void install(final ClassLoader classLoader) {
        final Symbols symbols = Symbols.get();
        symbols.method("DataKeys.prefs");
        XposedBridge.hookMethod(symbols.method("ModeSwitch.switch"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                String from = (String) param.args[0];
                String to = (String) param.args[1];
                if (from == null || to == null || from.equals(to)) return;
                try {
                    if (isPro(from)) {
                        DataStore.put(symbols, classLoader, recordKey(symbols, from), ProParamsRecord.write(ProParamStore.snapshot(symbols, classLoader)));
                    }
                    if (isPro(to)) {
                        String text = (String) DataStore.get(symbols, classLoader, recordKey(symbols, to), "");
                        ProParamStore.set(symbols, classLoader, text.isEmpty() ? null : ProParamsRecord.parse(text));
                        pending = true;
                        Log.i(TAG, from + " -> " + to + (text.isEmpty() ? ": Auto (first visit)" : ": restored"));
                    }
                } catch (Throwable error) {
                    Log.e(TAG, "swap failed " + from + " -> " + to, error);
                }
            }
        });
        XposedBridge.hookMethod(symbols.method("CaptureParams.beforePreview"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(final MethodHookParam param) {
                if (!pending) return;
                try {
                    if (!isPro((String) symbols.get("FeaturePresenter.modeName", param.thisObject))) return;
                    pending = false;
                    MAIN.post(() -> {
                        try {
                            ProParamStore.refreshPanel(symbols, param.thisObject);
                        } catch (Throwable error) {
                            Log.e(TAG, "parameter bar refresh failed", error);
                        }
                    });
                } catch (Throwable error) {
                    Log.e(TAG, "parameter bar refresh failed", error);
                }
            }
        });
        Log.i(TAG, "installed");
    }

    private static boolean isPro(String mode) {
        return "professional".equals(mode) || "gr".equals(mode);
    }

    private static Object recordKey(Symbols symbols, String mode) throws Throwable {
        return symbols.call("DataKeys.prefs", null, String.class, RECORD_PREFIX + mode);
    }
}
