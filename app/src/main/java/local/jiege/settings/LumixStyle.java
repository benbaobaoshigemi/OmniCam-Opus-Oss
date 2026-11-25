package local.jiege.settings;

/** Panasonic Lumix S9 photo styles (native style "lumix"; looks/lumix_<id>.bin). */
final class LumixStyle extends MasterStyle {
    static final String[] LUMIX_IDS = {
        "standard", "vivid", "natural", "landscape", "portrait", "l_classicneo", "709like",
        "monochrome", "l_monochrome", "l_monochrome_d", "l_monochrome_s", "leica_monochrome", "flat", "cinelike_d2", "cinelike_v2",
    };
    static final String[] LUMIX_LABELS = {
        "标准", "鲜艳", "自然", "风光", "人像", "L.ClassicNeo", "709 Like",
        "单色", "L.单色", "L.单色 D", "L.单色 S", "徕卡单色", "Flat", "Cinelike D2", "Cinelike V2",
    };
    private static final Choice STYLE = new Choice("照片风格", "master_look_lumix", LUMIX_IDS, LUMIX_LABELS, null, ALWAYS);

    LumixStyle() {
        super(2, "Lumix", "松下 S9 照片风格");
    }

    @Override public Choice[] choices() {
        return new Choice[] {STYLE};
    }

    @Override public String lookId(IntLookup selections) {
        return "lumix_" + STYLE.id(selections);
    }
}
