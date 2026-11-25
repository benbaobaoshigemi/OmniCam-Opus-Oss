package local.jiege.hook.palette;

import dalvik.system.BaseDexClassLoader;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * Gallery process (com.coloros.gallery3d): palette editing in the photo editor ("色彩风格": style
 * list, palette panel, re-edit of the camera's filter + palette from the photo's filter.info).
 *
 * One gap on this device: feature_is_support_color_palette requires ro.product.first_api_level >= 37
 * (this device shipped with 36); every other term holds. The hook answers true for that one switch.
 * The system com.oplus.camera.unit.sdk.jar carries the palette mode and renders through the APS
 * texture entry. Which photos the editor treats as palette photos: GalleryPaletteScope.
 */
public final class GalleryColorPalette {
    private static final String TAG = "GalleryColorPalette";
    private static final String FEATURE = "feature_is_support_color_palette";
    private static final String SDK_JAR = "com.oplus.camera.unit.sdk.jar";

    private GalleryColorPalette() {}

    /** Before anything in the gallery touches the camera unit SDK. */
    public static void installGallery(ClassLoader classLoader) {
        if (!hookSdkLoader(classLoader)) return;
        try {
            XposedBridge.hookMethod(Symbols.get().method("Config.getBoolean"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (FEATURE.equals(param.args[0])) param.setResult(Boolean.TRUE);
                }
            });
            GalleryPaletteScope.install(classLoader);
            Log.i(TAG, "gallery palette editor enabled");
        } catch (Throwable error) {
            Log.e(TAG, "gallery palette switch hook failed", error);
        }
    }

    /**
     * The palette stage rebuilds HDR from the photo's hdr.transform.data (its HLG and sRGB curves) before it
     * applies the sliders. A master photo's data is the master HDR transform, which the camera already ran
     * on the src.image, so the stage would apply the master tone a second time. Without the data the engine
     * runs the palette sliders as an SDR edit of the base (is_hdr downgraded by the SDK); the gain map is
     * copied through by the gallery, so the photo stays UltraHDR.
     */
    private static void masterWithoutTransformData(ClassLoader sdkLoader) {
        Class<?> base = XposedHelpers.findClass("com.oplus.ocs.camera.ipusdk.processunit.BaseProcessUnitSDK", sdkLoader);
        Class<?> key = XposedHelpers.findClass("com.oplus.ocs.camera.ipusdk.processunit.RequestKey", sdkLoader);
        XposedHelpers.findAndHookMethod(base, "setProcessParameter", key, Object.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!GalleryPaletteScope.masterPhoto()) return;
                Object name = XposedHelpers.getObjectField(param.args[0], "mKeyName");
                if ("hdr_transform_data".equals(name)) param.args[1] = new byte[0];
            }
        });
    }

    /** The SDK's own loader (a system shared library): hooks go on its classes. */
    private static boolean hookSdkLoader(ClassLoader classLoader) {
        try {
            ClassLoader[] loaders = (ClassLoader[]) XposedHelpers.getObjectField(classLoader, "sharedLibraryLoaders");
            if (loaders == null) return false;
            for (ClassLoader loader : loaders) {
                if (loader instanceof BaseDexClassLoader && loader.toString().contains(SDK_JAR)) {
                    masterWithoutTransformData(loader);
                    GalleryFilters.install(loader);
                    return true;
                }
            }
            Log.i(TAG, "camera unit SDK loader not found");
        } catch (Throwable error) {
            Log.e(TAG, "camera unit SDK hooks failed", error);
        }
        return false;
    }
}
