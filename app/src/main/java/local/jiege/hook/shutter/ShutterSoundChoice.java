package local.jiege.hook.shutter;

import android.content.Context;
import android.media.SoundPool;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.ModuleAssetFile;
import local.jiege.hook.common.Settings;
import local.jiege.hook.gr.GrShutterSound;
import local.jiege.settings.ModuleSettings;

/**
 * Shutter sound style of the modes that use the camera's common shutter sounds (every mode but GR
 * and XPAN, which play their own). Any SoundPool load of a common shutter sound (the camera's pool
 * and ShutterSound's shadow pool) loads the chosen style's sound instead:
 * modern = the camera's own, retro = the module's asset, XPAN = the camera's XPAN shutter,
 * GR = the GR shutter (GrShutterSound).
 */
public final class ShutterSoundChoice {
    private static final String TAG = "ShutterSoundChoice";
    private static final Set<String> COMMON = new HashSet<>(Arrays.asList(
        "camera_shutter", "camera_shutter_v1", "camera_moon_shutter", "camera_moon_shutter_low_loudness"));
    private static final String XPAN = "camera_xpan_shutter_sound";
    private static final String GR = "camera_gr_shutter";

    private ShutterSoundChoice() {}

    public static void installCamera(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(SoundPool.class, "load", Context.class, Integer.TYPE, Integer.TYPE, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    int style = Settings.getInt(ModuleSettings.SHUTTER_SOUND);
                    if (style == ModuleSettings.SHUTTER_SOUND_MODERN) return;
                    Context context = (Context) param.args[0];
                    if (!COMMON.contains(entryName(context, (Integer) param.args[1]))) return;
                    SoundPool pool = (SoundPool) param.thisObject;
                    int priority = (Integer) param.args[2];
                    try {
                        if (style == ModuleSettings.SHUTTER_SOUND_RETRO) {
                            File file = ModuleAssetFile.extract(context, "shutter_sounds/hasselblad_click.ogg", "jiege_shutter_retro.ogg");
                            param.setResult(pool.load(file.getPath(), priority));
                        } else if (style == ModuleSettings.SHUTTER_SOUND_XPAN) {
                            int xpan = context.getResources().getIdentifier(XPAN, "raw", context.getPackageName());
                            if (xpan != 0) param.setResult(pool.load(context, xpan, priority));
                        } else if (style == ModuleSettings.SHUTTER_SOUND_GR) {
                            File file = GrShutterSound.original(context, GR);
                            param.setResult(pool.load(file.getPath(), priority));
                        }
                    } catch (Throwable error) {
                        Log.e(TAG, "shutter sound style " + style + " unavailable", error);
                    }
                }
            });
            Log.i(TAG, "installed");
        } catch (Throwable error) {
            Log.e(TAG, "shutter sound style hooks unavailable", error);
        }
    }

    private static String entryName(Context context, int resource) {
        try {
            return context.getResources().getResourceEntryName(resource);
        } catch (Throwable error) {
            return "";
        }
    }
}
