package local.jiege.hook.preview;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import local.jiege.hook.common.DataStore;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.settings.ModuleSettings;

/**
 * Focus peaking in the Pro gear with autofocus. The preview renderer asks the mode before every
 * frame whether to draw the peaking edges (ProMode.peakingDrawn); stock answers yes only when the
 * peaking switch is on, the Pro gear is active and the focus is manual. The edges come from a
 * Sobel pass over the preview, so autofocus needs nothing else: this drops the manual-focus term
 * while ModuleSettings.PRO_PEAKING_AF is on.
 */
public final class ProFocusPeaking {
    private static final String TAG = "ProFocusPeaking";
    private static final String PEAKING_MODE = "focusPeaking";
    private static final String PRO_AUTO_KEY = "key_master_mode_auto_option";

    private static final long SETTING_CHECK_MS = 500;

    private static volatile Object proAutoKey;
    private static volatile boolean enabled = true;
    private static volatile long checkedAt;

    private ProFocusPeaking() {}

    /** The setting, read at most twice a second: the renderer asks every frame. */
    private static boolean enabled() {
        long now = System.currentTimeMillis();
        if (now - checkedAt >= SETTING_CHECK_MS) {
            enabled = Settings.get(ModuleSettings.PRO_PEAKING_AF);
            checkedAt = now;
        }
        return enabled;
    }

    public static void installCamera(ClassLoader classLoader) {
        Log.guard(TAG, "peaking with autofocus", () -> {
            Symbols symbols = Symbols.get();
            XposedBridge.hookMethod(symbols.method("ProMode.peakingDrawn"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (Boolean.TRUE.equals(param.getResult()) || !enabled()) return;
                    if (!PEAKING_MODE.equals(symbols.call("PhotoMode.peakingMode", param.thisObject))) return;
                    if (proAutoKey == null) proAutoKey = DataStore.key(symbols, "Keys.proAuto", PRO_AUTO_KEY);
                    if (!Boolean.FALSE.equals(DataStore.get(symbols, classLoader, proAutoKey, Boolean.TRUE))) return;
                    param.setResult(true);
                }
            });
            Log.i(TAG, "installed");
        });
    }
}
