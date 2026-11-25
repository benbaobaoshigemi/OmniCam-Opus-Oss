package local.jiege.hook.watermark;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;

/** Uses photo metadata when the gallery rebuilds a filter watermark. */
final class WatermarkFilterNames {
    private static final String TAG = "Watermark";
    private WatermarkFilterNames() {}

    /**
     * Rebuilding a master watermark names the filter by its index in the stock IPU list
     * (MasterModeInfo.getRealFilterName); module filters sit at other indices (ETERNA BB came back
     * as 人文). The name the camera stored with the photo (master.mode.preset.info) is used instead.
     */
    static void installGallery(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod("com.oplus.gallery.foundation.codec.extend.mastermode.MasterModeInfo", classLoader,
                "getRealFilterName", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Object stored = XposedHelpers.getObjectField(param.thisObject, "filterName");
                        if (stored instanceof String && !((String) stored).isEmpty()) param.setResult(stored);
                    }
                });
        } catch (Throwable error) {
            Log.e(TAG, "gallery master filter name hook failed", error);
        }
    }

}
