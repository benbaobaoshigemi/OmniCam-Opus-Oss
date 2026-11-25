package local.jiege.hook.video;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * OpenGate records at 24/30fps only. The X10 app treats video_size_open_gate like 1080p when it
 * decides whether 60fps is offered (VideoMode.isSupport60Fps, which the pro-video mode extends),
 * but the full-frame sensor modes run at 30fps. The feature graph already maps
 * fps 60/120 to 30 when OpenGate is chosen; this removes 60 from the fps menu itself.
 */
public final class OpenGateFps {
    private static final String TAG = "OpenGateFps";
    private static final String OPEN_GATE = "video_size_open_gate";

    private OpenGateFps() {}

    public static void installCamera(ClassLoader classLoader) {
        Log.guard(TAG, "OpenGate fps limit", () ->
            XposedBridge.hookMethod(Symbols.get().method("VideoMode.isSupport60Fps"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (OPEN_GATE.equals(param.args[0])) param.setResult(false);
                }
            }));
    }
}
