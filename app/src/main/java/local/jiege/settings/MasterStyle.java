package local.jiege.settings;

/**
 * A master colour style plugin's description of itself: what the settings page shows for it, which settings it owns and
 * the look id (a native style's look, see cpp/master_style.h) the current selections stand for. The page and the camera
 * hook read nothing else about a style, so a new style is one subclass listed in {@link MasterStyles} next to its native
 * plugin.
 *
 * Selections are integer settings (the index of the chosen option, or a parameter's value in setting units); a
 * {@link IntLookup} reads them, from the settings store in the app or from the preferences in the camera.
 */
public abstract class MasterStyle {
    public interface IntLookup {
        int get(String key);
    }

    /** Whether a card is shown for the current selections. */
    public interface When {
        boolean test(IntLookup selections);
    }

    /** Whether one option of a card is offered for the current selections. */
    public interface OptionWhen {
        boolean test(int index, IntLookup selections);
    }

    public interface OptionLabel {
        String get(int index, IntLookup selections);
    }

    public static final When ALWAYS = selections -> true;

    /** A card of options; the index of the chosen one is the integer setting `key` (0 = the first by default). */
    public static final class Choice {
        public final String title, key;
        public final String[] ids, labels;
        public final String[] summaries;   // null: none
        public final When visible;
        public final OptionWhen optionShown;   // null: every option
        public OptionLabel labelOf;            // null: labels[index]; else the label shown for the current selections

        public Choice(String title, String key, String[] ids, String[] labels, String[] summaries, When visible) {
            this(title, key, ids, labels, summaries, visible, null);
        }

        public Choice(String title, String key, String[] ids, String[] labels, String[] summaries, When visible, OptionWhen optionShown) {
            this.title = title;
            this.key = key;
            this.ids = ids;
            this.labels = labels;
            this.summaries = summaries;
            this.visible = visible;
            this.optionShown = optionShown;
        }

        /** The label shown for option `index`. */
        public String label(int index, IntLookup selections) {
            return labelOf == null ? labels[index] : labelOf.get(index, selections);
        }

        /** Whether option `index` is offered for the current selections. */
        public boolean offered(int index, IntLookup selections) {
            return optionShown == null || optionShown.test(index, selections);
        }

        /** The chosen option's id, the first for an index out of range. */
        public String id(IntLookup selections) {
            int index = selections.get(key);
            return ids[index < 0 || index >= ids.length ? 0 : index];
        }
    }

    /**
     * A setting the user adjusts: the name the native style knows it by (mastercolor::Key::params), the integer setting
     * that holds it, label, unit, and range / default in setting units (the value shown, and passed to the style, is
     * setting / scale).
     */
    public static final class Param {
        public final String id, key, title, unit, hint;   // hint: what the setting is and does, shown under the value
        public final int scale, min, max, def;
        /** Discrete grid in raw units (0 = continuous); the slider snaps to min + k*step and shows tick marks. */
        public final int step;

        public Param(String id, String key, String title, String unit, int scale, int min, int max, int def) {
            this(id, key, title, unit, scale, min, max, def, "", 0);
        }

        public Param(String id, String key, String title, String unit, int scale, int min, int max, int def, String hint) {
            this(id, key, title, unit, scale, min, max, def, hint, 0);
        }

        public Param(String id, String key, String title, String unit, int scale, int min, int max, int def, String hint, int step) {
            this.step = step;
            this.hint = hint;
            this.id = id;
            this.key = key;
            this.title = title;
            this.unit = unit;
            this.scale = scale;
            this.min = min;
            this.max = max;
            this.def = def;
        }
    }

    protected static final Choice[] NO_CHOICES = {};
    protected static final Param[] NO_PARAMS = {};

    /** The persisted value of the master_look setting that selects this style; never reused for another style. */
    public final int index;
    public final String label, summary;

    protected MasterStyle(int index, String label, String summary) {
        this.index = index;
        this.label = label;
        this.summary = summary;
    }

    /** The option cards in the order shown. */
    public Choice[] choices() {
        return NO_CHOICES;
    }

    /** Every parameter the style can show, for their defaults. */
    public Param[] allParams() {
        return NO_PARAMS;
    }

    /** The look id (looks/<id>.bin, or a native look without a file) the selections stand for. */
    public abstract String lookId(IntLookup selections);

    /** Settings the native style takes that come from the choices (name -> value in the style's unit). */
    public java.util.Map<String, Float> settings(IntLookup selections) {
        return java.util.Collections.emptyMap();
    }

    /** The parameters shown, and sent to the native style, for the selections. */
    public Param[] params(IntLookup selections) {
        return NO_PARAMS;
    }

    /** False for the stock look: there is nothing to blend it with. */
    public boolean blendable() {
        return true;
    }
}
