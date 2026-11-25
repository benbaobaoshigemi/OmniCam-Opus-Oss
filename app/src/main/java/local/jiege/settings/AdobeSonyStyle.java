package local.jiege.settings;

/** Adobe Camera Matching Sony Creative Look presets (native style "adobe"; looks/adobe_sony_<id>.bin). */
final class AdobeSonyStyle extends MasterStyle {
    static final String[] ADOBE_SONY_IDS = {
        "st", "vv", "vv2", "fl", "in", "sh", "nt", "pt", "bw",
    };
    static final String[] ADOBE_SONY_LABELS = {
        "ST（标准）", "VV（鲜艳）", "VV2（鲜艳 2）", "FL（胶片）", "IN（即影）",
        "SH（柔和）", "NT（中性）", "PT（人像）", "BW（黑白）",
    };
    private static final Choice STYLE = new Choice("索尼创意外观", "master_look_adobe_sony", ADOBE_SONY_IDS, ADOBE_SONY_LABELS, null, ALWAYS);

    AdobeSonyStyle() {
        super(15, "索尼", "Adobe Camera Raw 索尼创意外观（Creative Look）");
    }

    @Override public Choice[] choices() {
        return new Choice[] {STYLE};
    }

    @Override public String lookId(IntLookup selections) {
        return "adobe_sony_" + STYLE.id(selections);
    }
}
