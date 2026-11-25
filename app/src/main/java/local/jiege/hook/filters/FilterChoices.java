package local.jiege.hook.filters;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One presentation of filter entities, with explicit mapping to the camera's positions. */
public final class FilterChoices {
    private final List<FilterChoice> entries;
    private final Map<String, FilterChoice> byKey = new LinkedHashMap<>();
    private final Map<Integer, Integer> pages = new HashMap<>();

    public FilterChoices(List<FilterChoice> choices) {
        entries = Collections.unmodifiableList(new ArrayList<>(choices));
        for (int page = 0; page < entries.size(); page++) {
            FilterChoice choice = entries.get(page);
            byKey.put(choice.key, choice);
            pages.put(choice.cameraIndex, page);
        }
    }

    public List<FilterChoice> entries() { return entries; }
    public int size() { return entries.size(); }
    public FilterChoice at(int page) { return entries.get(page); }
    public FilterChoice find(String key) { return byKey.get(key); }
    public int pageOf(int cameraIndex) { return pages.getOrDefault(cameraIndex, -1); }

    /** Order and visibility belong to entities; hiding a LUT does not hide its custom presets. */
    public FilterChoices arrange(List<String> order, Set<String> hidden) {
        Map<String, FilterChoice> remaining = new LinkedHashMap<>(byKey);
        List<FilterChoice> result = new ArrayList<>();
        for (String key : order) {
            FilterChoice choice = remaining.remove(key);
            if (choice != null && !hidden.contains(key)) result.add(choice);
        }
        for (FilterChoice choice : remaining.values()) if (!hidden.contains(choice.key)) result.add(choice);
        return new FilterChoices(result);
    }
}
