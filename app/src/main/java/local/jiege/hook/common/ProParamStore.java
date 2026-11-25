package local.jiege.hook.common;

import de.robv.android.xposed.XposedHelpers;
import java.util.LinkedHashMap;
import java.util.Map;
import local.jiege.hook.filters.ProParamsRecord;

/**
 * The Pro gear parameters in the camera's settings store: value, manual flag and manual index of white balance,
 * colour tone, ISO, exposure compensation, shutter and focus. Shared by the master presets (parameters saved with a
 * preset) and the per-mode split (parameters kept apart for master and GR).
 */
public final class ProParamStore {
    /** Marks a parameter that has no stored value. */
    private static final String MISSING = "\u0001";
    private static final String[] PARAMETERS = {
        ProParamsRecord.WHITE_BALANCE, ProParamsRecord.COLOR_TONE, ProParamsRecord.ISO,
        ProParamsRecord.EXPOSURE_COMPENSATION, ProParamsRecord.EXPOSURE_TIME, ProParamsRecord.FOCUS_MODE
    };

    private ProParamStore() {}

    public static Map<String, String> snapshot(Symbols symbols, ClassLoader loader) throws Throwable {
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : PARAMETERS) {
            values.put(name, (String) DataStore.get(symbols, loader, valueKey(symbols, name), MISSING));
            values.put(name + "_manual_state", String.valueOf(DataStore.get(symbols, loader, manualKey(symbols, name), false)));
            values.put(name + "_manual", String.valueOf(DataStore.get(symbols, loader, indexKey(symbols, name), -1)));
        }
        return values;
    }

    /**
     * Puts the parameters into one state in the store: the given values, or all Auto for null. Every parameter ends
     * up either as recorded or Auto; nothing keeps an earlier value.
     */
    public static void set(Symbols symbols, ClassLoader loader, Map<String, String> values) throws Throwable {
        clear(symbols, loader);
        if (values != null) write(symbols, loader, values);
    }

    /**
     * The one way to change the Pro parameters while the camera runs. The store is the truth; the camera reads the
     * capture parameters from it in the preview setup and the parameter bar shows it only when told to, so all three
     * are brought in line here: the store, the preview setup (pro: whether the Pro gear is the one set up; null when no
     * preview setup is wanted), and the bar. values: the target state, null for all Auto.
     */
    public static void apply(Symbols symbols, ClassLoader loader, Object presenter, Boolean pro, Map<String, String> values) throws Throwable {
        set(symbols, loader, values);
        if (pro != null) {
            symbols.call("CaptureParams.beforePreview", presenter, pro);
            set(symbols, loader, values);
        }
        refreshPanel(symbols, presenter);
    }

    private static void write(Symbols symbols, ClassLoader loader, Map<String, String> values) throws Throwable {
        Object data = DataStore.manager(loader);
        for (String name : PARAMETERS) {
            Object key = valueKey(symbols, name);
            String value = values.get(name);
            if (MISSING.equals(value)) symbols.call("DataManager.remove", data, key);
            else if (value != null) DataStore.put(symbols, loader, key, value);
            String manual = values.get(name + "_manual_state");
            if (manual != null) DataStore.put(symbols, loader, manualKey(symbols, name), Boolean.valueOf(manual));
            String index = values.get(name + "_manual");
            if (index != null) DataStore.put(symbols, loader, indexKey(symbols, name), Integer.valueOf(index));
        }
    }

    private static void clear(Symbols symbols, ClassLoader loader) throws Throwable {
        Object data = DataStore.manager(loader);
        for (String name : PARAMETERS) {
            symbols.call("DataManager.remove", data, valueKey(symbols, name));
            symbols.call("DataManager.remove", data, manualKey(symbols, name));
            symbols.call("DataManager.remove", data, indexKey(symbols, name));
        }
    }

    /** Has the parameter bar of the presenter's capture view show what the store holds now. */
    public static void refreshPanel(Symbols symbols, Object presenter) throws Throwable {
        Object view = symbols.get("CaptureParams.view", presenter);
        if (view == null) return;
        Object panel = symbols.get("CaptureView.parameterPanel", view);
        if (panel == null) return;
        @SuppressWarnings("unchecked")
        Map<Integer, Object> controls = (Map<Integer, Object>) symbols.get("ProPanel.controls", panel);
        if (controls != null) {
            for (Map.Entry<Integer, Object> entry : controls.entrySet()) {
                boolean auto = (Boolean) symbols.call("ProPanel.isAuto", panel, entry.getKey());
                XposedHelpers.callMethod(entry.getValue(), "setBarAuto", auto);
            }
        }
        symbols.call("ProPanel.sync", panel);
        symbols.call("ProPanel.refresh", panel);
    }

    private static Object valueKey(Symbols symbols, String name) throws Throwable {
        return DataStore.key(symbols, "Keys.proAuto", name);
    }

    private static Object manualKey(Symbols symbols, String name) throws Throwable {
        return symbols.call("DataKeys.prefs", null, Boolean.class, name + "_manual_state");
    }

    private static Object indexKey(Symbols symbols, String name) throws Throwable {
        return symbols.call("DataKeys.prefs", null, Integer.class, name + "_manual");
    }
}
