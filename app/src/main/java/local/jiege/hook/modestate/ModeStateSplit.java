package local.jiege.hook.modestate;

import android.text.TextUtils;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import local.jiege.hook.common.DataStore;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Reflection;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.settings.ModuleSettings;

/**
 * Keeps the flash state and the live photo state per mode, instead of one value shared by all
 * photo modes.
 *
 * The camera switches modes through ModeSwitch.switch(fromMode, toMode, caller) (the mode switcher
 * stage 1, and CameraEntry at launch). Stock already uses it to keep POP's live photo state apart
 * (swapRetroLivePhotoStateIfNeeded: DATA_KEY_LIVE_PHOTO <-> pref_live_photo_retro_camera_mode_key).
 * Before it runs, the shared value is saved under a per-mode key of the mode being left, and the
 * entered mode's saved value, if it has one, is put back. A mode entered for the first time keeps
 * the current value.
 *
 * The Pro gear of the master mode (key_master_mode_auto_option, Boolean: true = Auto) is shared by 大师 and
 * 理光 (both run ProMode); it is kept per mode the same way, always.
 *
 * Shared keys: flash pref_camera_flashmode_key (per camera id; its holder is the class whose
 * static initialiser defines it), live photo pref_live_photo_key (one of the keys the mode switch
 * itself reads). Per-mode keys are created with the same factories (DataKeys.cameraPrefs /
 * DataKeys.prefs), so they are stored in the same preference files. Both settings are Strings
 * ("on"/"off"/"auto"/"torch"). Live photo leaves POP to the stock swap.
 *
 * The keys are read on the first mode switch, not at install: touching the key holders while the
 * camera process is still starting runs their static initialisers too early and hangs the launch.
 */
public final class ModeStateSplit {
    private static final String TAG = "ModeStateSplit";
    private static final String RETRO_CAMERA = "retroCamera";

    /** The mode a switch is entering (the mode the camera shows once it returns), null before the first switch. */
    private static volatile String currentMode;

    private ModeStateSplit() {}

    /** One shared camera setting kept per mode. */
    private static final class State {
        final String name;
        final String setting;
        final String preference;
        Object sharedKey;
        final String factory;
        final String prefix;
        final boolean stockRetroSwap;
        final Class<?> valueType;
        final Map<String, Object> modeKeys = new HashMap<>();

        State(String name, String setting, String preference, String factory, String prefix, boolean stockRetroSwap, Class<?> valueType) {
            this.valueType = valueType;
            this.name = name;
            this.setting = setting;
            this.preference = preference;
            this.factory = factory;
            this.prefix = prefix;
            this.stockRetroSwap = stockRetroSwap;
        }
    }

    public static void installCamera(ClassLoader classLoader) {
        try {
            install(classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "mode state split unavailable", error);
        }
    }

    private static void install(final ClassLoader classLoader) {
        final Symbols symbols = Symbols.get();
        final State[] states = {
            // DataKeys.cameraPrefs: per-camera-id file, like the flash key itself.
            new State("flash", ModuleSettings.FLASH_PER_MODE, "pref_camera_flashmode_key", "DataKeys.cameraPrefs", "pref_camera_flashmode_key_jiege_", false, String.class),
            new State("live", ModuleSettings.LIVE_PHOTO_PER_MODE, "pref_live_photo_key", "DataKeys.prefs", "pref_live_photo_key_jiege_", true, String.class),
            // The DataKey is found by the preference name in the Keys.proAuto holders.
            new State("proGear", null, "key_master_mode_auto_option", "DataKeys.cameraPrefs", "key_master_mode_auto_option_jiege_", false, Boolean.class),
        };
        symbols.method("DataKeys.cameraPrefs");
        symbols.method("DataKeys.prefs");

        XposedBridge.hookMethod(symbols.method("ModeSwitch.switch"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                String from = (String) param.args[0];
                String to = (String) param.args[1];
                if (TextUtils.isEmpty(from) || TextUtils.isEmpty(to) || from.equals(to)) return;
                currentMode = to;
                for (State state : states) {
                    if (state.setting != null && !Settings.get(state.setting)) continue;
                    if (state.stockRetroSwap && (RETRO_CAMERA.equals(from) || RETRO_CAMERA.equals(to))) continue;
                    try {
                        if (state.sharedKey == null) state.sharedKey = sharedKey(symbols, classLoader, state);
                        // DataManager.getOr(key, default) dereferences the default; "" marks "not stored".
                        // A Boolean setting is kept as text under the per-mode keys (the shared value defaults to true).
                        boolean bool = state.valueType == Boolean.class;
                        Object current = DataStore.get(symbols, classLoader, state.sharedKey, bool ? (Object) Boolean.TRUE : "");
                        if (!"".equals(current)) DataStore.put(symbols, classLoader, modeKey(symbols, state, from), bool ? current.toString() : current);
                        Object saved = DataStore.get(symbols, classLoader, modeKey(symbols, state, to), "");
                        boolean restore = !"".equals(saved);
                        if (restore) DataStore.put(symbols, classLoader, state.sharedKey, bool ? (Object) Boolean.valueOf((String) saved) : saved);
                        Log.i(TAG, state.name + " " + from + "=" + current + " -> " + to + "=" + (restore ? saved : current + " (first visit)"));
                    } catch (Throwable error) {
                        Log.e(TAG, state.name + " swap failed " + from + " -> " + to, error);
                    }
                }
            }
        });
        Log.guard(TAG, "live photo auto turn on", () -> installAutoOn(symbols, classLoader, states[1]));
        Log.i(TAG, "installed");
    }

    /**
     * On entering a mode the camera decides by itself whether to turn live photo on (it keeps one in-memory "closed by
     * the user" state for all photo modes and ignores what the mode saved). After the saved value of the mode was put
     * back as off, that decision turned it on again, so a live photo switched on in photo mode came along into 哈苏超清.
     * The decision is skipped for a mode whose own saved value and the value now in place are both off.
     */
    private static void installAutoOn(Symbols symbols, ClassLoader classLoader, State live) {
        XposedBridge.hookMethod(symbols.method("LivePhoto.autoOn"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                String mode = currentMode;
                if (mode == null || RETRO_CAMERA.equals(mode) || !Settings.get(live.setting)) return;
                try {
                    if (live.sharedKey == null) live.sharedKey = sharedKey(symbols, classLoader, live);
                    Object now = DataStore.get(symbols, classLoader, live.sharedKey, "");
                    Object saved = DataStore.get(symbols, classLoader, modeKey(symbols, live, mode), "");
                    if (!"off".equals(now) || !"off".equals(saved)) return;
                    param.setResult(Boolean.FALSE);
                    Log.i(TAG, "live " + mode + ": camera's auto turn on skipped (saved off)");
                } catch (Throwable error) {
                    Log.e(TAG, "live auto turn on guard failed", error);
                }
            }
        });
    }

    /** Flash: from its defining holder. Live photo: the mode switch's own key of that name. */
    private static Object sharedKey(Symbols symbols, ClassLoader classLoader, State state) throws Throwable {
        if ("proGear".equals(state.name)) return DataStore.key(symbols, "Keys.proAuto", state.preference);
        if ("flash".equals(state.name)) return DataStore.key(symbols, "Keys.flash", state.preference);
        for (Field field : symbols.<Field>all("ModeSwitch.keys")) {
            Object key = field.get(null);
            if (key != null && Reflection.hasStringField(key, state.preference)) return key;
        }
        throw new IllegalStateException("DataKey not found: " + state.preference);
    }

    private static Object modeKey(Symbols symbols, State state, String mode) throws Throwable {
        synchronized (state.modeKeys) {
            Object key = state.modeKeys.get(mode);
            if (key == null) {
                key = symbols.call(state.factory, null, String.class, state.prefix + mode);
                state.modeKeys.put(mode, key);
            }
            return key;
        }
    }
}
