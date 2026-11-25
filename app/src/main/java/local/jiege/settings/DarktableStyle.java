package local.jiege.settings;

/** darktable tone mappers (native style "darktable"; looks/sigmoid.bin, filmic.bin, agx.bin). */
final class DarktableStyle extends MasterStyle {
    private static final Choice MAPPER = new Choice("色调映射", "master_look_darktable",
        new String[] {"sigmoid", "filmic", "agx"}, new String[] {"Sigmoid", "Filmic", "AgX"}, null, ALWAYS);
    private static final Param[] SIGMOID = {
        new Param("contrast", "master_sigmoid_contrast", "对比度", "", 100, 100, 300, 150),
        new Param("hue", "master_sigmoid_hue", "色相保持", "%", 1, 0, 100, 100),
    };
    private static final Param[] FILMIC = {
        new Param("black", "master_filmic_black", "黑点", " EV", 100, -900, -400, -765),
        new Param("white", "master_filmic_white", "白点", " EV", 100, 200, 700, 456),
    };
    private static final Param[] AGX = {
        new Param("black", "master_agx_black", "黑点", " EV", 100, -1200, -400, -1000),
        new Param("white", "master_agx_white", "白点", " EV", 100, 400, 1000, 650),
        new Param("toe", "master_agx_toe", "趾部", "", 100, 100, 300, 150),
        new Param("shoulder", "master_agx_shoulder", "肩部", "", 100, 100, 500, 330),
    };

    DarktableStyle() {
        super(7, "Darktable", "darktable 色调映射");
    }

    @Override public Choice[] choices() {
        return new Choice[] {MAPPER};
    }

    @Override public Param[] allParams() {
        Param[] all = new Param[SIGMOID.length + FILMIC.length + AGX.length];
        System.arraycopy(SIGMOID, 0, all, 0, SIGMOID.length);
        System.arraycopy(FILMIC, 0, all, SIGMOID.length, FILMIC.length);
        System.arraycopy(AGX, 0, all, SIGMOID.length + FILMIC.length, AGX.length);
        return all;
    }

    @Override public String lookId(IntLookup selections) {
        return MAPPER.id(selections);
    }

    @Override public Param[] params(IntLookup selections) {
        switch (lookId(selections)) {
            case "sigmoid": return SIGMOID;
            case "filmic": return FILMIC;
            default: return AGX;
        }
    }
}
