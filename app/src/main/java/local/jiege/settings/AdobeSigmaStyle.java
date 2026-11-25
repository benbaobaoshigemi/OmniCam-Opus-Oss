package local.jiege.settings;

/** Adobe Camera Matching Sigma fp presets (native style "adobe"; looks/adobe_sigma_<id>.bin). */
final class AdobeSigmaStyle extends MasterStyle {
    static final String[] ADOBE_SIGMA_IDS = {
        "teal_orange", "cinema", "powder_blue", "fov_blue", "fov_yellow",
        "forest_green", "sunset_red", "warm_gold",
        "standard", "vivid", "neutral", "monochrome",
    };
    static final String[] ADOBE_SIGMA_LABELS = {
        "Teal and Orange（青橙）", "Cinema（电影）", "Powder Blue（粉蓝）", "FOV Classic Blue", "FOV Classic Yellow",
        "Forest Green（森绿）", "Sunset Red（落日红）", "Warm Gold（暖金）",
        "Standard（标准）", "Vivid（鲜艳）", "Neutral（中性）", "Monochrome（黑白）",
    };
    private static final Choice STYLE = new Choice("适马色彩", "master_look_adobe_sigma", ADOBE_SIGMA_IDS, ADOBE_SIGMA_LABELS, null, ALWAYS);

    AdobeSigmaStyle() {
        super(13, "适马", "Adobe Camera Raw 适马 fp 电影感与 Foveon 色彩");
    }

    @Override public Choice[] choices() {
        return new Choice[] {STYLE};
    }

    @Override public String lookId(IntLookup selections) {
        return "adobe_sigma_" + STYLE.id(selections);
    }
}
