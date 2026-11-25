package local.jiege.hook.shutter;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.SystemClock;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.settings.ModuleSettings;

/**
 * Plays the camera sounds (shutter etc.) in silent and/or vibrate mode.
 *
 * The camera's sound player (SoundPlayer) keeps the ringer mode in an int field and plays only
 * when it is RINGER_MODE_NORMAL, on a SoundPool of STREAM_SYSTEM, which the ringer mode mutes.
 * SoundPlayer.refresh(int) writes that field from AudioManager.getRingerMode() (and the volume
 * into another int field); the ringer field is the one holding the current ringer mode after it.
 * (Regions with a forced shutter sound use STREAM_SYSTEM_ENFORCED instead; not this device.)
 *
 * When enabled for the current ringer mode:
 *   - D is reported as NORMAL, so the camera's own checks (including its shutter-sound
 *     setting) decide as in normal mode;
 *   - the camera pool's play/stop/volume calls go to a shadow pool on STREAM_SYSTEM_ENFORCED, which
 *     CameraSoundForced (system framework scope) takes out of ringer-mode muting at full volume.
 *     The shadow pool loads the same sound resources and files.
 *
 * The camera loads some sounds on demand (GR shutter, long exposure start) and plays them as soon
 * as its own pool finished loading; the shadow pool loads the same file at that moment and is
 * usually not done yet. Such a play waits for the shadow sample (up to PENDING_MS) under a
 * stand-in stream ID, which stop/volume calls follow to the real stream once it plays.
 *
 * With the own shutter volume on, play/setVolume volumes are scaled by the configured percentage:
 * on the camera's own pool (system stream) in normal ringer mode, on the shadow pool otherwise.
 */
public final class ShutterSound {
    private static final String TAG = "ShutterSound";
    /** AudioManager.STREAM_SYSTEM_ENFORCED (hidden in the SDK). */
    private static final int STREAM_SYSTEM_ENFORCED = 7;
    /** CameraManager handler message that silences the ringer until the camera asks SoundPlayer to restore it. */
    private static final int SILENCE_MESSAGE = 15;
    private static final long PENDING_MS = 1000;

    private static final Object lock = new Object();
    private static SoundPool shadowPool;
    /** camera pool sample ID -> shadow pool sample ID */
    private static final Map<Integer, Integer> samples = new HashMap<>();
    /** stream IDs that were played on the shadow pool */
    private static final Set<Integer> shadowStreams = new HashSet<>();
    /** shadow sample IDs that finished loading */
    private static final Set<Integer> loadedSamples = new HashSet<>();
    /** plays waiting for their shadow sample, by stand-in stream ID */
    private static final Map<Integer, PendingPlay> pendingPlays = new HashMap<>();
    /** stand-in stream ID -> shadow stream it became */
    private static final Map<Integer, Integer> standIns = new HashMap<>();
    private static int nextStandIn = -1;
    /** camera pool sample ID -> how to load it again (the shadow pool is rebuilt when the output kind changes) */
    private static final Map<Integer, Loader> sources = new HashMap<>();
    /** whether the shadow pool was built for headphones (media stream) or not (enforced stream) */
    private static boolean shadowHeadphones;
    private static volatile boolean redirecting;
    private static volatile boolean cameraSilenced;
    private static volatile Method playerInstance;
    private static volatile Field ringerField;

    private ShutterSound() {}

    public static void installCamera(ClassLoader classLoader) {
        try {
            install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "shutter sound hooks unavailable", error);
        }
    }

    private static void install(ClassLoader classLoader) throws NoSuchMethodException {
        final Symbols symbols = Symbols.get();
        final Method refresh = symbols.method("SoundPlayer.refresh");
        playerInstance = symbols.method("SoundPlayer.instance");
        final Class<?> playerClass = symbols.cls("SoundPlayer");

        // The camera silences the ringer on purpose (CameraManager message 15) until SoundPlayer.unsilence()
        // restores it; sounds stay off in that window, as in the stock camera. Builds that keep this in
        // SoundPlayer.silence() are hooked there; builds that inline it into the message handler, at message 15.
        if (symbols.has("SoundPlayer.silence")) {
            XposedBridge.hookMethod(symbols.method("SoundPlayer.silence"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    cameraSilenced = true;
                }
            });
        } else {
            XposedBridge.hookMethod(symbols.method("CameraManager.handler"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (((android.os.Message) param.args[0]).what == SILENCE_MESSAGE) cameraSilenced = true;
                }
            });
        }
        XposedBridge.hookMethod(symbols.method("SoundPlayer.unsilence"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                cameraSilenced = false;
            }
        });

        // SoundPlayer.refresh(int) refreshes volume and ringer mode.
        XposedBridge.hookMethod(refresh, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Field ringer = ringerField(playerClass, param.thisObject);
                if (ringer == null) return;
                int ringerMode = ringer.getInt(param.thisObject);
                boolean redirect = !cameraSilenced && wanted(ringerMode, headphones());
                synchronized (lock) {
                    if (!redirect) shadowStreams.clear();
                    redirecting = redirect;
                }
                if (redirect) ringer.setInt(param.thisObject, AudioManager.RINGER_MODE_NORMAL);
            }
        });

        // The ringer field is only rewritten by refresh(), which the camera calls on ringer changes,
        // so a settings change would wait for the next ringer toggle. When the camera comes to the
        // front, write the field to what the settings now decide.
        XposedBridge.hookMethod(XposedHelpers.findClass("com.oplus.camera.Camera", classLoader).getMethod("onResume"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Method getter = playerInstance;
                Object player = getter == null ? null : getter.invoke(null);
                if (player == null) return;
                AudioManager audio = (AudioManager) ((Context) param.thisObject).getSystemService(Context.AUDIO_SERVICE);
                int ringerMode = audio.getRingerMode();
                boolean want = !cameraSilenced && wanted(ringerMode, headphones());
                Field ringer = ringerField;
                if (ringer == null || want == redirecting) return;
                synchronized (lock) {
                    if (!want) shadowStreams.clear();
                    redirecting = want;
                }
                ringer.setInt(player, want ? AudioManager.RINGER_MODE_NORMAL : ringerMode);
            }
        });

        XposedHelpers.findAndHookMethod(SoundPool.class, "load", Context.class, Integer.TYPE, Integer.TYPE, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!isCameraPool(param.thisObject) || !(param.getResult() instanceof Integer)) return;
                synchronized (lock) {
                    final Context context = (Context) param.args[0];
                    final int resource = (Integer) param.args[1], priority = (Integer) param.args[2];
                    Loader loader = pool -> pool.load(context, resource, priority);
                    SoundPool shadow = shadowPool(headphones());
                    sources.put((Integer) param.getResult(), loader);
                    samples.put((Integer) param.getResult(), loader.load(shadow));
                }
            }
        });

        // Sounds the camera's APK ships empty are loaded from a file instead (GrShutterSound).
        XposedHelpers.findAndHookMethod(SoundPool.class, "load", String.class, Integer.TYPE, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!isCameraPool(param.thisObject) || !(param.getResult() instanceof Integer)) return;
                synchronized (lock) {
                    final String path = (String) param.args[0];
                    final int priority = (Integer) param.args[1];
                    Loader loader = pool -> pool.load(path, priority);
                    SoundPool shadow = shadowPool(headphones());
                    sources.put((Integer) param.getResult(), loader);
                    samples.put((Integer) param.getResult(), loader.load(shadow));
                }
            }
        });

        XposedHelpers.findAndHookMethod(SoundPool.class, "play", Integer.TYPE, Float.TYPE, Float.TYPE, Integer.TYPE,
            Integer.TYPE, Float.TYPE, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isCameraPool(param.thisObject)) return;
                    int ringerMode = ringerMode();
                    boolean headphones = headphones();
                    if (cameraSilenced || !wanted(ringerMode, headphones)) {
                        if (redirecting && ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                            // The setting turned off after the camera last refreshed its ringer mode.
                            param.setResult(0);
                            return;
                        }
                        float gain = volumeGain();
                        param.args[1] = gain * (Float) param.args[1];
                        param.args[2] = gain * (Float) param.args[2];
                        return;
                    }
                    synchronized (lock) {
                        shadowPool(headphones);
                        Integer shadowSample = samples.get((Integer) param.args[0]);
                        if (shadowSample == null) return;
                        // The camera's volume follows the (muted) system stream; on the shadow pool
                        // the loudness is the alarm volume times the own shutter volume only.
                        float gain = volumeGain();
                        PendingPlay play = new PendingPlay(shadowSample, gain, (Integer) param.args[3], (Integer) param.args[4], (Float) param.args[5]);
                        if (!loadedSamples.contains(shadowSample)) {
                            int standIn = nextStandIn--;
                            pendingPlays.put(standIn, play);
                            shadowStreams.add(standIn);
                            Log.i(TAG, "play sample=" + param.args[0] + " waits for the shadow load, stream=" + standIn);
                            param.setResult(standIn);
                            return;
                        }
                        int stream = play.start();
                        if (stream != 0) shadowStreams.add(stream);
                        Log.i(TAG, "play sample=" + param.args[0] + " cameraVolume=" + param.args[1] + " gain=" + gain + " stream=" + stream);
                        param.setResult(stream);
                    }
                }
            });

        XC_MethodHook streamControl = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!isCameraPool(param.thisObject)) return;
                synchronized (lock) {
                    int stream = (Integer) param.args[0];
                    if (shadowPool != null && pendingPlays.containsKey(stream)) {
                        // Not playing yet: a stop cancels it, the rest waits with it.
                        if ("stop".equals(param.method.getName())) {
                            pendingPlays.remove(stream);
                            shadowStreams.remove(stream);
                        }
                        param.setResult(null);
                        return;
                    }
                    Integer real = standIns.get(stream);
                    if (real != null) stream = real;
                    if (shadowPool == null || !shadowStreams.contains(stream)) {
                        if ("setVolume".equals(param.method.getName())) {
                            float gain = volumeGain();
                            param.args[1] = gain * (Float) param.args[1];
                            param.args[2] = gain * (Float) param.args[2];
                        }
                        return;
                    }
                    Object[] arguments = param.args.clone();
                    arguments[0] = stream;
                    if ("setVolume".equals(param.method.getName())) {
                        float gain = volumeGain();
                        arguments[1] = gain;
                        arguments[2] = gain;
                    }
                    Class<?>[] types = ((java.lang.reflect.Method) param.method).getParameterTypes();
                    XposedHelpers.callMethod(shadowPool, param.method.getName(), types, arguments);
                    if ("stop".equals(param.method.getName())) {
                        shadowStreams.remove(stream);
                        standIns.values().remove(stream);
                    }
                    param.setResult(null);
                }
            }
        };
        XposedHelpers.findAndHookMethod(SoundPool.class, "stop", Integer.TYPE, streamControl);
        XposedHelpers.findAndHookMethod(SoundPool.class, "pause", Integer.TYPE, streamControl);
        XposedHelpers.findAndHookMethod(SoundPool.class, "resume", Integer.TYPE, streamControl);
        XposedHelpers.findAndHookMethod(SoundPool.class, "setVolume", Integer.TYPE, Float.TYPE, Float.TYPE, streamControl);
        XposedHelpers.findAndHookMethod(SoundPool.class, "setLoop", Integer.TYPE, Integer.TYPE, streamControl);
        XposedHelpers.findAndHookMethod(SoundPool.class, "setRate", Integer.TYPE, Float.TYPE, streamControl);

        // The camera releases its pool when it pauses; release the shadow with it.
        XposedHelpers.findAndHookMethod(SoundPool.class, "release", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!isCameraPool(param.thisObject)) return;
                synchronized (lock) {
                    if (shadowPool != null) shadowPool.release();
                    shadowPool = null;
                    samples.clear();
                    sources.clear();
                    shadowStreams.clear();
                    loadedSamples.clear();
                    pendingPlays.clear();
                    standIns.clear();
                }
            }
        });
        Log.i(TAG, "installed");
    }

    private static boolean shouldPlay(int ringerMode) {
        if (ringerMode == AudioManager.RINGER_MODE_NORMAL || !Settings.get(ModuleSettings.SHUTTER_ALWAYS)) return false;
        if (ringerMode == AudioManager.RINGER_MODE_SILENT) return Settings.get(ModuleSettings.SHUTTER_IN_SILENT);
        if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) return Settings.get(ModuleSettings.SHUTTER_IN_VIBRATE);
        return false;
    }

    /**
     * Whether the sound goes to the shadow pool. With headphones connected every audible shutter
     * sound goes there (headphones only); without, only the sounds the settings add in silent/vibrate mode.
     */
    private static boolean wanted(int ringerMode, boolean headphones) {
        if (ringerMode == AudioManager.RINGER_MODE_NORMAL) return headphones;
        return shouldPlay(ringerMode);
    }

    private static AudioManager audioManager() {
        android.app.Application app = (android.app.Application) XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
        return app == null ? null : (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
    }

    private static int ringerMode() {
        AudioManager audio = audioManager();
        return audio == null ? AudioManager.RINGER_MODE_NORMAL : audio.getRingerMode();
    }

    /** Whether the shutter sound keeps to headphones: the setting is on and headphones (wired, USB, Bluetooth media, hearing aid) are connected as outputs. */
    private static boolean headphones() {
        if (!Settings.get(ModuleSettings.SHUTTER_KEEP_MEDIA)) return false;
        AudioManager audio = audioManager();
        if (audio == null) return false;
        for (AudioDeviceInfo device : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            switch (device.getType()) {
                case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                case AudioDeviceInfo.TYPE_USB_HEADSET:
                case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                case AudioDeviceInfo.TYPE_HEARING_AID:
                case AudioDeviceInfo.TYPE_BLE_HEADSET:
                    return true;
                default:
                    break;
            }
        }
        return false;
    }

    /** Scale for shadow-pool volumes: the own shutter volume, or 1 when it is off. */
    private static float volumeGain() {
        if (!Settings.get(ModuleSettings.SHUTTER_VOLUME_CUSTOM)) return 1f;
        return Math.max(0, Math.min(100, Settings.getInt(ModuleSettings.SHUTTER_VOLUME))) / 100f;
    }

    /**
     * The ringer field: of the player's int fields that refresh() writes, the one now holding the
     * ringer mode the system reports. Null (and not remembered) while that is ambiguous.
     */
    private static Field ringerField(Class<?> playerClass, Object player) throws Throwable {
        Field known = ringerField;
        if (known != null) return known;
        android.app.Application app = (android.app.Application) XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
        if (app == null) return null;
        int ringerMode = ((AudioManager) app.getSystemService(Context.AUDIO_SERVICE)).getRingerMode();
        Field match = null;
        for (Field field : Symbols.get().<Field>all("SoundPlayer.refreshedInts")) {
            if (field.getInt(player) != ringerMode) continue;
            if (match != null) return null;
            match = field;
        }
        if (match != null) {
            ringerField = match;
            Log.i(TAG, "ringer mode field " + match.getName());
        }
        return match;
    }

    /** The camera's sound pools are the SoundPool fields of the player singleton. */
    private static boolean isCameraPool(Object pool) {
        Method instanceGetter = playerInstance;
        if (instanceGetter == null || pool == null || pool == shadowPool) return false;
        try {
            Object instance = instanceGetter.invoke(null);
            if (instance == null) return false;
            for (Field field : Symbols.get().<Field>all("SoundPlayer.pools")) {
                if (field.get(instance) == pool) return true;
            }
            return false;
        } catch (Throwable error) {
            return false;
        }
    }

    /**
     * The shadow pool for the current output. Headphones: the media stream, which plays on the
     * headphones only and mixes with their music without muting it. Otherwise the enforced stream,
     * audible in silent/vibrate mode. A pool built for the other kind is rebuilt with the same samples.
     */
    private static SoundPool shadowPool(boolean headphones) {
        if (shadowPool != null && shadowHeadphones != headphones) {
            shadowPool.release();
            shadowPool = null;
            samples.clear();
            shadowStreams.clear();
            loadedSamples.clear();
            pendingPlays.clear();
            standIns.clear();
        }
        if (shadowPool == null) {
            AudioAttributes.Builder attributes = new AudioAttributes.Builder();
            if (headphones) attributes.setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION);
            else attributes.setLegacyStreamType(STREAM_SYSTEM_ENFORCED);
            shadowPool = new SoundPool.Builder().setMaxStreams(10).setAudioAttributes(attributes.build()).build();
            shadowHeadphones = headphones;
            shadowPool.setOnLoadCompleteListener(ShutterSound::shadowLoaded);
            for (Map.Entry<Integer, Loader> entry : sources.entrySet()) samples.put(entry.getKey(), entry.getValue().load(shadowPool));
        }
        return shadowPool;
    }

    private interface Loader {
        int load(SoundPool pool);
    }

    /** Starts the plays that waited for this sample, unless they waited too long. */
    private static void shadowLoaded(SoundPool pool, int sample, int status) {
        synchronized (lock) {
            if (pool != shadowPool || status != 0) return;
            loadedSamples.add(sample);
            for (Iterator<Map.Entry<Integer, PendingPlay>> it = pendingPlays.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Integer, PendingPlay> entry = it.next();
                PendingPlay play = entry.getValue();
                if (play.sample != sample) continue;
                it.remove();
                shadowStreams.remove(entry.getKey());
                if (SystemClock.uptimeMillis() - play.time > PENDING_MS) continue;
                int stream = play.start();
                if (stream == 0) continue;
                shadowStreams.add(stream);
                standIns.put(entry.getKey(), stream);
                Log.i(TAG, "played after the shadow load: stream " + entry.getKey() + " -> " + stream);
            }
        }
    }

    /** A camera play on the shadow pool. */
    private static final class PendingPlay {
        final int sample;
        final float gain;
        final int priority;
        final int loop;
        final float rate;
        final long time = SystemClock.uptimeMillis();

        PendingPlay(int sample, float gain, int priority, int loop, float rate) {
            this.sample = sample;
            this.gain = gain;
            this.priority = priority;
            this.loop = loop;
            this.rate = rate;
        }

        int start() {
            return shadowPool.play(sample, gain, gain, priority, loop, rate);
        }
    }
}
