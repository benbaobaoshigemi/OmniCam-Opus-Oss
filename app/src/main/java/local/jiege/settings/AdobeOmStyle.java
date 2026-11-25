package local.jiege.settings;

/** Adobe Camera Matching OM System OM-1 Mark II presets (native style "adobe"; looks/adobe_om_<id>.bin). */
final class AdobeOmStyle extends MasterStyle {
    static final String[] ADOBE_OM_IDS = {
        "natural", "vivid", "muted", "portrait",
        "monotone", "monotone_or", "monotone_r", "monotone_g",
    };
    static final String[] ADOBE_OM_LABELS = {
        "Natural（自然）", "Vivid（浓郁）", "Muted（清淡）", "Portrait（人像）",
        "Monotone（单色）", "单色 + Or 橙滤镜", "单色 + R 红滤镜", "单色 + G 绿滤镜",
    };
    private static final Choice STYLE = new Choice("奥之心色彩", "master_look_adobe_om", ADOBE_OM_IDS, ADOBE_OM_LABELS, null, ALWAYS);

    AdobeOmStyle() {
        super(14, "奥之心", "Adobe Camera Raw 奥之心 OM-1 II 色彩模式");
    }

    @Override public Choice[] choices() {
        return new Choice[] {STYLE};
    }

    @Override public String lookId(IntLookup selections) {
        return "adobe_om_" + STYLE.id(selections);
    }
}
