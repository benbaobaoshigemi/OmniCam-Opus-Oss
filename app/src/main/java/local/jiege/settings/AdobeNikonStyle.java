package local.jiege.settings;

/** Adobe Camera Matching Nikon Z 8 Picture Controls and Creative Picture Controls (native style "adobe"; looks/adobe_nikon_<id>.bin). */
final class AdobeNikonStyle extends MasterStyle {
    static final String[] ADOBE_NIKON_IDS = {
        "standard", "neutral", "vivid", "portrait", "rich_tone_portrait", "landscape", "flat",
        "bleach", "blue", "denim", "dramatic", "dream", "melancholic", "morning", "pink", "pop", "pure", "red",
        "sepia", "silence", "somber", "sunday", "toy",
    };
    static final String[] ADOBE_NIKON_LABELS = {
        "Standard（标准）", "Neutral（中性）", "Vivid（鲜艳）", "Portrait（人像）", "Rich Tone Portrait（细腻人像）",
        "Landscape（风景）", "Flat（平面）",
        "Bleach（漂白）", "Blue（蓝调）", "Denim（牛仔）", "Dramatic（戏剧）", "Dream（梦幻）", "Melancholic（忧郁）",
        "Morning（清晨）", "Pink（粉调）", "Pop（流行）", "Pure（纯净）", "Red（红调）",
        "Sepia（棕褐）", "Silence（静默）", "Somber（沉郁）", "Sunday（周日）", "Toy（玩具）",
    };
    private static final Choice STYLE = new Choice("尼康 Picture Control", "master_look_adobe_nikon", ADOBE_NIKON_IDS, ADOBE_NIKON_LABELS, null, ALWAYS);

    AdobeNikonStyle() {
        super(16, "尼康", "Adobe Camera Raw 尼康 Z 8 Picture Control 与创意 Picture Control");
    }

    @Override public Choice[] choices() {
        return new Choice[] {STYLE};
    }

    @Override public String lookId(IntLookup selections) {
        return "adobe_nikon_" + STYLE.id(selections);
    }
}
