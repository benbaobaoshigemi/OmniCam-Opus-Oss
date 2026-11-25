package local.jiege.hook.preview;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.settings.ModuleSettings;

/**
 * The display's HDR/SDR ratio comes from the system's EDR brightness manager, which picks a policy
 * by the type bits of the layer's EDR flags. The camera marks the viewfinder HDR_EDR (type 8), which
 * hits CameraPreviewEdrStrategy and its cameraPreviewMaxNit cap; the gallery marks its photos
 * HDR_IMAGE (type 1) and gets the default policy. The viewfinder is flagged HDR_IMAGE as well, so
 * the same brightness gives the same ratio. The preview transform also runs every frame instead of
 * skipping frames whose ratio is close to the native one.
 */
public final class ViewfinderHdr {
    private static final int TYPE_MASK = 0xFFFF0000;
    private static final int TYPE_HDR_EDR = 8 << 16;
    private static final int TYPE_HDR_IMAGE = 1 << 16;

    private ViewfinderHdr() {}

    public static void installCamera() {
        if (!Settings.get(ModuleSettings.VIEWFINDER_HDR)) return;
        Log.guard("ViewfinderHdr", "HDR preview", () -> {
            Class<?> preview = Symbols.get().cls("HdrTransformPreview");
            Field tolerance = Symbols.get().field("HdrTransformPreview.tolerance");
            XposedBridge.hookAllConstructors(preview, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    XposedHelpers.setStaticFloatField(preview, tolerance.getName(), Float.NEGATIVE_INFINITY);
                }
            });
            XposedBridge.hookAllMethods(Class.forName("com.oplus.view.OplusEdrUtils"), "setEdrFlags", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    int flags = (Integer) param.args[2];
                    if ((flags & TYPE_MASK) == TYPE_HDR_EDR) param.args[2] = (flags & ~TYPE_MASK) | TYPE_HDR_IMAGE;
                }
            });
        });
    }
}
