package local.jiege.hook.softlight;

import android.content.Context;
import android.media.ExifInterface;
import android.net.Uri;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.InputStream;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.common.ZoomFocal;
import local.jiege.hook.filters.FilterScope;
import local.jiege.hook.mastercolor.MasterColor;
import local.jiege.hook.mastercolor.MasterPhysicalVignette;
import local.jiege.settings.HncsStyle;
import local.jiege.settings.MasterStyle;
import local.jiege.settings.MasterStyles;
import local.jiege.settings.ModuleSettings;
import local.jiege.settings.X100viStyle;

/**
 * Soft light replacement: weak (柔美) renders as a Ricoh GR HDF, medium (朦胧) as a 1/4 black
 * mist, strong (梦幻) as a white mist, all scattered with physically simulated point spreads in the native half
 * (soft_light.cpp). The halo is an angle in front of the lens, so its size in the image follows
 * the focal length; this half reports the 35mm-equivalent focal length of the current zoom, and
 * which levels are replaced (experimental settings, read when the camera opens). Everything here
 * acts in 大师模式 and 理光模式 only (GR runs on the master pipeline and follows the same settings): the
 * filter presenter reports its mode whenever it applies a filter or builds a capture request, and every other
 * mode keeps the stock soft light and HDRTransform.
 */
public final class SoftLight {
    private static final String TAG = "SoftLight";
    private static final String GR_MODE = "gr";
    private static final String ZOOM_BAR = "com.oplus.camera.feature.zoom.view.ZoomSeekBar";

    private SoftLight() {}

    private static native void nativeSetFocal(float millimetres);
    private static native void nativeSetLevels(boolean weak, boolean medium, boolean strong);
    private static native void nativeSetNoLocalContrast(boolean on);
    private static native void nativeSetMasterMode(boolean master);
    private static native void nativeSetHncsGrain(boolean masterMode, boolean enabled, int type, int amount, int size, int roughness, int color);
    private static native void nativeSetX100viGrain(boolean masterMode, boolean enabled, int strength, int size, boolean isAcros);

    private static volatile String lastHncsGrainState;
    private static volatile String lastX100viGrainState;

    /** Syncs HNCS & X100VI Film Grain parameters to native; strictly isolated to real Master mode (never GR or gallery). */
    public static void syncHncsGrain(boolean realMaster) {
        if (!realMaster) {
            String key = "0";
            if (!key.equals(lastHncsGrainState)) {
                lastHncsGrainState = key;
                nativeSetHncsGrain(false, false, 1, 0, 40, 15, 0);
            }
            if (!key.equals(lastX100viGrainState)) {
                lastX100viGrainState = key;
                nativeSetX100viGrain(false, false, 0, 0, false);
            }
            return;
        }
        boolean blend = Settings.getInt(ModuleSettings.MASTER_LOOK_MODE) == MasterStyles.MODE_BLEND;
        MasterStyle style = MasterStyles.byIndex(Settings.getInt(ModuleSettings.MASTER_LOOK));
        if (blend && !style.blendable()) style = MasterStyles.DEFAULT;
        int tone = blend ? Settings.getInt(ModuleSettings.MASTER_BLEND_TONE) : 100;
        int colour = blend ? Settings.getInt(ModuleSettings.MASTER_BLEND_COLOUR) : 100;
        if (!HncsStyle.isGrainActive(style, blend, tone, colour, Settings::getInt)) {
            String key = "1:0";
            if (!key.equals(lastHncsGrainState)) {
                lastHncsGrainState = key;
                nativeSetHncsGrain(true, false, 1, 0, 40, 15, 0);
            }
        } else {
            int[] g = HncsStyle.resolveGrain(Settings::getInt);
            String key = "1:" + g[0] + ":" + g[1] + ":" + g[2] + ":" + g[3] + ":" + g[4] + ":" + g[5];
            if (!key.equals(lastHncsGrainState)) {
                lastHncsGrainState = key;
                nativeSetHncsGrain(true, g[0] != 0, g[1], g[2], g[3], g[4], g[5]);
            }
        }
        if (!X100viStyle.isGrainActive(style, blend, tone, colour, Settings::getInt)) {
            String key = "1:0";
            if (!key.equals(lastX100viGrainState)) {
                lastX100viGrainState = key;
                nativeSetX100viGrain(true, false, 0, 0, false);
            }
        } else {
            int[] xg = X100viStyle.resolveGrain(Settings::getInt);
            String key = "1:" + xg[0] + ":" + xg[1] + ":" + xg[2];
            if (!key.equals(lastX100viGrainState)) {
                lastX100viGrainState = key;
                nativeSetX100viGrain(true, true, xg[0], xg[1], xg[2] != 0);
            }
        }
    }

    /**
     * The photo editor re-renders a camera photo's soft light with the same native hooks. Called when the editor
     * opens a photo: only a master capture takes the replaced soft light (when the gallery switch is on), at the
     * focal length its EXIF records.
     */
    public static void galleryPhoto(Context context, Uri uri, boolean master) {
        Log.guard(TAG, "gallery soft light", () -> {
            boolean on = master && Settings.get(ModuleSettings.SOFT_LIGHT_GALLERY);
            nativeSetLevels(Settings.get(ModuleSettings.SOFT_LIGHT_WEAK_HDF),
                Settings.get(ModuleSettings.SOFT_LIGHT_MEDIUM_BLACK_MIST),
                Settings.get(ModuleSettings.SOFT_LIGHT_STRONG_WHITE_MIST));
            nativeSetFocal(on ? focalOf(context, uri) : 0f);
            nativeSetMasterMode(on);
            syncHncsGrain(false);
        });
    }

    /** The 35mm-equivalent focal length in the photo's EXIF; 0 (the native default) when it has none. */
    private static float focalOf(Context context, Uri uri) throws Exception {
        if (uri == null) return 0f;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            return in == null ? 0f : new ExifInterface(in).getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0);
        }
    }

    private static volatile float lastFocalMm = -1f;

    private static void reportZoom(Symbols symbols, float zoom) throws Throwable {
        if (!(zoom > 0f)) return;
        float focal = ZoomFocal.millimetres(symbols, zoom);
        if (Math.abs(focal - lastFocalMm) < 0.05f) return;
        lastFocalMm = focal;
        MasterPhysicalVignette.setFocalMm(focal);
        nativeSetFocal(focal);
    }

    public static void installCamera(ClassLoader classLoader) {
        final Symbols symbols = Symbols.get();
        final Class<?> zoomBarClass = XposedHelpers.findClass(ZOOM_BAR, classLoader);
        // A new zoom bar starts at 1x; later zoom changes report through setZoom / setZoomRate.
        Log.guard(TAG, "soft light focal length at 1x", () -> XposedBridge.hookAllConstructors(
            zoomBarClass, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    nativeSetLevels(Settings.get(ModuleSettings.SOFT_LIGHT_WEAK_HDF),
                        Settings.get(ModuleSettings.SOFT_LIGHT_MEDIUM_BLACK_MIST),
                        Settings.get(ModuleSettings.SOFT_LIGHT_STRONG_WHITE_MIST));
                    lastFocalMm = -1f;
                    reportZoom(symbols, 1.0f);
                    nativeSetNoLocalContrast(Settings.get(ModuleSettings.SOFT_LIGHT_NO_LOCAL_CONTRAST));
                    syncHncsGrain(MasterColor.inMaster());
                }
            }));
        for (String stage : new String[] {"FilterPresenter.apply", "FilterPresenter.captureRequest"}) {
            Log.guard(TAG, "soft light mode at " + stage, () -> XposedBridge.hookMethod(symbols.method(stage), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object mode = symbols.get("FeaturePresenter.modeName", param.thisObject);
                    nativeSetMasterMode(FilterScope.MASTER_MODE.equals(mode) || GR_MODE.equals(mode));
                    syncHncsGrain(FilterScope.MASTER_MODE.equals(mode));
                }
            }));
        }
        final XC_MethodHook onZoom = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                reportZoom(symbols, (Float) param.args[0]);
            }
        };
        Log.guard(TAG, "soft light focal length", () -> {
            XposedBridge.hookMethod(symbols.method("Zoom.setZoom"), onZoom);
            XposedHelpers.findAndHookMethod(zoomBarClass, "setZoomRate", Float.TYPE, onZoom);
            XposedHelpers.findAndHookMethod(zoomBarClass, "setCurrentDisplayText", Float.TYPE, onZoom);
        });
    }
}
