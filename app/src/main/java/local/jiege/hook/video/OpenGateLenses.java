package local.jiege.hook.video;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import local.jiege.hook.common.DataStore;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * OpenGate offers the ultra wide, main and 3x lenses. In OpenGate the pro-video mode runs
 * physical single-camera sessions, and its zoom configuration (MasterVideoMode.zoomConfig) turns
 * on the 10x lens entry whenever the device has an ultra tele camera. With video_size_open_gate
 * chosen that entry (ZoomConfig.ultraTele) is turned off.
 */
public final class OpenGateLenses {
    private static final String TAG = "OpenGateLenses";
    private static final String OPEN_GATE = "video_size_open_gate";
    private static final String SIZE_KEY = "pref_master_video_size_key";

    private OpenGateLenses() {}

    public static void installCamera(ClassLoader classLoader) {
        Log.guard(TAG, "OpenGate lenses", () -> {
            Symbols symbols = Symbols.get();
            Field ultraTele = symbols.field("ZoomConfig.ultraTele");
            XposedBridge.hookMethod(symbols.method("MasterVideoMode.zoomConfig"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object config = param.getResult();
                    if (config == null || !ultraTele.getBoolean(config)) return;
                    Object size = DataStore.get(symbols, classLoader, DataStore.key(symbols, "Keys.masterVideoSize", SIZE_KEY), "");
                    if (OPEN_GATE.equals(size)) ultraTele.setBoolean(config, false);
                }
            });
        });
    }
}
