package local.jiege.hook.filters;

/** A selectable filter entity. Several entities may use the same LUT. */
public final class FilterChoice {
    public enum Kind { FILTER, CUSTOM }

    /** The camera's parameter-storage key: custom_N, a filter type, or empty for 标准. */
    public final String key;
    public final String filterType;
    public final String title;
    public final Kind kind;
    /** Position in the camera's own list, independent of presentation order. */
    public final int cameraIndex;
    /** Resource registration, null for an unmodified factory filter. */
    public final FilterEntry resource;

    public FilterChoice(String key, String filterType, String title, Kind kind, int cameraIndex, FilterEntry resource) {
        this.key = key;
        this.filterType = filterType;
        this.title = title;
        this.kind = kind;
        this.cameraIndex = cameraIndex;
        this.resource = resource;
    }
}
