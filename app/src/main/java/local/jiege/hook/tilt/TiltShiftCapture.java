package local.jiege.hook.tilt;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import local.jiege.hook.common.Log;

/**
 * Tilt-shift mode: the saved photo never got the tilt-shift algorithm. The engine takes the capture
 * algorithm list from the preview decision (ApsProcessor.mApsAlgoFlags), which has no tilt-shift node;
 * the list the app builds itself (getAlgoFromApp, which does contain aps_algo_tilt_shift) is only used
 * when there is no decision result. When the request carries a tilt-shift blur, the algorithm is added to
 * the list createMetaItemInfo produced, before the rotate/mirror node.
 *
 * Tilt-shift photos take the plain MFNR chain (tools/patch_tilt_mfnr.py), which asks for an NV12 capture
 * stream. The photo use case this device is configured for (sat_photo_none_yuv_case) has no capture_yuv
 * stream, and the HAL aborts when the request has no link for it. The models that ship tilt-shift use
 * sat_photo_case, so the tilt-shift mode gets that one back.
 */
public final class TiltShiftCapture {
    private static final String TAG = "TiltShift";
    private static final String SDK = "com.oplus.ocs.camera.";
    private static final String TILT_SHIFT = "aps_algo_tilt_shift";
    private static final String ROTATE_MIRROR = "aps_algo_rotate_mirror";

    private TiltShiftCapture() {}

    public static void installCamera(ClassLoader classLoader) {
        Log.guard(TAG, "capture algorithm", () -> hookCaptureList(classLoader));
        Log.guard(TAG, "use case", () -> hookUseCase(classLoader));
    }

    private static void hookUseCase(ClassLoader classLoader) {
        final Class<?> tiltShift = XposedHelpers.findClass(SDK + "producer.mode.TiltShiftPhotoMode", classLoader);
        final Class<?> photoMode = XposedHelpers.findClass(SDK + "producer.mode.PhotoMode", classLoader);
        XposedHelpers.findAndHookMethod(photoMode, "getSurfaceUseCase", String.class, Boolean.TYPE, Float.class,
            new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    // The method is declared by PhotoMode; only the tilt-shift mode changes.
                    if (param.hasThrowable() || !tiltShift.isInstance(param.thisObject)) return;
                    String useCase = (String) param.getResult();
                    String withYuv = "sat_photo_none_yuv_case".equals(useCase) ? "sat_photo_case"
                        : "sat_photo_none_yuv_full_size_case".equals(useCase) ? "sat_photo_full_size_case" : null;
                    if (withYuv == null) return;
                    param.setResult(withYuv);
                    Log.i(TAG, "use case " + useCase + " -> " + withYuv);
                }
            });
    }

    private static void hookCaptureList(ClassLoader classLoader) {
        Class<?> processor = XposedHelpers.findClass(SDK + "consumer.ApsProcessor", classLoader);
        Class<?> tagClass = XposedHelpers.findClass(SDK + "common.util.CameraRequestTag", classLoader);
        final Object algorithmKey = XposedHelpers.getStaticObjectField(
            XposedHelpers.findClass(SDK + "common.util.ParameterKeys", classLoader), "KEY_CAPTURE_ALGO_LIST");
        XposedHelpers.findAndHookMethod(processor, "createMetaItemInfo", tagClass, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) return;
                try {
                    Object tag = param.args[0];
                    if (!XposedHelpers.getBooleanField(tag, "mbTiltShiftOpen")
                            || XposedHelpers.getIntField(tag, "mTiltShiftBlurValue") <= 0) return;
                    Object meta = param.getResult();
                    String[] algorithms = (String[]) XposedHelpers.callMethod(meta, "get", algorithmKey);
                    if (algorithms == null) return;
                    List<String> updated = new ArrayList<>(Arrays.asList(algorithms));
                    if (updated.contains(TILT_SHIFT)) return;
                    int insertion = updated.indexOf(ROTATE_MIRROR);
                    updated.add(insertion < 0 ? updated.size() : insertion, TILT_SHIFT);
                    XposedHelpers.callMethod(meta, "setParameter", algorithmKey, (Object) updated.toArray(new String[0]));
                    Log.i(TAG, "tilt-shift capture algorithm added " + updated);
                } catch (Throwable t) { Log.e(TAG, "capture algorithm update failed", t); }
            }
        });
    }
}
