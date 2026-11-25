package local.jiege.hook.gr;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.SoundPool;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.ModuleAssetFile;

/**
 * GR shutter sounds. The GR mode plays its own sounds (shutter, long exposure start / stop:
 * raw camera_gr_shutter, camera_gr_shutter_start, camera_gr_shutter_stop), but PMA110's camera
 * ships those files empty: they never finish loading and GR captures stay silent. Any SoundPool
 * load of one of them (the camera's pool and ShutterSound's shadow pool) reads the realme
 * camera's original sound from the module APK (assets/gr_sounds) instead, copied once to the
 * camera's cache. The sound setting, ringer mode and ShutterSound apply as for every sound.
 */
public final class GrShutterSound {
    private static final String TAG = "RicohGrPort";
    private static final String[] SOUNDS = {"camera_gr_shutter", "camera_gr_shutter_start", "camera_gr_shutter_stop"};

    private GrShutterSound() {}

    static void install(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(SoundPool.class, "load", Context.class, Integer.TYPE, Integer.TYPE, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Context context = (Context) param.args[0];
                int resource = (Integer) param.args[1];
                String name = grSound(context, resource);
                if (name == null) return;
                try {
                    File file = original(context, name);
                    param.setResult(((SoundPool) param.thisObject).load(file.getPath(), (Integer) param.args[2]));
                } catch (Throwable error) {
                    Log.e(TAG, "GR sound " + name + " unavailable", error);
                }
            }
        });
    }

    /** The GR sound this resource is, when the camera's copy of it is empty; else null. */
    private static String grSound(Context context, int resource) {
        for (String name : SOUNDS) {
            if (resource != context.getResources().getIdentifier(name, "raw", context.getPackageName())) continue;
            try (AssetFileDescriptor descriptor = context.getResources().openRawResourceFd(resource)) {
                return descriptor.getLength() > 0 ? null : name;
            } catch (Throwable error) {
                return name;
            }
        }
        return null;
    }

    public static File original(Context context, String name) throws Exception {
        return ModuleAssetFile.extract(context, "gr_sounds/" + name + ".ogg", "jiege_" + name + ".ogg");
    }
}
