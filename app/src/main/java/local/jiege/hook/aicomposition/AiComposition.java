package local.jiege.hook.aicomposition;

import android.app.Application;
import android.content.Context;
import android.hardware.HardwareBuffer;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.settings.ModuleSettings;

/**
 * AI 灵感构图 through the cloud.
 *
 * The camera's composition engine (OplusAICompositionHelper) needs vendor native code this device
 * lacks. The suggestion comes from the system AI unit's cloud ability instead (AiCompositionCloud)
 * and is fed through the camera's own streaming callback; tracking follows the attitude the camera
 * gives the tracker (AiCompositionAttitude). The camera's own feature list and config flags already
 * offer the composition entry; only the engine behind it is replaced.
 *
 * - The camera asks for one suggestion per composition round and tracks afterwards: asking per
 *   frame reads as a scene change and ends the round.
 * - The tracker's fourth value must be 3 or 6 (locked), otherwise the state machine reports a large
 *   change and the camera leaves composition.
 */
public final class AiComposition {
    private static final String TAG = "AiComposition";
    private static final String HELPER = "com.oplus.ocs.camera.OplusAICompositionHelper";

    private static volatile boolean delivered;
    /** The preview frame the composition processor is working on, for the cloud request. */
    private static final ThreadLocal<HardwareBuffer> FRAME = new ThreadLocal<>();
    /** Latest target pixel on the tracking frame. */
    private static volatile float[] last = {0f, 0f};

    private AiComposition() {}

    public static void installCamera(ClassLoader loader) {
        if (!Settings.get(ModuleSettings.AI_COMPOSITION_CLOUD)) return;
        AiCompositionCloud.setClassLoader(loader);
        Log.i(TAG, "cloud composition on");
        Class<?> helper;
        try {
            helper = Symbols.get().cls("AiCompositionHelper");
        } catch (Throwable error) {
            Log.e(TAG, "composition engine unavailable", error);
            return;
        }
        Log.guard(TAG, "frame source", AiComposition::installFrameSource);
        Log.guard(TAG, "streaming", () -> installStreaming(helper, loader));
        Log.guard(TAG, "tracker", () -> installTracker(helper));
        Log.guard(TAG, "reset", () -> installReset(helper));
    }

    /** The processor's 11 argument entry sees the RGBA preview frame the engine's buffer was made from. */
    private static void installFrameSource() {
        Method feed = Symbols.get().method("AiCompositionHelper.feed");
        XposedBridge.hookMethod(feed, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                // The second buffer is the RGBA preview frame; the first (RGB) copies out empty.
                Object frame = param.args.length > 1 ? param.args[1] : null;
                if (frame instanceof HardwareBuffer) FRAME.set((HardwareBuffer) frame);
            }
        });
    }

    /** Streaming composition: when the native engine does not run, the cloud's suggestion is delivered. */
    private static void installStreaming(Class<?> helper, ClassLoader loader) throws Exception {
        Class<?> callback = loader.loadClass(HELPER + "$CompositionStreamCallback");
        XposedHelpers.findAndHookMethod(helper, "runCompositionStreaming", HardwareBuffer.class, callback, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(param.getResult())) return;
                Object argument = param.args[0];
                Object listener = param.args[1];
                if (!(argument instanceof HardwareBuffer) || listener == null) return;
                if (delivered) {
                    param.setThrowable(null);
                    param.setResult(Boolean.TRUE);
                    return;
                }
                HardwareBuffer frame = FRAME.get();
                FRAME.remove();
                if (frame == null || frame.isClosed()) frame = (HardwareBuffer) argument;
                String json = AiCompositionCloud.suggest(context(), frame);
                if (json == null) return;
                try {
                    XposedHelpers.callMethod(listener, "onEvent", 1, json);
                    XposedHelpers.callMethod(listener, "onEvent", 2, json);
                    delivered = true;
                    Log.i(TAG, "suggestion delivered " + json);
                } catch (Throwable error) {
                    Log.e(TAG, "callback failed", error);
                }
                param.setThrowable(null);
                param.setResult(Boolean.TRUE);
            }
        });
    }

    /**
     * Tracker: the camera's own attitude drives the pixel position (AiCompositionAttitude) and the state is
     * locked. The target is where the camera put the anchor; without an attitude the last position holds.
     */
    private static void installTracker(Class<?> helper) {
        XposedHelpers.findAndHookMethod(helper, "initializeTracker", HardwareBuffer.class, int.class, int.class,
            float[].class, float[].class, float[].class, float.class, long.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    float[] x = (float[]) param.args[3];
                    float[] y = (float[]) param.args[4];
                    AiCompositionAttitude.anchor((float[]) param.args[5], x[0], y[0], (int) param.args[1], (int) param.args[2],
                        (float) param.args[6]);
                    last = new float[]{x[0], y[0]};
                    param.setThrowable(null);
                    param.setResult(Boolean.TRUE);
                }
            });
        XposedHelpers.findAndHookMethod(helper, "track", HardwareBuffer.class, int.class, int.class, float[].class,
            long.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    float[] at = AiCompositionAttitude.project((float[]) param.args[3], (int) param.args[1], (int) param.args[2]);
                    if (at != null) last = at;
                    param.setThrowable(null);
                    param.setResult(new float[]{last[0], last[1], 0.0f, 3.0f});
                }
            });
    }

    /** The camera resets composition: the next round asks again. */
    private static void installReset(Class<?> helper) {
        XposedHelpers.findAndHookMethod(helper, "reset", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                delivered = false;
            }
        });
    }

    private static Context context() {
        try {
            Application app = (Application) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            return app;
        } catch (Throwable error) {
            return null;
        }
    }
}
