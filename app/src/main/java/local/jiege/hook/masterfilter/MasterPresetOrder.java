package local.jiege.hook.masterfilter;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import local.jiege.hook.common.DataStore;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.filters.CameraFilterPresets;
import local.jiege.hook.filters.FilterCore;
import local.jiege.hook.filters.FilterScope;
import local.jiege.hook.filters.MasterLayout;

/**
 * The master preset list (EffectData.presets) in the master layout's order: custom presets,
 * 标准 and filters mixed as the settings app arranges them, the hidden ones at the end. The
 * camera's own swipe, pager and animations then follow that order.
 *
 * The camera persists the head of the list as the custom presets (EffectData.persist copies the
 * first PresetDataRepository.d() entries) and inserts a new custom preset at that position, so
 * around each persist the list is put back into the camera's canonical order (custom presets,
 * then built-in presets in filter order) and laid out again afterwards. Every reorder keeps the
 * stored position on the same preset.
 */
final class MasterPresetOrder {
    private static final String TAG = "MasterFilter";
    private static final String POSITION_KEY = "key_master_mode_current_preset_index";
    private MasterPresetOrder() {}

    static void install(Symbols symbols, ClassLoader classLoader, Class<?> filterSource) {
        XposedBridge.hookMethod(symbols.method("EffectData.persist"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                arrange(symbols, classLoader, filterSource, param.thisObject, false);
            }
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                arrange(symbols, classLoader, filterSource, param.thisObject, true);
            }
        });
        // add() stores position d() - 1 (the new preset in canonical order) after persisting.
        XposedBridge.hookMethod(symbols.method("EffectData.add"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                List<Object> presets = presets(symbols, param.thisObject);
                int index = presets == null ? -1 : indexOfIdentity(presets, param.args[0]);
                if (index >= 0) MasterPresetListSync.write(symbols, classLoader, positionKey(symbols), Integer.valueOf(index));
            }
        });
    }

    /** After EffectData.rebuild (MasterPresetListSync, before its selection check). */
    static void layOut(Symbols symbols, ClassLoader classLoader, Class<?> filterSource, Object manager) {
        arrange(symbols, classLoader, filterSource, manager, true);
    }

    /** Lays the list out (laidOut) or puts it back into the camera's canonical order. */
    private static void arrange(Symbols symbols, ClassLoader classLoader, Class<?> filterSource, Object manager, boolean laidOut) {
        try {
            LinkedList<Object> presets = presets(symbols, manager);
            @SuppressWarnings("unchecked") List<String> types = (List<String>) MasterPresetListSync.filterTypes(symbols, manager, filterSource);
            if (presets == null || presets.isEmpty() || types == null || types.isEmpty()) return;
            Map<String, Object> byKey = new HashMap<>();
            List<String> keys = new ArrayList<>();
            for (Object preset : presets) {
                String key = key(preset);
                if (byKey.put(key, preset) != null) return;  // duplicate keys: leave the camera's list alone
                keys.add(key);
            }
            List<String> canonical = new ArrayList<>();
            for (String key : keys) if (key.startsWith("custom_")) canonical.add(key);
            for (String type : types) {
                String key = types.indexOf(type) == 0 ? "" : type;
                if (byKey.containsKey(key) && !canonical.contains(key)) canonical.add(key);
            }
            for (String key : keys) if (!canonical.contains(key)) canonical.add(key);

            List<String> order = canonical;
            if (laidOut) {
                List<String> ids = new ArrayList<>();
                for (String key : canonical) ids.add(key.isEmpty() ? MasterLayout.STANDARD : key);
                MasterLayout.Result layout = MasterLayout.compute(ids, FilterCore.get(FilterScope.MASTER),
                    FilterCore.config().layout(), false);
                order = new ArrayList<>();
                for (String id : layout.order) order.add(MasterLayout.STANDARD.equals(id) ? "" : id);
                CameraFilterPresets.visibleCount(layout.order.size() - layout.hiddenCount);
            }
            if (order.equals(keys)) return;

            Object position = positionKey(symbols);
            int stored = (Integer) DataStore.get(symbols, classLoader, position, Integer.valueOf(0));
            String selected = stored >= 0 && stored < keys.size() ? keys.get(stored) : null;
            presets.clear();
            for (String key : order) presets.add(byKey.get(key));
            if (selected != null) {
                int moved = order.indexOf(selected);
                if (moved != stored) MasterPresetListSync.write(symbols, classLoader, position, Integer.valueOf(moved));
            }
        } catch (Throwable error) {
            Log.e(TAG, "master preset order failed", error);
        }
    }

    private static String key(Object preset) {
        String key = CameraFilterPresets.key(preset);
        return key == null ? "" : key;
    }

    @SuppressWarnings("unchecked")
    private static LinkedList<Object> presets(Symbols symbols, Object manager) throws Throwable {
        return (LinkedList<Object>) symbols.get("EffectData.presets", manager);
    }

    private static Object positionKey(Symbols symbols) throws Throwable {
        return DataStore.key(symbols, "Keys.presetIndex", POSITION_KEY);
    }

    private static int indexOfIdentity(List<Object> list, Object item) {
        for (int i = 0; i < list.size(); i++) if (list.get(i) == item) return i;
        return -1;
    }
}
