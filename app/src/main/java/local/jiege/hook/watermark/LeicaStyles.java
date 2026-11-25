package local.jiege.hook.watermark;

import android.graphics.Color;
import android.os.Build;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * Xiaomi HyperOS Leica Watermark style registry, presets, background mapping,
 * and metadata/expression formatters (matching {@code xiaomi_leica_watermark/models.py} 1:1).
 */
public final class LeicaStyles {
    public static final String STYLE_PREFIX = "personalize_leica_";

    public static final int[] STYLE_ORDER = {
        1, 2, 3, 4, 5, 67, 68, 69, 70, 88, 89, 90, 91, 92, 93, 94, 104, 111, 135
    };

    private static final Map<Integer, StylePreset> PRESETS;
    private static final Pattern NUMBER_PATTERN = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");
    private static final Pattern DATETIME_PATTERN =
            Pattern.compile("(\\d{4})[-/.:年\\s]+(\\d{1,2})[-/.:月\\s]+(\\d{1,2})(?:[日T\\s]+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?");

    static {
        Map<Integer, StylePreset> map = new HashMap<>();
        map.put(1, new StylePreset(1, "Leica Classic", "layout", "white", "location_latlng", true, true, true, false, true));
        map.put(2, new StylePreset(2, "Leica Limited", "layout", "white", "location_latlng_switch", true, true, true, false, true));
        map.put(3, new StylePreset(3, "LEICA THREE", "layout", "white", "location_off", true, true, false, false, true));
        map.put(4, new StylePreset(4, "LEICA FOUR", "layout", "white", "location_off", true, true, false, false, true));
        map.put(5, new StylePreset(5, "LEICA FIVE", "outer_border_right", "white", "location_off", true, true, false, false, true));
        map.put(67, new StylePreset(67, "LEICA SIX", "bottom_middle", "transparent_white", "location_latlng_switch", true, true, true, true, false));
        map.put(68, new StylePreset(68, "LEICA SEVEN", "bottom", "transparent_white", "location_latlng_switch", true, true, true, true, false));
        map.put(69, new StylePreset(69, "LEICA EIGHT", "layout", "white", "location_latlng_switch", true, true, true, false, true));
        map.put(70, new StylePreset(70, "LEICA NINE", "layout", "white", "location_latlng_switch", true, true, true, false, true));
        map.put(88, new StylePreset(88, "Leica 100-Year Full Frame", "layout", "white", "location_address", true, true, true, false, true));
        map.put(89, new StylePreset(89, "Leica 100-Year Side", "outer_border_right", "white", "location_address", true, true, true, false, true));
        map.put(90, new StylePreset(90, "Leica Editorial Red", "layout", "white", "location_latlng_switch", true, true, true, false, true));
        map.put(91, new StylePreset(91, "Leica Editorial Overlay", "center_top", "transparent_white", "location_latlng_switch", true, true, true, true, false));
        map.put(92, new StylePreset(92, "Leica Editorial Frame", "layout", "white", "location_latlng_switch", true, true, true, false, true));
        map.put(93, new StylePreset(93, "Leica Overlay Oxanium", "bottom", "transparent_white", "location_latlng_switch", true, true, true, true, false));
        map.put(94, new StylePreset(94, "Leica Monochrome Oxanium", "layout", "white", "location_latlng_switch", true, true, true, false, true));
        map.put(104, new StylePreset(104, "Leica Classic Long Name", "layout", "white", "location_latlng", true, true, true, false, true));
        map.put(111, new StylePreset(111, "Leica Black Gold", "layout", "black", "location_off", true, true, false, false, false));
        map.put(135, new StylePreset(135, "Leica Live Moment", "bottom", "transparent_white", "location_off", true, true, true, true, false));
        PRESETS = Collections.unmodifiableMap(map);
    }

    private LeicaStyles() {}

    public static final class StylePreset {
        public final int id;
        public final String name;
        public final String defaultLayout;
        public final String defaultBackground;
        public final String locationOption;
        public final boolean modelSwitch;
        public final boolean exifSwitch;
        public final boolean timeSwitch;
        public final boolean isOverlay;
        public final boolean supportsBgEdit;

        StylePreset(
                int id,
                String name,
                String defaultLayout,
                String defaultBackground,
                String locationOption,
                boolean modelSwitch,
                boolean exifSwitch,
                boolean timeSwitch,
                boolean isOverlay,
                boolean supportsBgEdit) {
            this.id = id;
            this.name = name;
            this.defaultLayout = defaultLayout;
            this.defaultBackground = defaultBackground;
            this.locationOption = locationOption;
            this.modelSwitch = modelSwitch;
            this.exifSwitch = exifSwitch;
            this.timeSwitch = timeSwitch;
            this.isOverlay = isOverlay;
            this.supportsBgEdit = supportsBgEdit;
        }
    }

    public static final class PhotoMeta {
        public final String marketName;
        public final double focalLengthMm;
        public final double aperture;
        public final String shutterSpeed;
        public final int iso;
        public final long takenAtMs;
        public final Double latitude;
        public final Double longitude;
        public final String address;
        public final String cvLens;
        public final String filterName;
        public final String customText;

        public PhotoMeta(
                String marketName,
                double focalLengthMm,
                double aperture,
                String shutterSpeed,
                int iso,
                long takenAtMs,
                Double latitude,
                Double longitude,
                String address,
                String cvLens,
                String filterName,
                String customText) {
            this.marketName = marketName != null && !marketName.isEmpty() ? marketName : Build.MODEL;
            this.focalLengthMm = focalLengthMm;
            this.aperture = aperture;
            this.shutterSpeed = shutterSpeed != null ? shutterSpeed : "";
            this.iso = iso;
            this.takenAtMs = takenAtMs;
            this.latitude = latitude;
            this.longitude = longitude;
            this.address = address != null ? address.trim() : "";
            this.cvLens = cvLens != null ? cvLens.trim() : "";
            this.filterName = filterName != null ? filterName.trim() : "";
            this.customText = customText != null ? customText.trim() : "";
        }
    }

    public static final class TextSpan {
        public final String text;
        public final int weight;

        public TextSpan(String text, int weight) {
            this.text = text;
            this.weight = weight;
        }
    }

    public static final class BgChoice {
        public final String name; // "white", "black", "light_blur", "dark_blur", "transparent_white", or "absorb"
        public final int customBgColor;
        public final boolean isBlur;
        public final boolean isLightBlur;

        public BgChoice(String name, int customBgColor, boolean isBlur, boolean isLightBlur) {
            this.name = name;
            this.customBgColor = customBgColor;
            this.isBlur = isBlur;
            this.isLightBlur = isLightBlur;
        }
    }

    public static boolean isLeicaStyle(String styleId) {
        return parseStyleId(styleId) >= 0;
    }

    public static int parseStyleId(String styleId) {
        if (styleId == null || !styleId.startsWith(STYLE_PREFIX)) return -1;
        String suffix = styleId.substring(STYLE_PREFIX.length());
        if (suffix.isEmpty()) return -1;
        for (int i = 0; i < suffix.length(); i++) {
            char c = suffix.charAt(i);
            if (c < '0' || c > '9') return -1;
        }
        try {
            int id = Integer.parseInt(suffix);
            return PRESETS.containsKey(id) ? id : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    public static StylePreset getPreset(String styleId) {
        return PRESETS.get(parseStyleId(styleId));
    }

    public static StylePreset getPreset(int id) {
        return PRESETS.get(id);
    }

    public static boolean isOverlayStyle(String styleId) {
        StylePreset p = getPreset(styleId);
        return p != null && p.isOverlay;
    }

    public static boolean supportsBgEdit(String styleId) {
        StylePreset p = getPreset(styleId);
        return p != null && p.supportsBgEdit;
    }

    /** Resolves OPPO style JSON background object into Leica engine background mode. */
    public static BgChoice resolveBackgroundChoice(StylePreset preset, JSONObject styleJson) {
        if (preset == null) {
            return new BgChoice("white", 0xFFFFFFFF, false, false);
        }
        if (preset.isOverlay) {
            return new BgChoice("transparent_white", 0x00FFFFFF, false, false);
        }
        if (!preset.supportsBgEdit) {
            int col = "black".equals(preset.defaultBackground) ? 0xFF141414 : 0xFFFFFFFF;
            return new BgChoice(preset.defaultBackground, col, false, false);
        }
        if (styleJson == null) {
            int col = "black".equals(preset.defaultBackground) ? 0xFF141414 : 0xFFFFFFFF;
            return new BgChoice(preset.defaultBackground, col, false, false);
        }
        JSONObject bg = styleJson.optJSONObject("background");
        if (bg == null) {
            return new BgChoice(preset.defaultBackground, 0xFFFFFFFF, false, false);
        }
        int bgType = bg.optInt("backgroundType", 0);
        String colorHex = bg.optString("color", "#FFFFFF");
        if (bgType == 2) {
            return new BgChoice("dark_blur", 0xFF141414, true, false);
        }
        if ("#FFFFFF".equalsIgnoreCase(colorHex) || "#FFFFFFFF".equalsIgnoreCase(colorHex)) {
            return new BgChoice("white", 0xFFFFFFFF, false, false);
        }
        if ("#141414".equalsIgnoreCase(colorHex)
                || "#FF141414".equalsIgnoreCase(colorHex)
                || "#1E1E1E".equalsIgnoreCase(colorHex)
                || "#FF1E1E1E".equalsIgnoreCase(colorHex)
                || "#000000".equalsIgnoreCase(colorHex)
                || "#FF000000".equalsIgnoreCase(colorHex)) {
            return new BgChoice("black", 0xFF141414, false, false);
        }
        try {
            int argb = Color.parseColor(colorHex);
            return new BgChoice("absorb", argb | 0xFF000000, false, false);
        } catch (IllegalArgumentException ignored) {
            return new BgChoice(preset.defaultBackground, 0xFFFFFFFF, false, false);
        }
    }

    public static PhotoMeta buildMetaFromStrings(
            String rawMarketName,
            String rawFocal35mm,
            String rawAperture,
            String rawShutter,
            String rawIso,
            String rawTime,
            Double lat,
            Double lon,
            String address,
            String cvLens,
            String filterName,
            String customText) {
        double focal = parsePositiveDouble(rawFocal35mm, 23.0);
        double aperture = parsePositiveDouble(rawAperture, 1.8);
        String shutter = formatShutterNoUnit(rawShutter);
        int iso = parsePositiveInt(rawIso, 100);
        long takenMs = parseTimestampMs(rawTime);
        String lens = (cvLens != null && !cvLens.trim().isEmpty()) ? cvLens.trim() : "Summilux";
        return new PhotoMeta(
                rawMarketName,
                focal,
                aperture,
                shutter,
                iso,
                takenMs,
                lat,
                lon,
                address,
                lens,
                filterName,
                customText);
    }

    public static String formatFocalStr(double focalMm) {
        if (Math.abs(focalMm - Math.round(focalMm)) < 1e-3) {
            return String.valueOf((int) Math.round(focalMm));
        }
        return stripTrailingZeros(String.format(Locale.US, "%.1f", focalMm));
    }

    public static String formatApertureStr(double aperture) {
        if (Math.abs(aperture - Math.round(aperture)) < 1e-3) {
            return String.valueOf((int) Math.round(aperture));
        }
        return stripTrailingZeros(String.format(Locale.US, "%.2f", aperture));
    }

    public static String formatShutterNoUnit(String rawShutter) {
        if (rawShutter == null) return "";
        String s = rawShutter.trim();
        if (s.isEmpty() || "-".equals(s) || "-s".equalsIgnoreCase(s)) return "";
        if ((s.endsWith("s") || s.endsWith("S")) && s.length() > 1) {
            s = s.substring(0, s.length() - 1).trim();
        }
        int slash = s.indexOf('/');
        if (slash > 0 && slash < s.length() - 1) {
            try {
                double num = Double.parseDouble(s.substring(0, slash).trim());
                double den = Double.parseDouble(s.substring(slash + 1).trim());
                if (num > 0 && den > 0) {
                    return formatShutterSeconds(num / den);
                }
            } catch (NumberFormatException ignored) {
            }
            return s;
        }
        try {
            double secs = Double.parseDouble(s);
            if (secs >= 1000.0) secs /= 1.0e9;
            if (Double.isFinite(secs) && secs > 0.0) {
                return formatShutterSeconds(secs);
            }
        } catch (NumberFormatException ignored) {
        }
        return s;
    }

    private static String formatShutterSeconds(double secs) {
        if (secs < 0.5) {
            long denom = Math.max(1L, Math.round(1.0 / secs));
            return "1/" + denom;
        }
        if (Math.abs(secs - Math.round(secs)) < 1e-2) {
            return String.valueOf(Math.round(secs));
        }
        return stripTrailingZeros(String.format(Locale.US, "%.1f", secs));
    }

    public static String formatExifText(String expr, PhotoMeta meta) {
        if (meta == null) return "";
        int eq = expr.indexOf('=');
        String out = eq >= 0 ? expr.substring(eq + 1) : expr;
        if (out.contains("@{focal}")) {
            if (meta.focalLengthMm <= 0) return "";
            out = out.replace("@{focal}", formatFocalStr(meta.focalLengthMm));
        }
        if (out.contains("@{aperture}")) {
            if (meta.aperture <= 0) return "";
            out = out.replace("@{aperture}", formatApertureStr(meta.aperture));
        }
        if (out.contains("@{speed}")) {
            if (meta.shutterSpeed.isEmpty()) return "";
            out = out.replace("@{speed}", meta.shutterSpeed);
        }
        if (out.contains("@{iso}")) {
            if (meta.iso <= 0) return "";
            out = out.replace("@{iso}", String.valueOf(meta.iso));
        }
        return out;
    }

    public static String formatLocationText(String expr, PhotoMeta meta, String locationOption) {
        if ("location_off".equals(locationOption) || meta == null) return "";
        boolean multiline = expr.contains("@LOC=oo");
        if (expr.contains("location_address") || "location_address".equals(locationOption)) {
            if (!meta.address.isEmpty()) return meta.address;
            if ("location_address".equals(locationOption)) return "";
        }
        if (meta.latitude != null && meta.longitude != null
                && Double.isFinite(meta.latitude) && Double.isFinite(meta.longitude)) {
            return formatLatLngDms(meta.latitude, meta.longitude, multiline);
        }
        return "";
    }

    public static String formatLatLngDms(double lat, double lon, boolean multiline) {
        String latS = dmsComponent(lat, "N", "S");
        String lonS = dmsComponent(lon, "E", "W");
        return multiline ? (latS + "\n" + lonS) : (latS + "  " + lonS);
    }

    private static String dmsComponent(double val, String pos, String neg) {
        String hem = val >= 0 ? pos : neg;
        double v = Math.abs(val);
        int deg = (int) v;
        double rem = (v - deg) * 60.0;
        int mins = (int) rem;
        int secs = (int) Math.round((rem - mins) * 60.0);
        if (secs >= 60) {
            secs -= 60;
            mins += 1;
        }
        if (mins >= 60) {
            mins -= 60;
            deg += 1;
        }
        return String.format(Locale.US, "%d°%02d'%02d\"%s", deg, mins, secs, hem);
    }

    public static String formatTimeText(
            String expr, PhotoMeta meta, Map<String, Map<String, String>> i18n, String locale) {
        int eq = expr.indexOf('=');
        String raw = eq >= 0 ? expr.substring(eq + 1) : expr;
        String pattern;
        if (raw.startsWith("@")) {
            String key = raw.substring(1);
            String found = null;
            if (i18n != null) {
                Map<String, String> entry = i18n.get(key);
                if (entry != null) {
                    found = entry.get(locale);
                    if (found == null || found.isEmpty()) found = entry.get("default");
                }
            }
            if (found != null && !found.isEmpty()) {
                pattern = found;
            } else if ("wm_time_yyyy_MM_dd".equals(key)) {
                pattern = "yyyy.MM.dd";
            } else if ("wm_time_HH_mm".equals(key)) {
                pattern = "HH:mm";
            } else {
                pattern = "yyyy.MM.dd HH:mm";
            }
        } else {
            pattern = raw;
        }
        long ts = meta != null && meta.takenAtMs > 0 ? meta.takenAtMs : System.currentTimeMillis();
        try {
            return new SimpleDateFormat(pattern, Locale.US).format(new Date(ts));
        } catch (IllegalArgumentException ignored) {
            return new SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.US).format(new Date(ts));
        }
    }

    public static TextSpan[] formatPureTextModelSpans(
            String marketName, int baseWeight, boolean textUpper) {
        String raw = marketName != null ? marketName.trim() : "";
        if (raw.isEmpty()) return new TextSpan[0];
        if (textUpper) raw = raw.toUpperCase(Locale.US);
        int space = raw.indexOf(' ');
        if (space <= 0) {
            return new TextSpan[] {new TextSpan(raw, baseWeight)};
        }
        String head = raw.substring(0, space);
        String tail = raw.substring(space + 1).trim();
        if (tail.isEmpty()) {
            return new TextSpan[] {new TextSpan(head, baseWeight)};
        }
        String hl = head.toLowerCase(Locale.US);
        String headDisplay = ("xiaomi".equals(hl) || "oppo".equals(hl) || "leica".equals(hl)
                || "poco".equals(hl) || "hasselblad".equals(hl))
                ? head.toUpperCase(Locale.US)
                : head;
        return new TextSpan[] {
            new TextSpan(headDisplay, baseWeight),
            new TextSpan(" " + tail, baseWeight),
        };
    }

    public static String formatFilterText(String expr, PhotoMeta meta, boolean hasselbladBadge) {
        if (meta == null || meta.filterName.isEmpty()) return "";
        int eq = expr.indexOf('=');
        String tpl = eq >= 0 ? expr.substring(eq + 1) : expr;
        if (hasselbladBadge) {
            tpl = tpl.replaceAll("\\bLEICA\\b", "HASSELBLAD");
        }
        String f = meta.filterName;
        return tpl.replace("${name}", f)
                .replace("${uppercase_name}", f.toUpperCase(Locale.US))
                .replace("${lowercase_name}", f.toLowerCase(Locale.US));
    }

    private static double parsePositiveDouble(String raw, double fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        Matcher m = NUMBER_PATTERN.matcher(raw);
        if (!m.find()) return fallback;
        try {
            double v = Double.parseDouble(m.group());
            return Double.isFinite(v) && v > 0.0 ? v : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int parsePositiveInt(String raw, int fallback) {
        if (raw == null || raw.isEmpty()) return fallback;
        Matcher m = Pattern.compile("\\d+").matcher(raw);
        if (!m.find()) return fallback;
        try {
            int v = Integer.parseInt(m.group());
            return v > 0 ? v : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    @SuppressWarnings("deprecation")
    private static long parseTimestampMs(String raw) {
        if (raw == null || raw.trim().isEmpty()) return System.currentTimeMillis();
        String s = raw.trim();
        Matcher m = DATETIME_PATTERN.matcher(s);
        if (m.find()) {
            try {
                int y = Integer.parseInt(m.group(1));
                int mo = Integer.parseInt(m.group(2));
                int d = Integer.parseInt(m.group(3));
                int h = m.group(4) != null ? Integer.parseInt(m.group(4)) : 12;
                int mi = m.group(5) != null ? Integer.parseInt(m.group(5)) : 0;
                int se = m.group(6) != null ? Integer.parseInt(m.group(6)) : 0;
                Date dt = new Date(y - 1900, mo - 1, d, h, mi, se);
                return dt.getTime();
            } catch (Throwable ignored) {
            }
        }
        return System.currentTimeMillis();
    }

    private static String stripTrailingZeros(String s) {
        int dot = s.indexOf('.');
        if (dot < 0) return s;
        int end = s.length();
        while (end > dot && s.charAt(end - 1) == '0') end--;
        if (end == dot + 1) end = dot;
        return s.substring(0, end);
    }
}
