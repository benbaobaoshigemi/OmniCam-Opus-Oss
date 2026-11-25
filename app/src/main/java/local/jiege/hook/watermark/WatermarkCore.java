package local.jiege.hook.watermark;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Mode ownership and complete watermark memories, independent of camera hooks and style families. */
public final class WatermarkCore {
    public enum Channel { PHOTO, VIDEO }
    public static final String SHARED = "other";
    public static final String GR = "gr";
    public static final String XPAN = "xpan";

    public static final class Policy {
        private final Set<String> photoModes;
        private final Set<String> videoModes;

        public Policy(Set<String> photoModes, Set<String> videoModes) {
            this.photoModes = Collections.unmodifiableSet(new HashSet<>(photoModes));
            this.videoModes = Collections.unmodifiableSet(new HashSet<>(videoModes));
        }

        public String group(String mode, Channel channel) {
            if (channel == Channel.PHOTO && GR.equals(mode)) return GR;
            return (channel == Channel.PHOTO ? photoModes : videoModes).contains(mode) ? mode : SHARED;
        }
    }

    /** Empty style and disabled switch are values to remember, including when backup is nonempty. */
    public static final class State {
        public final String style;
        public final String backup;
        public final boolean open;
        public final String capture;

        public State(String style, String backup, boolean open, String capture) {
            this.style = style;
            this.backup = backup;
            this.open = open;
            this.capture = capture;
        }

        public boolean on() { return open && "on".equals(capture) && !style.isEmpty(); }
    }

    /** A native slot and its persisted per-group memories. Null memory means no selection yet. */
    public interface Store {
        String owner() throws Throwable;
        void owner(String group) throws Throwable;
        State live() throws Throwable;
        State read(String group) throws Throwable;
        void save(String group, State state) throws Throwable;
        boolean available(State state) throws Throwable;
        void apply(State state) throws Throwable;
        void clear() throws Throwable;
    }

    private WatermarkCore() {}

    /** Swap one native slot between memory groups; the dedicated GR photo slot is not swapped. */
    public static void activate(Store store, String target) throws Throwable {
        String source = store.owner();
        if (source.equals(target)) return;
        State live = store.live();
        store.save(source, live);
        State selected = store.read(target);
        if (selected != null && store.available(selected)) {
            store.apply(selected);
        } else if (SHARED.equals(target)) {
            // A cold start with a style kept by an independent mode has no shared selection to restore.
            store.clear();
        }
        // A group opened for the first time inherits the current selection until edited.
        store.owner(target);
    }
}
