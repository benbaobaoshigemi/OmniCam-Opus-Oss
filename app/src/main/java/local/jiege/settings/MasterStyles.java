package local.jiege.settings;

/**
 * The master colour style plugins as the settings page and the camera hook see them (大师色彩). The order is the order
 * shown; each style's index is what the master_look setting stores.
 */
public final class MasterStyles {
    /** 模式选择: the chosen look replaces the stock master LMT, or is blended with it (tone / colour weights). */
    public static final int MODE_REPLACE = 0;
    public static final int MODE_BLEND = 1;

    public static final MasterStyle[] ALL = {
        new StockStyle(), new BasicStyle(), new HncsStyle(), new LumixStyle(), new FilmStyle(), new CanonStyle(),
        new DarktableStyle(), new LeicaStyle(), new LeicaM240Style(), new LeicaM10rStyle(), new X100viStyle(),
        new AdobeSigmaStyle(), new AdobeOmStyle(), new AdobeSonyStyle(), new AdobeNikonStyle(),
    };

    /** What master_look holds before the user chooses, and for a stored value that names no style. */
    public static final MasterStyle DEFAULT = ALL[2];

    private MasterStyles() {}

    public static MasterStyle byIndex(int index) {
        for (MasterStyle style : ALL)
            if (style.index == index) return style;
        return DEFAULT;
    }

    /** The default of a style's own setting (a choice is the first option, a parameter its default), or null if no style owns the key. */
    public static Integer defaultOf(String key) {
        for (MasterStyle style : ALL) {
            for (MasterStyle.Choice choice : style.choices())
                if (choice.key.equals(key)) return 0;
            for (MasterStyle.Param param : style.allParams())
                if (param.key.equals(key)) return param.def;
        }
        return null;
    }
}
