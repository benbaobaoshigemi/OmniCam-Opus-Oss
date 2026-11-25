package local.jiege.hook.launch;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.DataStore;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.settings.ModuleSettings;

/**
 * Quick launch key mode: the key opens the camera in the chosen mode instead of the remembered one.
 *
 * The key starts the camera with android_camera_launch_type spruce_double_tap_launch_from_framework
 * or android_spruce_key_launch_type (and boolean android_spruce_key_launch_type), either through
 * CameraImageActivity on to Camera (unlocked) or directly into Camera / OplusCameraActivity on
 * lockscreen (STILL_IMAGE_CAMERA_SECURE). QuickActivity invokes CameraEntry before
 * super.onCreate(), so both Activity lifecycle hooks and CameraEntry.parseIntentExtraInfo must
 * tag the Intent with CHOSEN before CameraInfoCursor maps it to the chosen mode name.
 *
 * The camera remembers the mode it shows (pref_camera_mode_key) for its next start. A key launch
 * keeps the remembered mode: when that camera session stops still in the key's mode, the mode
 * remembered before the key launch is written back. A mode the user picked during the session stays.
 */
public final class KeyLaunchMode {
    private static final String TAG = "KeyLaunchMode";
    private static final int CHOSEN = 9000;
    private static final String CAMERA = "com.oplus.camera.Camera";
    private static final String CAMERA_IMAGE = "com.oplus.camera.component.CameraImageActivity";

    /** The key's mode and the mode remembered before the key launch, while a key session runs. */
    private static String sessionMode;
    private static Object remembered;
    /** pref_camera_mode_key; resolved at first use (resolving DataKeys while hooks install deadlocks the camera). */
    private static Object modeKey;

    private KeyLaunchMode() {}

    public static void installCamera(ClassLoader classLoader) {
        Log.guard(TAG, "quick launch key mode", () -> install(classLoader));
    }

    private static void install(ClassLoader classLoader) throws Throwable {
        Symbols symbols = Symbols.get();
        Class<?> cameraClass = XposedHelpers.findClass(CAMERA, classLoader);
        Class<?> imageClass = XposedHelpers.findClassIfExists(CAMERA_IMAGE, classLoader);

        XC_MethodHook mark = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object target = param.thisObject;
                if (!cameraClass.isInstance(target) && (imageClass == null || !imageClass.isInstance(target))) return;
                Intent intent = param.args[0] instanceof Intent ? (Intent) param.args[0] : ((Activity) target).getIntent();
                markLaunch(symbols, classLoader, intent);
            }
        };
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, mark);
        XposedHelpers.findAndHookMethod(Activity.class, "onNewIntent", Intent.class, mark);
        XposedBridge.hookMethod(symbols.method("CameraEntry.parseIntentExtraInfo"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                markLaunch(symbols, classLoader, (Intent) param.args[0]);
            }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "onStop", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (sessionMode == null || !cameraClass.isInstance(param.thisObject)) return;
                try {
                    if (sessionMode.equals(DataStore.get(symbols, classLoader, modeKey(symbols), ""))) DataStore.put(symbols, classLoader, modeKey(symbols), remembered);
                } catch (Throwable error) {
                    Log.e(TAG, "remembered mode not restored", error);
                }
                sessionMode = null;
                remembered = null;
            }
        });

        XposedBridge.hookMethod(symbols.method("CameraInfoCursor.modeName"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if ((Integer) param.args[0] != CHOSEN) return;
                String mode = Settings.getString(ModuleSettings.KEY_LAUNCH_MODE);
                Log.i(TAG, "quick launch key opens " + mode);
                param.setResult(mode);
            }
        });
        Log.i(TAG, "installed");
    }

    private static void markLaunch(Symbols symbols, ClassLoader classLoader, Intent intent) {
        if (intent == null || !fromKey(intent) || !Settings.get(ModuleSettings.KEY_LAUNCH_MODE_ON)) return;
        intent.putExtra("mode", CHOSEN);
        if (sessionMode != null) return;
        try {
            remembered = DataStore.get(symbols, classLoader, modeKey(symbols), "common");
            sessionMode = Settings.getString(ModuleSettings.KEY_LAUNCH_MODE);
        } catch (Throwable error) {
            Log.e(TAG, "remembered mode unreadable", error);
        }
    }

    private static Object modeKey(Symbols symbols) throws IllegalAccessException {
        if (modeKey == null) modeKey = DataStore.key(symbols, "Keys.cameraMode", "pref_camera_mode_key");
        return modeKey;
    }

    private static boolean fromKey(Intent intent) {
        if (intent.getBooleanExtra("android_spruce_key_launch_type", false)) return true;
        String launchType = intent.getStringExtra("android_camera_launch_type");
        return "spruce_double_tap_launch_from_framework".equals(launchType)
            || "android_spruce_key_launch_type".equals(launchType);
    }
}
