package local.jiege.settings;

/** The stock master colour of a phone model: this phone's own, or the Find X7 / X8 Ultra's (native style "stock"). */
final class StockStyle extends MasterStyle {
    private static final Choice MODEL = new Choice("机型", "master_look_oppo", new String[] {"oppo", "x8u", "x7u"},
        new String[] {"Find X9 Ultra", "Find X8 Ultra", "Find X7 Ultra"}, null, ALWAYS);

    StockStyle() {
        super(0, "OPPO", "原厂大师模式色彩");
    }

    @Override public Choice[] choices() {
        return new Choice[] {MODEL};
    }

    @Override public String lookId(IntLookup selections) {
        return MODEL.id(selections);
    }

    @Override public boolean blendable() {
        return false;
    }
}
