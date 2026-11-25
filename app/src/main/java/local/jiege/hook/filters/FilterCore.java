package local.jiege.hook.filters;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Filter resources, user configuration and selectable entities for each camera scope.
 * FilterEntry describes a LUT registration; FilterChoice describes an entity with its own
 * parameter key. Factory filters and custom presets keep the camera's parameter storage.
 * Adapters register resources and translate positions; presentation order never changes LUT
 * identity or the camera's internal custom-preset order.
 */
public final class FilterCore {
    private static final Map<FilterScope, FilterCore> catalogs = new EnumMap<>(FilterScope.class);
    private static MasterConfig config = MasterConfig.empty();
    private static boolean mirrorMaster;

    public final FilterScope scope;
    private final List<FilterEntry> entries;
    private final Map<String, FilterEntry> byId = new LinkedHashMap<>();

    private FilterCore(FilterScope scope, List<FilterEntry> list) {
        this.scope = scope;
        for (FilterEntry entry : list) {
            if (byId.put(entry.id, entry) != null) throw new IllegalStateException("Duplicate " + scope + " filter " + entry.id);
        }
        this.entries = Collections.unmodifiableList(new ArrayList<>(list));
    }

    public static synchronized FilterCore get(FilterScope scope) {
        if (catalogs.isEmpty()) {
            List<FilterEntry> master = MasterFilterDefaults.entries(config);
            List<FilterEntry> normal = NormalFilterDefaults.entries();
            if (mirrorMaster) normal.addAll(NormalMasterMirror.mirrored(master, normal, config.layout()));
            catalogs.put(FilterScope.NORMAL, new FilterCore(FilterScope.NORMAL, normal));
            catalogs.put(FilterScope.MASTER, new FilterCore(FilterScope.MASTER, master));
            checkShared();
        }
        return catalogs.get(scope);
    }

    /** Sets the user's configuration (imported filters) and rebuilds the catalogs on next use. */
    public static synchronized void configure(MasterConfig userConfig) {
        configure(userConfig, false);
    }

    /**
     * As configure(userConfig); with mirrorMaster the normal catalog also lists the filters the
     * master list shows (NormalMasterMirror).
     */
    public static synchronized void configure(MasterConfig userConfig, boolean mirrorMaster) {
        config = userConfig == null ? MasterConfig.empty() : userConfig;
        FilterCore.mirrorMaster = mirrorMaster;
        catalogs.clear();
    }

    /** Whether the normal catalog follows the master list. */
    public static synchronized boolean mirrorsMaster() {
        return mirrorMaster;
    }

    public static synchronized MasterConfig config() {
        return config;
    }

    /** All entries in display order, disabled ones included. */
    public List<FilterEntry> entries() {
        return entries;
    }

    /** Entries to show, in display order. */
    public List<FilterEntry> enabled() {
        List<FilterEntry> out = new ArrayList<>();
        for (FilterEntry entry : entries) if (entry.enabled) out.add(entry);
        return out;
    }

    /** The entry for a filter type, or null for filters this scope does not add. */
    public FilterEntry find(String id) {
        return id == null ? null : byId.get(id);
    }

    /** Binds an entity to a resource without copying its camera-owned adjustment parameters. */
    public FilterChoice choice(String key, String type, String presetName, int cameraIndex) {
        boolean custom = key != null && key.startsWith("custom_");
        FilterEntry resource = find(type);
        String title = custom ? presetName : resource == null ? null : resource.title;
        return new FilterChoice(key, type, title, custom ? FilterChoice.Kind.CUSTOM : FilterChoice.Kind.FILTER,
            cameraIndex, resource);
    }

    /**
     * Some camera state is keyed by filter type alone, not by scope: the capture ID map, and the
     * filter list names (the list beans carry no mode). A type in both scopes must agree there.
     */
    private static void checkShared() {
        FilterCore master = catalogs.get(FilterScope.MASTER);
        for (FilterEntry normal : catalogs.get(FilterScope.NORMAL).entries) {
            FilterEntry other = master.find(normal.id);
            if (other == null) continue;
            if (!equal(normal.captureIdFrom, other.captureIdFrom) || !equal(normal.title, other.title)
                    || !equal(normal.nameResource, other.nameResource)) {
                throw new IllegalStateException(normal.id + ": normal and master entries disagree on shared state");
            }
        }
    }

    private static boolean equal(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
