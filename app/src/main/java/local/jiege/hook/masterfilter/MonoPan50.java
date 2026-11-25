package local.jiege.hook.masterfilter;

import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.filters.FilterScope;
import local.jiege.hook.masterlut.MasterLutSlots;
import local.jiege.hook.masterlut.ModuleEffects;

/**
 * MonoPan50 (Xiaomi M3 monochrome) in the master filter list. The filter's LUT is an identity table; the look is
 * the engine's: the grey conversion as a Post slot stage (master_lut_slots.cpp), shared by preview and capture, the
 * M3 tone, vignette and vendor grain on the frame (monopan_grain.cpp). The master 颗粒 item is hidden while the filter
 * is selected, and the master 暗角 switch drives the M3 vignette (gr_basictone.cpp) instead of the stock one. The slot
 * parameter follows the selection: on entering master mode, on a preset or filter change, and with each capture's
 * filter type.
 */
final class MonoPan50 {
    static final String ID = "JG_MONOPAN50.cube.rgb.bin";
    static final String PRO_ID = "JG_MONOPAN50PRO.cube.rgb.bin";
    private static final String TAG = "MonoPan50";
    private static final String SDK = "com.oplus.ocs.camera.";

    private MonoPan50() {}

    /** Whether a master filter type is one of the two MonoPan50 filters (Pro has the gallery's grain and tone). */
    static boolean owns(Object type) {
        return ID.equals(type) || PRO_ID.equals(type);
    }

    /** The slot parameter for a filter type: 1 MonoPan50, 2 MonoPan50 Pro, 0 another filter. */
    private static int variant(Object type) {
        return ID.equals(type) ? 1 : PRO_ID.equals(type) ? 2 : 0;
    }

    static void install(ClassLoader classLoader) {
        final Symbols symbols = Symbols.get();
        final Handler main = new Handler(Looper.getMainLooper());
        Log.guard(TAG, "entering master", () -> XposedBridge.hookMethod(symbols.method("Lut3d.load"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (FilterScope.MASTER_MODE.equals(param.args[2])) main.post(() -> follow(symbols));
            }
        }));
        // The selection is written before the presenter's filter index moves; read it once the change has settled.
        for (String write : new String[] {"DataManager.put", "DataManager.putSync"}) {
            Log.guard(TAG, "selection " + write, () -> XposedBridge.hookMethod(symbols.method(write), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (ModuleEffects.isPresetKey(param.args[0])) main.postDelayed(() -> follow(symbols), 120L);
                }
            }));
        }
        Log.guard(TAG, "capture", () -> XposedHelpers.findAndHookMethod(SDK + "producer.mode.BaseMode", classLoader,
            "setPositiveFilterGrain", SDK + "common.util.CameraRequestTag", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object tag = param.args[0];
                    Object type = tag == null ? null : XposedHelpers.getObjectField(tag, "mFilterType");
                    if (type instanceof String) MasterLutSlots.set(MasterLutSlots.Param.MONOPAN, variant(type));
                }
            }));
    }

    private static void follow(Symbols symbols) {
        try {
            MasterLutSlots.set(MasterLutSlots.Param.MONOPAN, variant(MasterGrain.currentType(symbols)));
            MasterGrain.refreshItem(symbols);
        } catch (Throwable error) {
            Log.e(TAG, "selection read failed", error);
        }
    }
}
