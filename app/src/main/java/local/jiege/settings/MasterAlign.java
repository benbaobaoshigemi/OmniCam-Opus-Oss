package local.jiege.settings;

/**
 * Exposure compensation of a master look: an EV offset (optionally against the camera's own EV), or an alignment of the look
 * to this phone's stock master look (LMTLut3): an EV offset. The offsets come from MasterAlignTable (tools/mastercolor/align_table.py). A look
 * that cannot reach the stock value with any exposure, or that is not in the table, does not offer that alignment.
 */
public final class MasterAlign {
    public static final int MODE_OFFSET = 0;
    public static final int MODE_ALIGN = 1;

    /** Base offset mode slider (1/100 EV); prefer {@link #offsetParam(int)} for per-engine storage. */
    public static final MasterStyle.Param OFFSET = offsetParam(0);

    public static String exposureKey(int styleIndex) {
        return ModuleSettings.MASTER_LOOK_EXPOSURE + "_" + styleIndex;
    }

    public static String alignKey(int styleIndex) {
        return ModuleSettings.MASTER_ALIGN + "_" + styleIndex;
    }

    public static String exposureModeKey(int styleIndex) {
        return ModuleSettings.MASTER_EXPOSURE_MODE + "_" + styleIndex;
    }

    public static String wbTempKey(int styleIndex) {
        return ModuleSettings.MASTER_WB_TEMP + "_" + styleIndex;
    }

    public static String wbTintKey(int styleIndex) {
        return ModuleSettings.MASTER_WB_TINT + "_" + styleIndex;
    }

    public static MasterStyle.Param offsetParam(int styleIndex) {
        return new MasterStyle.Param("exposure", exposureKey(styleIndex), "偏移量", " EV", 100, -400, 400, 0);
    }

    public static MasterStyle.Param wbTempParam(int styleIndex) {
        return new MasterStyle.Param(
            "wb_temp", wbTempKey(styleIndex), "色温偏移", " K", 1, -800, 800, 0,
            "冷调 (蓝) ← 0 → 暖调 (琥珀)，引擎内白点适应，不改变手机 AWB", 0);
    }

    public static MasterStyle.Param wbTintParam(int styleIndex) {
        return new MasterStyle.Param(
            "wb_tint", wbTintKey(styleIndex), "青品偏移", "", 1, -50, 50, 0,
            "青绿 ← 0 → 品红，引擎内白点适应，不改变手机 AWB", 0);
    }

    public static boolean isExposureKey(String key) {
        return hasKeyOrPrefix(key, ModuleSettings.MASTER_LOOK_EXPOSURE)
            || hasKeyOrPrefix(key, ModuleSettings.MASTER_ALIGN)
            || hasKeyOrPrefix(key, ModuleSettings.MASTER_EXPOSURE_MODE)
            || hasKeyOrPrefix(key, ModuleSettings.MASTER_WB_TEMP)
            || hasKeyOrPrefix(key, ModuleSettings.MASTER_WB_TINT);
    }

    private static boolean hasKeyOrPrefix(String key, String base) {
        if (base.equals(key)) return true;
        if (!key.startsWith(base + "_")) return false;
        String suffix = key.substring(base.length() + 1);
        if (suffix.isEmpty()) return false;
        for (int i = 0; i < suffix.length(); i++) {
            if (!Character.isDigit(suffix.charAt(i))) return false;
        }
        return true;
    }

    public static final int NONE = 0;
    public static final int GREY = 1;
    public static final int MID = 2;
    public static final int HIGH = 3;
    public static final int WHITE = 4;

    public static final String[] LABELS = {"不对齐", "中灰", "中间调", "高光", "白点"};
    public static final String[] SUMMARIES = {
        "保持风格原始曝光",
        "对齐 18% 中灰亮度",
        "对齐中间调平均明度",
        "对齐高光平均明度",
        "对齐 90% 漫反射白点",
    };

    private MasterAlign() {}

    /** True if the look is in the table at all (the stock look and the not covered variants are not). */
    public static boolean covered(String look) {
        return MasterAlignTable.OFFSETS.containsKey(look);
    }

    public static boolean available(String look, int mode) {
        if (mode == NONE) return true;
        float[] offsets = MasterAlignTable.OFFSETS.get(look);
        return offsets != null && mode <= offsets.length && !Float.isNaN(offsets[mode - 1]);
    }

    /**
     * The EV the engine adds to the scene. Offset mode: the offset (1/100 EV). Align mode: the alignment's offset.
     */
    public static float evFor(String look, int mode, int offsetCentiEv, int align) {
        float ev = mode == MODE_ALIGN ? offset(look, align) : Math.max(-400, Math.min(400, offsetCentiEv)) / 100f;
        return Math.max(-4f, Math.min(4f, Math.round(ev * 100f) / 100f));
    }

    /** EV of an alignment; 0 when the mode is NONE or the look does not offer it. */
    public static float offset(String look, int mode) {
        return available(look, mode) && mode != NONE ? MasterAlignTable.OFFSETS.get(look)[mode - 1] : 0f;
    }
}
