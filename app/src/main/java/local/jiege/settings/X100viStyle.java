package local.jiege.settings;

/** Fujifilm X100VI film simulations, tables from the X-T5 v4.31 / X100VI v1.31 firmware (native style "x100vi"; looks/x100vi_<id>.bin). */
public final class X100viStyle extends MasterStyle {
    static final String[] X100VI_IDS = {
        "provia", "velvia", "astia", "classic_chrome", "reala_ace", "pro_neg_hi", "pro_neg_std",
        "classic_neg", "nostalgic_neg", "eterna", "bleach_bypass",
        "acros", "acros_ye", "acros_r", "acros_g",
        "monochrome", "monochrome_ye", "monochrome_r", "monochrome_g", "sepia",
    };
    static final String[] X100VI_LABELS = {
        "PROVIA / 标准", "Velvia / 鲜艳", "ASTIA / 柔和", "经典正片 Classic Chrome", "REALA ACE",
        "PRO Neg. Hi", "PRO Neg. Std", "经典负片 Classic Neg.", "怀旧负片 Nostalgic Neg.",
        "ETERNA / 影院", "ETERNA 漂白效果",
        "ACROS", "ACROS + Ye 滤镜", "ACROS + R 滤镜", "ACROS + G 滤镜",
        "黑白", "黑白 + Ye 滤镜", "黑白 + R 滤镜", "黑白 + G 滤镜", "棕褐色 Sepia",
    };
    private static final Choice STYLE = new Choice("胶片模拟", "master_look_x100vi", X100VI_IDS, X100VI_LABELS, null, ALWAYS);

    public static final String GRAIN_STRENGTH_KEY = "master_x100vi_grain_strength";
    public static final String GRAIN_SIZE_KEY = "master_x100vi_grain_size";
    public static final int GRAIN_OFF = 0;

    public static final Choice GRAIN_STRENGTH = new Choice(
        "颗粒效果",
        GRAIN_STRENGTH_KEY,
        new String[] {"off", "weak", "strong"},
        new String[] {"关", "弱", "强"},
        new String[] {"不添加胶片颗粒（默认）", "轻微银盐颗粒质感", "强烈银盐颗粒质感"},
        ALWAYS);
    public static final Choice GRAIN_SIZE = new Choice(
        "颗粒大小",
        GRAIN_SIZE_KEY,
        new String[] {"small", "large"},
        new String[] {"小", "大"},
        new String[] {"细密均匀的小颗粒", "粗犷明显的胶片大颗粒"},
        ALWAYS);
    private static final Param[] PARAMS = {
        new Param("highlight", "master_x100vi_highlight", "高光色调", "", 10, -20, 40, 0, "", 5),
        new Param("shadow", "master_x100vi_shadow", "阴影色调", "", 10, -20, 40, 0, "", 5),
        new Param("wb_r", "master_x100vi_wb_r", "白平衡偏移 R", "", 1, -9, 9, 0, "", 1),
        new Param("wb_b", "master_x100vi_wb_b", "白平衡偏移 B", "", 1, -9, 9, 0, "", 1),
        new Param("color", "master_x100vi_color", "色彩", "", 1, -4, 4, 0, "", 1),
        new Param("color_chrome", "master_x100vi_color_chrome", "彩色效果", "", 1, 0, 2, 0,
            "压暗高饱和色阶保留立体质感（0 关 · +1 弱 · +2 强）", 1),
        new Param("color_chrome_blue", "master_x100vi_color_chrome_blue", "彩色 FX 蓝", "", 1, 0, 2, 0,
            "加深高饱和蓝与青色阶调层次（0 关 · +1 弱 · +2 强）", 1),
        new Param("dr", "master_x100vi_dr", "动态范围", "", 1, 0, 1, 0,
            "高光滚降（0 DR100 · 1 DR200）；DR200 压高光、阴影与中间调不变", 1),
    };
    private static final Param[] MONO_PARAMS = {PARAMS[0], PARAMS[1], PARAMS[2], PARAMS[3], PARAMS[7]};

    X100viStyle() {
        super(11, "X100VI", "富士 X100VI 原厂胶片模拟色彩引擎");
    }

    @Override public Choice[] choices() {
        return new Choice[] {STYLE, GRAIN_STRENGTH, GRAIN_SIZE};
    }

    @Override public Param[] allParams() {
        return PARAMS;
    }

    /** Chroma is a colour-film setting: the black-and-white and sepia simulations have none. */
    @Override public Param[] params(IntLookup selections) {
        String id = STYLE.id(selections);
        return id.startsWith("acros") || id.startsWith("monochrome") || id.equals("sepia") ? MONO_PARAMS : PARAMS;
    }

    @Override public String lookId(IntLookup selections) {
        return "x100vi_" + STYLE.id(selections);
    }

    /** Resolves active X100VI grain parameters: {strength (0=off,1=weak,2=strong), size (0=small,1=large), isAcros (0/1)}. */
    public static int[] resolveGrain(IntLookup s) {
        int strength = clamp(s.get(GRAIN_STRENGTH_KEY), 0, 2);
        int size = clamp(s.get(GRAIN_SIZE_KEY), 0, 1);
        int isAcros = STYLE.id(s).startsWith("acros") ? 1 : 0;
        return new int[] {strength, size, isAcros};
    }

    /** True only when the active style is X100VI (and blend hasn't dropped both weights to 0) with grain enabled. */
    public static boolean isGrainActive(MasterStyle style, boolean blend, int tonePercent, int colourPercent, IntLookup s) {
        if (!(style instanceof X100viStyle)) return false;
        if (blend && tonePercent <= 0 && colourPercent <= 0) return false;
        return resolveGrain(s)[0] != GRAIN_OFF;
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }
}
