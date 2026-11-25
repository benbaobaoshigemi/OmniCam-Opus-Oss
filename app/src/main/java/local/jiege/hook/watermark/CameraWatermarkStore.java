package local.jiege.hook.watermark;

import java.util.HashMap;
import java.util.Map;
import local.jiege.hook.common.DataStore;
import local.jiege.hook.common.Symbols;

/** The camera's photo slot and per-mode memories, using its existing preferences. */
final class CameraWatermarkStore implements WatermarkCore.Store {
    static final String PHOTO_STYLE = "pref_ai_master_watermark_photo_style_id";
    static final String PHOTO_BACKUP = PHOTO_STYLE + "_backup";
    static final String PHOTO_OPEN = "pref_ai_master_watermark_photo_open_state";
    static final String PHOTO_SWITCH = "pref_watermark_capture_switch_key";
    static final String GR_STYLE = "pref_ai_master_watermark_mode_limit_style_id";
    static final String GR_BACKUP = GR_STYLE + "_backup";
    static final String GR_OPEN = "pref_ai_master_watermark_mode_limit_open_state";
    static final String GR_SWITCH = "pref_watermark_gr_capture_switch_key";
    static final String MODE_NAME = "pref_watermark_mode_limit_name";
    static final String[] PHOTO_PREFS = {PHOTO_STYLE, PHOTO_BACKUP, PHOTO_OPEN, PHOTO_SWITCH};
    static final String[] GR_PREFS = {GR_STYLE, GR_BACKUP, GR_OPEN, GR_SWITCH};
    private static final ThreadLocal<Boolean> raw = new ThreadLocal<>();

    private final Symbols symbols;
    private final ClassLoader loader;
    private final Map<String, Object> keys = new HashMap<>();
    private volatile Object modeKey;
    private volatile Object grSwitchKey;

    CameraWatermarkStore(Symbols symbols, ClassLoader loader) {
        this.symbols = symbols;
        this.loader = loader;
    }

    static boolean rawAccess() { return Boolean.TRUE.equals(raw.get()); }

    private synchronized Object key(String preference, Class<?> type, boolean saved) throws Throwable {
        Object key = keys.get(preference);
        if (key == null) {
            key = saved ? symbols.call("DataKeys.prefs", null, type, preference)
                : DataStore.key(symbols, "Keys.styleId", preference);
            keys.put(preference, key);
        }
        return key;
    }

    private Object read(String name, Object fallback, boolean saved) throws Throwable {
        Object key = key(name, fallback.getClass(), saved);
        Boolean previous = raw.get();
        raw.set(Boolean.TRUE);
        try { return DataStore.get(symbols, loader, key, fallback); }
        finally { if (previous == null) raw.remove(); else raw.set(previous); }
    }

    private void write(String name, Object value, boolean saved, boolean capture) throws Throwable {
        Object key = key(name, value.getClass(), saved);
        Boolean previous = raw.get();
        raw.set(Boolean.TRUE);
        try {
            if (capture) symbols.call("DataManager.putSync", DataStore.manager(loader), key, value, Boolean.FALSE);
            else DataStore.put(symbols, loader, key, value);
        } finally { if (previous == null) raw.remove(); else raw.set(previous); }
    }

    String value(String name, String fallback) throws Throwable { return (String) read(name, fallback, false); }

    void grStyle(String style) throws Throwable { write(GR_STYLE, style, false, false); }

    /** Sets the GR watermark switch ("on" / "off"). */
    void grSwitch(String value) throws Throwable {
        Object grKey = grSwitchKey;
        if (grKey == null) grSwitchKey = grKey = DataStore.key(symbols, "Keys.grWatermark", GR_SWITCH);
        Boolean previous = raw.get();
        raw.set(Boolean.TRUE);
        try { symbols.call("DataManager.putSync", DataStore.manager(loader), grKey, value, Boolean.FALSE); }
        finally { if (previous == null) raw.remove(); else raw.set(previous); }
    }

    boolean grOn() throws Throwable {
        Object grKey = grSwitchKey;
        if (grKey == null) grSwitchKey = grKey = DataStore.key(symbols, "Keys.grWatermark", GR_SWITCH);
        return !"off".equals(DataStore.get(symbols, loader, grKey, "on"));
    }

    String mode() throws Throwable {
        Object key = modeKey;
        if (key == null) modeKey = key = DataStore.key(symbols, "Keys.cameraMode", "pref_camera_mode_key");
        return (String) DataStore.get(symbols, loader, key, "common");
    }

    @Override public synchronized String owner() throws Throwable {
        String owner = (String) read("pref_jiege_wm_photo_owner", "", true);
        if (!owner.isEmpty()) return owner;
        return live().style.startsWith("hassel_style_xpan_") ? WatermarkCore.XPAN : WatermarkCore.SHARED;
    }

    @Override public synchronized void owner(String group) throws Throwable {
        write("pref_jiege_wm_photo_owner", group, true, false);
    }

    @Override public synchronized WatermarkCore.State live() throws Throwable {
        return new WatermarkCore.State(value(PHOTO_STYLE, ""), value(PHOTO_BACKUP, ""),
            Boolean.TRUE.equals(read(PHOTO_OPEN, Boolean.FALSE, false)), value(PHOTO_SWITCH, "off"));
    }

    @Override public synchronized WatermarkCore.State read(String group) throws Throwable {
        String prefix = "pref_jiege_wm_" + group + "_";
        String style = (String) read(prefix + "style", "", true);
        boolean present = Boolean.TRUE.equals(read(prefix + "present", Boolean.FALSE, true));
        if (!present && style.isEmpty()) return null;
        return new WatermarkCore.State(style, (String) read(prefix + "backup", style, true),
            Boolean.parseBoolean((String) read(prefix + "open", "false", true)),
            (String) read(prefix + "switch", "off", true));
    }

    @Override public synchronized void save(String group, WatermarkCore.State state) throws Throwable {
        saveMemory(group, state);
        retain(state);
    }

    synchronized void saveMemory(String group, WatermarkCore.State state) throws Throwable {
        String prefix = "pref_jiege_wm_" + group + "_";
        write(prefix + "style", state.style, true, false);
        write(prefix + "backup", state.backup, true, false);
        write(prefix + "open", String.valueOf(state.open), true, false);
        write(prefix + "switch", state.capture, true, false);
        write(prefix + "present", Boolean.TRUE, true, false);
    }

    void retain(WatermarkCore.State state) {
        WatermarkFiles.backupFiles(state.style);
        if (!state.style.equals(state.backup)) WatermarkFiles.backupFiles(state.backup);
    }

    @Override public boolean available(WatermarkCore.State state) {
        if (!state.backup.isEmpty()) WatermarkFiles.ensureFiles(state.backup);
        return !state.on() || WatermarkFiles.ensureFiles(state.style);
    }

    @Override public synchronized void apply(WatermarkCore.State state) throws Throwable {
        write(PHOTO_STYLE, state.style, false, false);
        write(PHOTO_BACKUP, state.backup, false, false);
        write(PHOTO_OPEN, state.open, false, false);
        write(PHOTO_SWITCH, state.capture, false, true);
    }

    @Override public synchronized void clear() throws Throwable {
        String backup = value(PHOTO_BACKUP, "");
        apply(new WatermarkCore.State("", backup.startsWith("hassel_style_xpan_") ? "" : backup, false, "off"));
    }
}
