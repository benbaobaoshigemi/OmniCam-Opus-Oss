package local.jiege.settings;

/** The scene front stage followed by a plain transfer curve (native style "basic"). */
final class BasicStyle extends MasterStyle {
    static final String[] BASIC_IDS = {"standard", "passthrough", "rec709", "gamma"};
    private static final Choice CURVE = new Choice("传输曲线", "master_look_basic", BASIC_IDS,
        new String[] {"标准", "直通", "Rec.709", "Gamma"},
        new String[] {
            "Adobe ACR3 默认影调，保持色彩比例",
            "仅线性到显示转换，无影调压缩",
            "Rec.709 视频伽马，暗部更沉",
            "纯幂函数曲线，默认 2.4",
        }, ALWAYS);
    private static final Param[] GAMMA = {new Param("gamma", "master_basic_gamma", "Gamma 值", "", 100, 100, 400, 240)};

    BasicStyle() {
        super(10, "基础", "场景色彩经 Adobe Standard 标定后，只套一条传输曲线");
    }

    @Override public Choice[] choices() {
        return new Choice[] {CURVE};
    }

    @Override public Param[] allParams() {
        return GAMMA;
    }

    @Override public String lookId(IntLookup selections) {
        return "basic_" + CURVE.id(selections);
    }

    @Override public Param[] params(IntLookup selections) {
        return "basic_gamma".equals(lookId(selections)) ? GAMMA : NO_PARAMS;
    }
}
