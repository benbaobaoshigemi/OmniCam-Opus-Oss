package local.jiege.settings;

/** Leica M (Typ 240) film modes from the camera firmware 2.1.0.0 (native style "m240"; looks/leica_m240_<mode>.bin). */
final class LeicaM240Style extends MasterStyle {
    static final String[] LEICA_M240_IDS = {"standard", "vivid", "natural"};
    private static final String[] LABELS = {"标准", "鲜艳", "自然"};
    private static final String[] SUMMARIES = {
        "原机默认：影调耐看，对比度、饱和度各五档",
        "阴影压得深、颜色浓；对比度、饱和度固定",
        "阴影偏重、颜色适中；对比度、饱和度固定",
    };
    private static final Choice MODE = new Choice("胶片模式", "master_look_leica_m240", LEICA_M240_IDS, LABELS, SUMMARIES, ALWAYS);
    // The camera's menu steps, low .. high, stored -2 .. 2 (0 = the middle). Only Standard has them: Vivid and Natural use one fixed
    // tone and saturation curve in the firmware.
    private static final Param[] PARAMS = {
        new Param("contrast", "master_m240_contrast", "对比度", "", 1, -2, 2, 0, "原机五档：低、中低、中、中高、高", 1),
        new Param("saturation", "master_m240_saturation", "色彩饱和度", "", 1, -2, 2, 0, "原机五档：低、中低、中、中高、高", 1),
    };

    LeicaM240Style() {
        super(17, "M240-P", "徕卡 M (Typ 240) 机内胶片模式（固件 2.1.0.0）");
    }

    @Override public Choice[] choices() {
        return new Choice[] {MODE};
    }

    @Override public Param[] allParams() {
        return PARAMS;
    }

    @Override public Param[] params(IntLookup selections) {
        return MODE.id(selections).equals("standard") ? PARAMS : NO_PARAMS;
    }

    @Override public String lookId(IntLookup selections) {
        return "leica_m240_" + MODE.id(selections);
    }
}
