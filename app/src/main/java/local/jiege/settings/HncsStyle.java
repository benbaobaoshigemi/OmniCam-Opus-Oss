package local.jiege.settings;

/** Hasselblad natural colour: official Phocus presets, Old Standard film curve, Reproduction, and Phocus CFilmGrainFilter capture grain. */
public final class HncsStyle extends MasterStyle {
    static final String[] HNCS_IDS = {
        "standard", "nature", "saturated", "extra_contrast", "lighten", "old_standard", "reproduction",
    };
    static final String[] HNCS_LABELS = {
        "标准", "自然", "高饱和", "高反差", "亮调", "经典标准", "翻拍",
    };
    static final String[] HNCS_SUMMARIES = {
        "Phocus 默认标准色彩与 HDR 影调",
        "S 曲线增强明暗对比（Nature）",
        "整体色彩饱和度柔和提升（Saturated）",
        "高光与反差层次增强（Extra contrast）",
        "提亮中间调同时保留高光细节（Lighten）",
        "初代哈苏胶片响应曲线（Old Standard）",
        "线性影调与高保真色彩还原（Reproduction）",
    };
    private static final Choice LOOK = new Choice(
        "色彩风格", "master_hncs_look", HNCS_IDS, HNCS_LABELS, HNCS_SUMMARIES, ALWAYS);

    public static final String GRAIN_PRESET_KEY = "master_hncs_grain_preset";
    public static final String GRAIN_TYPE_KEY = "master_hncs_grain_type";
    public static final String GRAIN_AMOUNT_KEY = "master_hncs_grain_amount";
    public static final String GRAIN_SIZE_KEY = "master_hncs_grain_size";
    public static final String GRAIN_ROUGHNESS_KEY = "master_hncs_grain_roughness";
    public static final String GRAIN_COLOR_KEY = "master_hncs_grain_color";

    public static final int GRAIN_PRESET_OFF = 0;
    public static final int GRAIN_PRESET_CUSTOM = 9;

    private static final String[] GRAIN_PRESET_IDS = {
        "off", "medium_iso", "high_iso", "slide_medium_iso", "slide_high_iso",
        "fine_texture", "rough_color", "tabular_grain", "artistic", "custom",
    };
    private static final String[] GRAIN_PRESET_LABELS = {
        "关闭", "Medium ISO", "High ISO", "Slide Medium ISO", "Slide High ISO",
        "Fine Texture", "Rough Color", "Tabular Grain", "Artistic", "自定义",
    };
    private static final String[] GRAIN_PRESET_SUMMARIES = {
        "不添加胶片颗粒（默认）",
        "中等感光度标准颗粒 · 强度 60 · 大小 40 · 粗糙度 15",
        "高感光度强银盐颗粒 · 强度 100 · 大小 50 · 粗糙度 30",
        "中速反转片微彩颗粒 · 强度 70 · 大小 60 · 彩噪 15",
        "高速反转片鲜明彩颗粒 · 强度 100 · 大小 75 · 粗糙度 50 · 彩噪 15",
        "高光倾向精细平滑纹理 · 强度 40 · 大小 10 · 粗糙度 5",
        "粗粝强彩色颗粒 · 强度 100 · 大小 75 · 粗糙度 100 · 彩噪 25",
        "扁平 T 颗粒乳剂质感 · 强度 39 · 大小 77 · 粗糙度 29",
        "柔和艺术大颗粒 · 强度 80 · 大小 80 · 粗糙度 80 · 彩噪 15",
        "使用下方自定义颗粒参数",
    };

    private static final String[] GRAIN_TYPE_IDS = {"standard", "fine", "soft"};
    private static final String[] GRAIN_TYPE_LABELS = {"标准 (Type 1)", "高光倾向 (Type 0)", "艺术柔化 (Type 2)"};
    private static final String[] GRAIN_TYPE_SUMMARIES = {
        "四阶谐波与柔和暗部响应（Medium / High ISO / Slide 默认）",
        "线性振幅与强高光衰减（Fine Texture / Rough Color / Tabular）",
        "三阶均匀尺度大颗粒（Artistic）",
    };
    // Maps UI choice index (default 0 first) to Phocus CFilmGrainFilter internal FilmGrainType integer.
    private static final int[] GRAIN_TYPE_CPP = {1, 0, 2};

    /** Official Phocus 4.1.1 Film Grain preset tuples: {typeChoiceIdx, amount, size, roughness, color}. */
    public static final int[][] GRAIN_PRESET_VALUES = {
        {0, 40, 40, 15, 0},    // off (Default.xml)
        {0, 60, 40, 15, 0},    // Medium ISO.xml (Type 1)
        {0, 100, 50, 30, 0},   // High ISO.xml (Type 1)
        {0, 70, 60, 15, 15},   // Slide Medium ISO.xml (Type 1)
        {0, 100, 75, 50, 15},  // Slide High ISO.xml (Type 1)
        {1, 40, 10, 5, 0},     // Fine Texture.xml (Type 0)
        {1, 100, 75, 100, 25}, // Rough Color.xml (Type 0)
        {1, 39, 77, 29, 0},    // Tabular Grain.xml (Type 0)
        {2, 80, 80, 80, 15},   // Artistic.xml (Type 2)
    };

    public static final Choice GRAIN_PRESET = new Choice(
        "颗粒预设", GRAIN_PRESET_KEY, GRAIN_PRESET_IDS, GRAIN_PRESET_LABELS, GRAIN_PRESET_SUMMARIES, ALWAYS);
    public static final Choice GRAIN_TYPE = new Choice(
        "颗粒类型", GRAIN_TYPE_KEY, GRAIN_TYPE_IDS, GRAIN_TYPE_LABELS, GRAIN_TYPE_SUMMARIES, ALWAYS);

    public static final Param GRAIN_AMOUNT = new Param(
        "grain_amount", GRAIN_AMOUNT_KEY, "颗粒强度", "", 1, 0, 100, 40, "Phocus Amount（0–100）");
    public static final Param GRAIN_SIZE = new Param(
        "grain_size", GRAIN_SIZE_KEY, "颗粒大小", "", 1, 1, 100, 40, "Phocus Size，控制谐波尺度分布与高光扩展（1–100）");
    public static final Param GRAIN_ROUGHNESS = new Param(
        "grain_roughness", GRAIN_ROUGHNESS_KEY, "粗糙度", "", 1, 0, 100, 15, "Phocus Roughness，USM 锐度增强颗粒边缘（0–100）");
    public static final Param GRAIN_COLOR = new Param(
        "grain_color", GRAIN_COLOR_KEY, "彩色颗粒", "", 1, 0, 100, 0, "Phocus Color，0 为纯亮度银盐，大于 0 叠加 YCoCg 色度颗粒");

    public static final Param[] GRAIN_PARAMS = {GRAIN_AMOUNT, GRAIN_SIZE, GRAIN_ROUGHNESS, GRAIN_COLOR};

    HncsStyle() {
        super(1, "X2D", "哈苏自然色彩解决方案");
    }

    @Override public Choice[] choices() {
        return new Choice[] {LOOK, GRAIN_PRESET, GRAIN_TYPE};
    }

    @Override public Param[] allParams() {
        return GRAIN_PARAMS;
    }

    @Override public String lookId(IntLookup selections) {
        String id = LOOK.id(selections);
        return id.equals(HNCS_IDS[0]) ? "hncs" : "hncs_" + id;
    }

    /**
     * Resolves active HNCS capture grain parameters:
     * {enabled (0/1), cppType (0/1/2), amount (0..100), size (1..100), roughness (0..100), color (0..100)}.
     */
    public static int[] resolveGrain(IntLookup s) {
        int preset = clamp(s.get(GRAIN_PRESET_KEY), 0, GRAIN_PRESET_CUSTOM);
        if (preset == GRAIN_PRESET_OFF) {
            return new int[] {0, GRAIN_TYPE_CPP[0], 0, 40, 15, 0};
        }
        int typeIdx;
        int amount;
        int size;
        int roughness;
        int color;
        if (preset < GRAIN_PRESET_CUSTOM && preset < GRAIN_PRESET_VALUES.length) {
            int[] p = GRAIN_PRESET_VALUES[preset];
            typeIdx = p[0];
            amount = p[1];
            size = p[2];
            roughness = p[3];
            color = p[4];
        } else {
            typeIdx = clamp(s.get(GRAIN_TYPE_KEY), 0, GRAIN_TYPE_CPP.length - 1);
            amount = clamp(s.get(GRAIN_AMOUNT_KEY), 0, 100);
            size = clamp(s.get(GRAIN_SIZE_KEY), 1, 100);
            roughness = clamp(s.get(GRAIN_ROUGHNESS_KEY), 0, 100);
            color = clamp(s.get(GRAIN_COLOR_KEY), 0, 100);
        }
        int enabled = amount > 0 ? 1 : 0;
        return new int[] {enabled, GRAIN_TYPE_CPP[typeIdx], amount, size, roughness, color};
    }

    /** True only when the active style is HNCS (and blend hasn't dropped both weights to 0) with grain enabled. */
    public static boolean isGrainActive(MasterStyle style, boolean blend, int tonePercent, int colourPercent, IntLookup s) {
        if (!(style instanceof HncsStyle)) return false;
        if (blend && tonePercent <= 0 && colourPercent <= 0) return false;
        return resolveGrain(s)[0] != 0;
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }
}
