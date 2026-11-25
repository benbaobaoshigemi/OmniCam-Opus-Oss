package local.jiege.hook.palette;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import local.jiege.hook.common.Log;
import local.jiege.hook.filters.FilterCore;
import local.jiege.hook.filters.FilterEntry;
import local.jiege.hook.filters.FilterScope;

/**
 * Gallery process: the module's filters in the photo editor's filter / colour style lists.
 *
 * The gallery lists the camera unit SDK's filter groups (FilterGroupManager, from the jar
 * GalleryColorPalette loads). After the SDK fills them, the default catalog entries are appended:
 * normal entries to the common group, master entries to the master group. The name is the entry
 * title (the gallery shows a plain name as it is; "R.string.x" names are its own resources).
 * Imported LUTs live in the camera's data and are left out, as is any entry whose LUT is not in
 * the shared LUT folders. Appending keeps the stock indices, which watermarks refer to.
 */
final class GalleryFilters {
    private static final String TAG = "GalleryFilters";
    private static final String MANAGER = "com.oplus.ocs.camera.ipusdk.processunit.filter.list.FilterGroupManager";
    private static final String[] LUT_DIRS = {"/odm/etc/camera/meishe_lut/", "/odm/etc/camera/filters_lut/"};

    private GalleryFilters() {}

    static void install(ClassLoader loader) {
        hookPaletteTables(loader);
        try {
            final Class<?> manager = XposedHelpers.findClass(MANAGER, loader);
            XposedHelpers.findAndHookMethod(manager, "init", android.content.Context.class, boolean.class, boolean.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    int normal = append(XposedHelpers.getStaticObjectField(manager, "sFilterGroup"), FilterScope.NORMAL);
                    int master = append(XposedHelpers.getStaticObjectField(manager, "sProFilterGroup"), FilterScope.MASTER);
                    Log.i(TAG, "gallery filters added: normal " + normal + ", master " + master);
                    masterTypes = typesOf(XposedHelpers.getStaticObjectField(manager, "sProFilterGroup"));
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "gallery filter hook failed", error);
        }
    }

    private static volatile Set<String> masterTypes = Collections.emptySet();

    /** The type ids of the master group as the editor lists them; empty before the SDK filled it. */
    static Set<String> masterTypes() {
        return masterTypes;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> typesOf(Object group) {
        return new HashSet<>((List<String>) XposedHelpers.getObjectField(group, "mBackTypeList"));
    }

    @SuppressWarnings("unchecked")
    private static int append(Object group, FilterScope scope) {
        int added = 0;
        try {
            List<String> types = (List<String>) XposedHelpers.getObjectField(group, "mBackTypeList");
            for (FilterEntry entry : FilterCore.get(scope).entries()) {
                if (!entry.enabled || entry.masterOnly || entry.source == FilterEntry.Source.IMPORTED) continue;
                if (!lutExists(entry.id)) continue;
                registerPalette(entry.id);
                if (types.contains(entry.id)) continue;
                String name = entry.title != null ? entry.title : "R.string." + entry.nameResource;
                XposedHelpers.callMethod(group, "addBack", entry.id, name);
                added++;
            }
        } catch (Throwable error) {
            Log.e(TAG, "gallery " + scope + " filters failed", error);
        }
        return added;
    }

    private static final Set<String> moduleTypes = Collections.synchronizedSet(new HashSet<>());

    private static void registerPalette(String type) {
        moduleTypes.add(type);
    }

    /**
     * The editor opens the palette panel only for types in the SDK's colour tables (hasPalette, get); the
     * tables are fixed maps without the module's filters. A module filter is answered with the default row
     * of the table, which keeps the panel and its seek bar drawable.
     */
    private static void hookPaletteTables(ClassLoader loader) {
        String base = "com.oplus.ocs.camera.ipusdk.processunit.filter.palette.";
        for (String table : new String[] {"PaletteColorTable", "PaletteSeekbarColorTable"}) {
            try {
                Class<?> cls = XposedHelpers.findClass(base + table, loader);
                XposedHelpers.findAndHookMethod(cls, "get", String.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getResult() != null || !moduleTypes.contains(param.args[0])) return;
                        param.setResult(XposedHelpers.callStaticMethod(cls, "get", "default"));
                    }
                });
                if ("PaletteColorTable".equals(table)) {
                    XposedHelpers.findAndHookMethod(cls, "hasPalette", String.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (moduleTypes.contains(param.args[0])) param.setResult(Boolean.TRUE);
                        }
                    });
                }
            } catch (Throwable error) {
                Log.e(TAG, "palette table hook " + table, error);
            }
        }
    }

    private static boolean lutExists(String type) {
        for (String dir : LUT_DIRS) {
            if (new File(dir + type).isFile()) return true;
        }
        return false;
    }
}
