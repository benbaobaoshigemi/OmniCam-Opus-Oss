package local.jiege.settings;

/** Leica M9 colour engine from the camera firmware 1.216 (native style "m9"; looks/leica_m9.bin). */
final class LeicaStyle extends MasterStyle {
    // The camera's menu steps, low .. high, stored -2 .. 2 (0 = the middle).
    private static final Param[] PARAMS = {
        new Param("contrast", "master_m9_contrast", "对比度", "", 1, -2, 2, 0, "原机五档：低、中低、中、中高、高", 1),
        new Param("saturation", "master_m9_saturation", "色彩饱和度", "", 1, -2, 2, 0, "原机五档：低、中低、中、中高、高", 1),
    };

    LeicaStyle() {
        super(8, "M9", "徕卡 M9 机内色彩与影调（固件 1.216）");
    }

    @Override public Param[] allParams() {
        return PARAMS;
    }

    @Override public Param[] params(IntLookup selections) {
        return PARAMS;
    }

    @Override public String lookId(IntLookup selections) {
        return "leica_m9";
    }
}
