package local.jiege.hook.masterlut;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import local.jiege.hook.common.DataStore;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Reflection;
import local.jiege.hook.common.Symbols;

/**
 * The module's own master effect values (对比度, 颗粒强度 and 大小) in the stock preset data model: each value belongs
 * to the selected preset or filter (the scope the stock keeps its effect values under, the selected preset key),
 * a saved preset (custom_N) has a base next to it (custom_base_N), and the value counts as modified when it
 * differs from the base.
 *
 * The stock decides "modified" and "same as a saved preset" from a fixed list of its own values. Those decisions
 * take the module's values in too: the modified state of the bar, the equality of two
 * preset records (which "另存为新预设" and "保存修改" consult), saving a new preset (the values move to
 * the new preset and its base, the source returns to its base) and saving a modification (the base takes the
 * values).
 */
public final class ModuleEffects {
    private static final String TAG = "ModuleEffects";
    private static final String PRESET_KEY_NAME = "key_master_mode_current_preset_key";
    private static final String PREF_GROUP = "com.oplus.camera_preferences_";
    private static final String CUSTOM = "custom_";
    private static final String CUSTOM_BASE = "custom_base_";
    private static final String MASTER_MODE = "professional";

    /** A value and its unmodified state (also the base of every preset that is not a saved one). */
    public enum Value {
        CONTRAST("key_master_mode_effect_muse_contrast", 0),
        GRAIN_AMOUNT("key_muse_grain_amount", -1),
        GRAIN_SIZE("key_muse_grain_size", -1);

        final String keyName;
        final int unmodified;

        Value(String keyName, int unmodified) {
            this.keyName = keyName;
            this.unmodified = unmodified;
        }
    }

    private static final Object[] keys = new Object[Value.values().length];
    private static volatile Object presetKey;
    private static volatile ClassLoader loader;

    private ModuleEffects() {}

    /** The scope of the selected preset or filter. */
    public static String scope(Symbols symbols) throws Throwable {
        Object key = presetKey;
        if (key == null) {
            key = DataStore.key(symbols, "Keys.masterPreset", PRESET_KEY_NAME);
            presetKey = key;
        }
        Object scope = DataStore.get(symbols, loader, key, "");
        return scope instanceof String ? (String) scope : "";
    }

    /** Whether a DataKey is the selected-preset key (a write of it is a preset change). */
    public static boolean isPresetKey(Object key) throws IllegalAccessException {
        return key != null && Reflection.hasStringField(key, PRESET_KEY_NAME);
    }

    /** The value in a scope; a scope without one holds the base. */
    public static int get(Symbols symbols, Value value, String scope) throws Throwable {
        Object stored = symbols.call("Prefs.get", null, key(symbols, value), scope, Integer.class, base(symbols, value, scope));
        return stored instanceof Integer ? (Integer) stored : value.unmodified;
    }

    public static void put(Symbols symbols, Value which, String scope, int value) throws Throwable {
        symbols.call("Prefs.put", null, key(symbols, which), scope, Integer.class, value);
    }

    /** The unmodified state of a scope: the base of a saved preset, else the parameter's own. */
    public static int base(Symbols symbols, Value value, String scope) throws Throwable {
        String base = baseOf(scope);
        if (base == null) return value.unmodified;
        Object stored = symbols.call("Prefs.get", null, key(symbols, value), base, Integer.class, value.unmodified);
        return stored instanceof Integer ? (Integer) stored : value.unmodified;
    }

    /** Returns a scope's values to its base. */
    public static void reset(Symbols symbols, String scope) throws Throwable {
        for (Value each : Value.values()) put(symbols, each, scope, base(symbols, each, scope));
    }

    public static boolean modified(Symbols symbols, String scope, Value... params) throws Throwable {
        for (Value each : params) if (get(symbols, each, scope) != base(symbols, each, scope)) return true;
        return false;
    }

    private static String baseOf(String scope) {
        if (scope == null || !scope.startsWith(CUSTOM) || scope.startsWith(CUSTOM_BASE)) return null;
        return CUSTOM_BASE + scope.substring(CUSTOM.length());
    }

    private static Object key(Symbols symbols, Value value) throws Throwable {
        synchronized (keys) {
            Object key = keys[value.ordinal()];
            if (key == null) {
                Class<?> dataKey = symbols.method("Prefs.get").getParameterTypes()[0];
                Object group = symbols.call("Prefs.storage", null);
                key = dataKey.getConstructor(String.class, String.class, String.class, Class.class)
                    .newInstance(group, PREF_GROUP, value.keyName, Integer.class);
                keys[value.ordinal()] = key;
            }
            return key;
        }
    }

    /** The key a module effect item carries; the stock stores, restores and reads its value like its own. */
    static Object itemKey(Symbols symbols, Value value) throws Throwable {
        return key(symbols, value);
    }

    public static void installCamera(ClassLoader classLoader) {
        loader = classLoader;
        final Symbols symbols = Symbols.get();
        // The modified state of the bar and of the preset menu.
        Log.guard(TAG, "modified state", () -> XposedBridge.hookMethod(symbols.method("CaptureParams.effectsModified"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (Boolean.TRUE.equals(param.getResult()) || !MASTER_MODE.equals(symbols.get("FeaturePresenter.modeName", param.thisObject))) return;
                if (modified(symbols, scope(symbols), Value.values())) param.setResult(Boolean.TRUE);
            }
        }));
        // A preset record equals another only when the module's values match too: this one is a stored preset (or
        // a stock filter), the other the record of the current state.
        Log.guard(TAG, "preset equality", () -> {
            XposedBridge.hookMethod(symbols.method("PresetRecord.equals"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!Boolean.TRUE.equals(param.getResult())) return;
                    String saved = (String) symbols.get("PresetRecord.key", param.thisObject);
                    String current = scope(symbols);
                    for (Value each : Value.values()) {
                        if (base(symbols, each, saved == null ? "" : saved) != get(symbols, each, current)) {
                            param.setResult(Boolean.FALSE);
                            return;
                        }
                    }
                }
            });
        });
        // 另存为新预设: the values go to the new preset and its base, the source preset returns to its base.
        Log.guard(TAG, "save as preset", () -> XposedBridge.hookMethod(symbols.method("EffectData.savePreset"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                String source = scope(symbols);
                int[] values = new int[Value.values().length];
                for (Value each : Value.values()) values[each.ordinal()] = get(symbols, each, source);
                param.setObjectExtra("source", source);
                param.setObjectExtra("values", values);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object result = param.getResult();
                if (result == null || (Integer) symbols.get("PresetSaveResult.status", result) != 0) return;
                String source = (String) param.getObjectExtra("source");
                int[] values = (int[]) param.getObjectExtra("values");
                String created = scope(symbols);
                if (created.equals(source) || baseOf(created) == null) return;
                for (Value each : Value.values()) {
                    put(symbols, each, created, values[each.ordinal()]);
                    put(symbols, each, baseOf(created), values[each.ordinal()]);
                }
                reset(symbols, source);
                MasterContrast.refresh(symbols);
            }
        }));
        // 保存修改: the stock writes the preset's base (its cold-warm value, once per save); the base takes the values.
        Log.guard(TAG, "save modification", () -> XposedBridge.hookMethod(symbols.method("Prefs.put"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object scope = param.args[1];
                if (!(scope instanceof String) || !((String) scope).startsWith(CUSTOM_BASE)
                    || !Reflection.hasStringField(param.args[0], "key_master_mode_effect_cold_warm")) return;
                String preset = CUSTOM + ((String) scope).substring(CUSTOM_BASE.length());
                for (Value each : Value.values()) {
                    put(symbols, each, (String) scope, get(symbols, each, preset));
                }
            }
        }));
    }
}
