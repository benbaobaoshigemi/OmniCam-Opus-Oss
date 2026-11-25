package local.jiege.settings;

/** Leica M10-R in-camera JPEG look from firmware 40.26.35.51 (native style "m10r"; looks/leica_m10r_<mode>.bin). */
final class LeicaM10rStyle extends MasterStyle {
    static final String[] LEICA_M10R_IDS = {"color", "monochrome"};
    private static final String[] LABELS = {"彩色", "黑白"};
    private static final String[] SUMMARIES = {
        "原机默认：柔和耐看高光，对比度与饱和度各三档",
        "原机单色：BT.601 零色度黑白，保留对比度三档",
    };
    private static final Choice MODE = new Choice("色彩模式", "master_look_leica_m10r", LEICA_M10R_IDS, LABELS, SUMMARIES, ALWAYS);
    // Firmware B2Y tables [134/136/138] and [156/157/158]: low (-1), medium (0, default), high (1).
    private static final Param CONTRAST = new Param(
        "contrast", "master_m10r_contrast", "对比度", "", 1, -1, 1, 0, "原机三档：低、中（默认）、高", 1);
    private static final Param SATURATION = new Param(
        "saturation", "master_m10r_saturation", "色彩饱和度", "", 1, -1, 1, 0, "原机三档：低、中（默认）、高", 1);
    private static final Param[] COLOR_PARAMS = {CONTRAST, SATURATION};
    private static final Param[] MONO_PARAMS = {CONTRAST};

    LeicaM10rStyle() {
        super(18, "M10-R", "徕卡 M10-R 机内色彩与影调（固件 40.26.35.51）");
    }

    @Override public Choice[] choices() {
        return new Choice[] {MODE};
    }

    @Override public Param[] allParams() {
        return COLOR_PARAMS;
    }

    @Override public Param[] params(IntLookup selections) {
        return MODE.id(selections).equals("monochrome") ? MONO_PARAMS : COLOR_PARAMS;
    }

    @Override public String lookId(IntLookup selections) {
        return "leica_m10r_" + MODE.id(selections);
    }
}
