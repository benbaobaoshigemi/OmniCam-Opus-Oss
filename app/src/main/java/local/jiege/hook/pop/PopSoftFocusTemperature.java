package local.jiege.hook.pop;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * POP "去锐化" keeps the colour temperature and dual-LUT setup of two-LUT filters (和光 / 浓郁).
 *
 * The camera hands the filter processor a filter name; with 去锐化 on the name is "&lt;filter&gt;,RETRO_FILTER_SOFT".
 * 1) FilterIpuAdapter.drawFrame passes the live colour temperature only when the name equals one of the two-LUT pairs
 *    (portra400 / positive, "..._a_1.bin,..._d_1.bin"), otherwise 0. Here 0 is replaced by the live temperature.
 * 2) FilterProcessUnitSDK.drawPreview only skips the first comma inside a dual-LUT pair for "portra400_sdr_gen_a_1.bin,
 *    portra400_sdr_gen_d_1.bin". Other pairs (such as positive_* and *_hdr_*) are split at the comma between _a_1 and _d_1,
 *    losing the _d_1 LUT, skipping processDualLutFilterParam, keeping intensity at the soft-focus slider value and leaving
 *    soft focus off. Temporarily stripping ",RETRO_FILTER_SOFT" for drawPreview and applying soft focus + intensity 100
 *    before drawFrame makes every dual-LUT pair behave identically to portra400 SDR.
 */
final class PopSoftFocusTemperature {
    private static final String TAG = "PopPort";
    private static final String SOFT_SUFFIX = ",RETRO_FILTER_SOFT";
    private static final String PAIR_MARK = "_a_1.bin,";
    private static final String PORTRA_SDR_PAIR = "portra400_sdr_gen_a_1.bin,portra400_sdr_gen_d_1.bin";
    private static final String UNIT_SDK_CLASS = "com.oplus.ocs.camera.ipusdk.processunit.filter.FilterProcessUnitSDK";
    private static final String KEY_SDK_CLASS = "com.oplus.ocs.camera.ipusdk.processunit.filter.key.FilterProcessKeySDK";
    private static final String PARAM_SOFT_FOCUS_ENABLE = "Soft Focus Enable";
    private static final String PARAM_SOFT_FOCUS_THRESH = "Soft Focus Thresh";
    private static final byte[] EMPTY_FACE_DATA = new byte[0];
    private static final int ARG_FILTER = 6;
    private static final int ARG_RETRO_TYPE = 9;
    private static final int ARG_SUPPORT_GAIN = 10;
    private static final int ARG_CAPTURE_MODE = 15;
    private static final int ARG_RETRO_INTENSITY = 22;
    private static final int ARG_TEMPERATURE = 23;

    private static final ThreadLocal<Float> temperature = new ThreadLocal<>();
    private static final ThreadLocal<String> filterName = new ThreadLocal<>();
    private static final ThreadLocal<PendingSdkSoftFocus> pendingSdk = new ThreadLocal<>();
    private static volatile String lastDraw = "";
    private static volatile String lastFix = "";
    private static volatile String lastSdkFix = "";

    private static final class PendingSdkSoftFocus {
        final Object unitSdk;
        final String originalFilterType;
        final String pair;
        final int softIntensity;

        PendingSdkSoftFocus(Object unitSdk, String originalFilterType, String pair, int softIntensity) {
            this.unitSdk = unitSdk;
            this.originalFilterType = originalFilterType;
            this.pair = pair;
            this.softIntensity = softIntensity;
        }
    }

    private PopSoftFocusTemperature() {}

    private static String stripSoftSuffixIfDualLut(String name) {
        if (name == null || !name.endsWith(SOFT_SUFFIX)) return null;
        String pair = name.substring(0, name.length() - SOFT_SUFFIX.length());
        return pair.contains(PAIR_MARK) ? pair : null;
    }

    static void install(ClassLoader classLoader) throws Throwable {
        Symbols symbols = Symbols.get();
        XposedBridge.hookMethod(symbols.method("FilterIpuAdapter.drawFrame"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object live = param.args[ARG_TEMPERATURE];
                if (live instanceof Float) temperature.set((Float) live);
                String state = param.args[ARG_FILTER] + " retro=" + param.args[ARG_RETRO_TYPE]
                        + " retroIntensity=" + param.args[ARG_RETRO_INTENSITY] + " temperature=" + live
                        + " gain=" + param.args[ARG_SUPPORT_GAIN] + " mode=" + param.args[ARG_CAPTURE_MODE];
                if (!state.equals(lastDraw)) {
                    lastDraw = state;
                    Log.i(TAG, "filter draw: " + state);
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                temperature.remove();
                filterName.remove();
            }
        });
        XposedBridge.hookMethod(symbols.method("FilterProcessParameter.setFilterName"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                filterName.set(param.args[0] instanceof String ? (String) param.args[0] : null);
            }
        });
        XposedBridge.hookMethod(symbols.method("FilterProcessParameter.setColorTemperature"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                String name = filterName.get();
                Float live = temperature.get();
                if (live == null || stripSoftSuffixIfDualLut(name) == null) return;
                if (!(param.args[0] instanceof Float) || (Float) param.args[0] != 0f) return;
                param.args[0] = live;
                String fix = name + " temperature 0 -> " + live;
                if (!fix.equals(lastFix)) {
                    lastFix = fix;
                    Log.i(TAG, "soft focus keeps temperature: " + fix);
                }
            }
        });
        installSdkDualLutSplitFix(classLoader);
        Log.i(TAG, "POP soft focus temperature hook installed");
    }

    private static void installSdkDualLutSplitFix(ClassLoader classLoader) {
        Class<?> sdkCls = XposedHelpers.findClassIfExists(UNIT_SDK_CLASS, classLoader);
        Class<?> keyCls = XposedHelpers.findClassIfExists(KEY_SDK_CLASS, classLoader);
        if (sdkCls == null || keyCls == null) {
            Log.i(TAG, "FilterProcessUnitSDK unavailable; skipping dual-LUT split fix");
            return;
        }
        final Object keyFilterType = XposedHelpers.getStaticObjectField(keyCls, "FILTER_TYPE");
        final Object keyIntensity = XposedHelpers.getStaticObjectField(keyCls, "INTENSITY");
        final Object keyFaceData = XposedHelpers.getStaticObjectField(keyCls, "DETECT_FACE_DATA");
        XposedHelpers.findAndHookMethod(sdkCls, "drawPreview", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object entities = XposedHelpers.getObjectField(param.thisObject, "mRequestEntities");
                if (entities == null) return;
                Object ftObj = XposedHelpers.callMethod(entities, "getProcessValue", keyFilterType);
                if (!(ftObj instanceof String)) return;
                String ft = (String) ftObj;
                String pair = stripSoftSuffixIfDualLut(ft);
                if (pair == null || PORTRA_SDR_PAIR.equals(pair)) return;
                Object intensityObj = XposedHelpers.callMethod(entities, "getProcessValue", keyIntensity);
                int softIntensity = intensityObj instanceof Integer ? (Integer) intensityObj : 1;
                pendingSdk.set(new PendingSdkSoftFocus(param.thisObject, ft, pair, softIntensity));
                XposedHelpers.callMethod(entities, "setProcessParameter", keyFilterType, pair);
                String msg = pair + " intensity=" + softIntensity;
                if (!msg.equals(lastSdkFix)) {
                    lastSdkFix = msg;
                    Log.i(TAG, "soft focus dual-LUT preview fix: " + msg);
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                PendingSdkSoftFocus pending = pendingSdk.get();
                pendingSdk.remove();
                if (pending == null || pending.unitSdk != param.thisObject) return;
                Object entities = XposedHelpers.getObjectField(param.thisObject, "mRequestEntities");
                if (entities != null) {
                    XposedHelpers.callMethod(entities, "setProcessParameter", keyFilterType, pending.originalFilterType);
                }
            }
        });
        XposedHelpers.findAndHookMethod(sdkCls, "drawFrame", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                PendingSdkSoftFocus pending = pendingSdk.get();
                if (pending == null || pending.unitSdk != param.thisObject) return;
                Object previewSdk = XposedHelpers.getObjectField(param.thisObject, "mPreviewSDK");
                if (previewSdk == null) return;
                Object entities = XposedHelpers.getObjectField(param.thisObject, "mRequestEntities");
                byte[] faceData = entities != null
                        ? (byte[]) XposedHelpers.callMethod(entities, "getProcessValue", keyFaceData)
                        : null;
                XposedHelpers.callMethod(previewSdk, "resetFilter");
                XposedHelpers.callMethod(previewSdk, "fastUpdateFilter", pending.pair);
                XposedHelpers.callMethod(previewSdk, "setEffectBooleanValue", PARAM_SOFT_FOCUS_ENABLE, Boolean.TRUE);
                XposedHelpers.callMethod(previewSdk, "setFaceData", faceData != null ? faceData : EMPTY_FACE_DATA);
                XposedHelpers.callMethod(previewSdk, "setEffectFloatValue", PARAM_SOFT_FOCUS_THRESH,
                        pending.softIntensity / 100.0f);
                XposedHelpers.callMethod(previewSdk, "setIntensity", 100);
            }
        });
    }
}
