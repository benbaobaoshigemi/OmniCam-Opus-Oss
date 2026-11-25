package local.jiege.settings;

/** Canon EOS R5 picture styles (native style "canon"; looks/canon_<id>.bin). */
final class CanonStyle extends MasterStyle {
    static final String[] CANON_IDS = {"standard", "portrait", "landscape", "neutral", "faithful", "monochrome"};
    static final String[] CANON_LABELS = {"标准", "人像", "风光", "中性", "可靠设置", "单色"};
    private static final Choice STYLE = new Choice("照片风格", "master_look_canon", CANON_IDS, CANON_LABELS, null, ALWAYS);

    CanonStyle() {
        super(4, "Canon", "佳能 EOS R5 照片风格");
    }

    @Override public Choice[] choices() {
        return new Choice[] {STYLE};
    }

    @Override public String lookId(IntLookup selections) {
        return "canon_" + STYLE.id(selections);
    }
}
