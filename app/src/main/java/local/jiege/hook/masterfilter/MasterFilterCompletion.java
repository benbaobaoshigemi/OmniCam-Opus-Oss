package local.jiege.hook.masterfilter;

import local.jiege.hook.common.Log;

/**
 * Master-mode effect adapters and the camera's original preset lifecycle.
 */
public final class MasterFilterCompletion {
    private static final String TAG = "MasterFilter";

    private MasterFilterCompletion() {}

    public static void installCamera(ClassLoader classLoader) {
        try {
            MasterNeonGlow.install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "neon glow hooks failed", error);
        }
        try {
            MasterPortraHalation.install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "Portra halation hook failed", error);
        }
        try {
            MasterGrain.install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "grain hooks failed", error);
        }
        try {
            MonoPan50.install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "MonoPan50 hooks failed", error);
        }
        try {
            MasterPresetListSync.install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "preset list sync hook failed", error);
        }
        try {
            MasterProPresets.install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "professional preset hooks failed", error);
        }
    }
}
