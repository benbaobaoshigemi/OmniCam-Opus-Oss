package local.jiege.hook.watermark;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hasselblad Phocus Mobile 1.2.0 style specifications, vector assets, and EXIF metadata formatters.
 *
 * <p>Matches {@code phocus_watermark/models.py} and {@code phocus_watermark/vector_rasterizer.py} 1:1.
 */
public final class PhocusStyles {
    public static final String STYLE_PREFIX = "personalize_more_phocus_";

    /** Display order of the 12 supported Phocus styles (Style 0 signature-only excluded). */
    public static final int[] STYLE_ORDER = {8, 9, 14, 13, 15, 12, 6, 7, 1, 2, 3, 4};

    /** Exact XCD lens lookup table from Phocus Mobile ({@code s7.S.f24657a}). */
    public static final Map<String, String> XCD_LENS_MAP;

    public static final VectorSpec VEC_HASSELBLAD_LOGO;
    public static final VectorSpec VEC_HASSELBLAD_LOGO_ORANGE;
    public static final VectorSpec VEC_HBLACK_LOGO;
    public static final VectorSpec VEC_HWHITE_LOGO;

    private static final Map<Integer, StyleSpec> STYLE_SPECS;
    private static final Pattern OFFSET_PATTERN = Pattern.compile("^([+-])(\\d{2}):(\\d{2})$");
    private static final Pattern YMD_PATTERN = Pattern.compile("(\\d{4})[-/.:年\\s](\\d{1,2})[-/.:月\\s](\\d{1,2})");
    private static final Pattern SVG_TOKEN_PATTERN =
        Pattern.compile("[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\\d+\\.\\d*|\\.\\d+|\\d+)(?:[eE][-+]?\\d+)?");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy MM dd", Locale.US);

    private static final String PATH_HASSELBLAD_WORDMARK =
        "M32.27,2.12C32.27,2.12 27.73,9.99 27.73,9.99C27.73,9.99 27.75,9.99 27.75,9.99C27.75,9.99 24.17,9.99 24.17,9.99"
            + "C24.17,9.99 23.78,11.14 23.78,11.14C23.78,11.14 27.06,11.14 27.06,11.14C27.06,11.14 24.34,15.84 24.34,15.84"
            + "C24.34,15.84 26.1,15.84 26.1,15.84C26.1,15.84 28.82,11.14 28.82,11.14C28.82,11.14 28.81,11.14 28.81,11.14"
            + "C28.81,11.14 32.84,11.14 32.84,11.14C32.84,11.14 33.7,15.84 33.7,15.84C33.7,15.84 36.96,15.84 36.96,15.84"
            + "C36.96,15.84 34.44,2.12 34.44,2.12C34.44,2.12 32.27,2.12 32.27,2.12ZM29.49,9.99C29.49,9.99 29.5,9.98 29.5,9.98"
            + "C29.5,9.98 31.88,5.86 31.88,5.86C31.88,5.86 32.64,9.99 32.64,9.99C32.64,9.99 29.49,9.99 29.49,9.99"
            + "C29.49,9.99 29.49,9.99 29.49,9.99ZM78.63,8.5C78.63,8.5 78.64,8.5 78.64,8.5C78.64,8.5 76.63,8.5 76.63,8.5"
            + "C76.63,8.5 76.25,9.65 76.25,9.65C76.25,9.65 78.26,9.65 78.26,9.65C78.26,9.65 78.28,9.65 78.28,9.65"
            + "C78.28,9.65 76.32,15.84 76.32,15.84C76.32,15.84 85.32,15.84 85.32,15.84C85.32,15.84 85.7,14.57 85.7,14.57"
            + "C85.7,14.57 80.15,14.57 80.15,14.57C80.15,14.57 81.71,9.65 81.71,9.65C81.71,9.65 85.32,9.65 85.32,9.65"
            + "C85.32,9.65 85.7,8.5 85.7,8.5C85.7,8.5 82.08,8.5 82.08,8.5C82.08,8.5 83.72,3.33 83.72,3.33"
            + "C83.72,3.33 89.31,3.33 89.31,3.33C89.31,3.33 89.66,2.11 89.66,2.11C89.66,2.11 80.66,2.11 80.66,2.11"
            + "C80.66,2.11 78.63,8.5 78.63,8.5ZM101.9,2.12C101.9,2.12 98.58,2.12 98.58,2.12C98.58,2.12 94.24,15.85 94.24,15.85"
            + "C94.24,15.85 102.19,15.85 102.19,15.85C102.19,15.85 102.58,14.63 102.58,14.63C102.58,14.63 97.95,14.63 97.95,14.63"
            + "C97.95,14.63 101.9,2.12 101.9,2.12ZM138.32,2.12C138.32,2.12 135.01,2.12 135.01,2.12"
            + "C135.01,2.12 130.67,15.85 130.67,15.85C130.67,15.85 138.62,15.85 138.62,15.85C138.62,15.85 139.01,14.63 139.01,14.63"
            + "C139.01,14.63 134.37,14.63 134.37,14.63C134.37,14.63 138.32,2.12 138.32,2.12ZM154.15,2.12"
            + "C154.15,2.12 149.61,9.99 149.61,9.99C149.61,9.99 149.64,9.99 149.64,9.99C149.64,9.99 146.06,9.99 146.06,9.99"
            + "C146.06,9.99 145.66,11.14 145.66,11.14C145.66,11.14 148.95,11.14 148.95,11.14C148.95,11.14 146.24,15.84 146.24,15.84"
            + "C146.24,15.84 147.99,15.84 147.99,15.84C147.99,15.84 150.71,11.14 150.71,11.14C150.71,11.14 150.7,11.14 150.7,11.14"
            + "C150.7,11.14 154.73,11.14 154.73,11.14C154.73,11.14 155.59,15.84 155.59,15.84C155.59,15.84 158.85,15.84 158.85,15.84"
            + "C158.85,15.84 156.32,2.11 156.32,2.11C156.32,2.11 154.15,2.11 154.15,2.11C154.15,2.11 154.15,2.12 154.15,2.12"
            + "ZM151.37,9.99C151.37,9.99 153.76,5.85 153.76,5.85C153.76,5.85 154.51,9.99 154.51,9.99"
            + "C154.51,9.99 151.37,9.99 151.37,9.99ZM13.99,8.63C13.99,8.63 8.94,8.63 8.94,8.63C8.94,8.63 11,2.12 11,2.12"
            + "C11,2.12 7.74,2.12 7.74,2.12C7.74,2.12 5.68,8.63 5.68,8.63C5.68,8.63 2.37,8.63 2.37,8.63C2.37,8.63 2,9.84 2,9.84"
            + "C2,9.84 5.3,9.84 5.3,9.84C5.3,9.84 3.4,15.85 3.4,15.85C3.4,15.85 6.66,15.85 6.66,15.85C6.66,15.85 8.56,9.84 8.56,9.84"
            + "C8.56,9.84 13.6,9.84 13.6,9.84C13.6,9.84 11.7,15.85 11.7,15.85C11.7,15.85 14.96,15.85 14.96,15.85"
            + "C14.96,15.85 19.3,2.12 19.3,2.12C19.3,2.12 16.04,2.12 16.04,2.12C16.04,2.12 13.99,8.63 13.99,8.63ZM53.12,4.15"
            + "C53.12,4.15 53.81,3 53.81,3C47.37,-0.57 42.72,6.31 47.29,9.34C48.06,9.87 49.12,10.29 50.25,11.02"
            + "C52.97,12.83 48.9,16.86 44.61,13.25C44.61,13.25 43.72,14.4 43.72,14.4C46.99,17.24 52.4,16.23 53.4,11.91"
            + "C53.74,10.41 53.33,9.19 52.09,8.3C50.87,7.42 50.13,7.18 49.18,6.68C45.87,5.23 49.12,1.51 53.12,4.15ZM68.8,4.15"
            + "C68.8,4.15 69.48,3 69.48,3C63.04,-0.57 58.4,6.31 62.96,9.34C63.73,9.87 64.8,10.29 65.92,11.02"
            + "C68.65,12.83 64.57,16.86 60.28,13.25C60.28,13.25 59.4,14.4 59.4,14.4C62.66,17.24 68.07,16.23 69.07,11.91"
            + "C69.42,10.41 69,9.19 67.76,8.3C66.54,7.42 65.81,7.18 64.85,6.68C61.54,5.23 64.81,1.51 68.8,4.15ZM119.06,2.12"
            + "C115.04,2.13 114.59,2.12 114.59,2.12C114.59,2.12 112.48,8.44 112.48,8.44C112.48,8.44 111.16,8.44 111.16,8.44"
            + "C111.16,8.44 110.35,8.44 110.35,8.44C110.35,8.44 109.93,9.69 109.93,9.69C109.93,9.69 111.99,9.69 111.99,9.69"
            + "C111.99,9.69 112.06,9.69 112.06,9.69C112.06,9.69 110.41,14.59 110.41,14.59C110.41,14.59 109.99,15.85 109.99,15.85"
            + "C109.99,15.85 116.12,15.85 116.12,15.85C122.41,15.74 124.58,10.03 119.82,8.93C125.3,7.25 124.58,2.11 119.06,2.12"
            + "ZM116.15,14.63C116.15,14.63 115,14.63 115,14.63C115,14.63 113.83,14.62 113.83,14.62"
            + "C113.83,14.62 115.44,9.68 115.44,9.68C115.44,9.68 117.38,9.68 117.38,9.68C121.23,9.72 118.64,14.6 116.15,14.63"
            + "ZM117.58,8.42C117.58,8.42 115.91,8.42 115.91,8.42C115.91,8.42 117.53,3.33 117.53,3.33"
            + "C117.53,3.33 118.3,3.33 118.3,3.33C121.8,3.26 121.04,8.35 117.58,8.42ZM174.63,2.12C174.63,2.12 170.55,2.12 170.55,2.12"
            + "C170.55,2.12 166.08,15.85 166.08,15.85C166.08,15.85 171.37,15.85 171.37,15.85C182.07,15.47 184.09,2 174.63,2.12"
            + "ZM171.24,14.57C171.24,14.57 169.71,14.57 169.71,14.57C169.71,14.57 173.53,3.4 173.53,3.4"
            + "C173.53,3.4 174.62,3.4 174.62,3.4C179.73,3.4 177.31,14.57 171.24,14.57Z";

    private static final String PATH_HBLACK_LOGO =
        "M32.521,8.165C32.521,8.165 28.419,21.144 28.419,21.144C28.419,21.144 18.367,21.144 18.367,21.144"
            + "C18.367,21.144 22.472,8.165 22.472,8.165C22.472,8.165 15.983,8.165 15.983,8.165"
            + "C15.983,8.165 11.878,21.144 11.878,21.144C11.878,21.144 5.267,21.144 5.267,21.144"
            + "C5.267,21.144 4.536,23.558 4.536,23.558C4.536,23.558 11.116,23.558 11.116,23.558"
            + "C11.116,23.558 7.332,35.517 7.332,35.517C7.332,35.517 13.821,35.517 13.821,35.517"
            + "C13.821,35.517 17.605,23.558 17.605,23.558C17.605,23.558 27.651,23.558 27.651,23.558"
            + "C27.651,23.558 23.87,35.517 23.87,35.517C23.87,35.517 30.358,35.517 30.358,35.517"
            + "C30.358,35.517 39.01,8.165 39.01,8.165C39.01,8.165 32.521,8.165 32.521,8.165Z";

    private static final String PATH_HWHITE_LOGO =
        "M599.9,150.61L524.23,390.04H338.8L414.53,150.61H294.84L219.11,390.04H97.16L83.67,434.57H205.06"
            + "L135.26,655.15H254.94L324.75,434.57H510.07L440.32,655.15H560.01L719.59,150.61H599.9Z";

    static {
        Map<String, String> lensMap = new HashMap<>();
        lensMap.put("XCD 21", "XCD 4,0 / 21");
        lensMap.put("XCD 25V", "XCD 2,5 / 25V");
        lensMap.put("XCD 30", "XCD 3,5 / 30");
        lensMap.put("XCD 38V", "XCD 2,5 / 38V");
        lensMap.put("XCD 45", "XCD 3,5 / 45");
        lensMap.put("XCD 45P", "XCD 4 / 45P");
        lensMap.put("XCD 28P", "XCD 4 / 28P");
        lensMap.put("XCD 55V", "XCD 2,5 / 55V");
        lensMap.put("XCD 65", "XCD 2,8 / 65");
        lensMap.put("XCD 80", "XCD 1,9 / 80");
        lensMap.put("XCD 90V", "XCD 2,5 / 90V");
        lensMap.put("XCD 90", "XCD 3,2 / 90");
        lensMap.put("XCD 120", "XCD 3,5 / 120 MACRO");
        lensMap.put("XCD 135", "XCD 2,8 / 135");
        lensMap.put("XCD 20-35E", "XCD 3,2-4,5 / 20-35E");
        lensMap.put("XCD 35-75", "XCD 3,5-4,5 / 35-75");
        lensMap.put("XCD 75P", "XCD 75P");
        lensMap.put("XCD 35-100E", "XCD 2,8-4 / 35-100E");
        lensMap.put("XCD 90 II", "XCD 2,5 / 90V");
        lensMap.put("XCD 38", "XCD 2,5 / 38V");
        lensMap.put("XCD 55", "XCD 2,5 / 55V");
        lensMap.put("-", "XCD");
        XCD_LENS_MAP = Collections.unmodifiableMap(lensMap);

        Map<Integer, StyleSpec> specs = new HashMap<>();
        specs.put(1, new StyleSpec(1, "Classic White",
            16.0f, 16.0f, 16.0f, 144.0f, 16.0f, 16.0f, 16.0f, 136.0f,
            0xFFFFFFFF, false));
        specs.put(2, new StyleSpec(2, "Simple White",
            16.0f, 16.0f, 16.0f, 114.0f, 16.0f, 16.0f, 16.0f, 98.0f,
            0xFFFFFFFF, false));
        specs.put(3, new StyleSpec(3, "Bottom Bar Simple",
            0.0f, 0.0f, 0.0f, 90.0f, 0.0f, 0.0f, 0.0f, 86.0f,
            0xFFFFFFFF, false));
        specs.put(4, new StyleSpec(4, "Bottom Bar Info",
            0.0f, 0.0f, 0.0f, 90.0f, 0.0f, 0.0f, 0.0f, 90.0f,
            0xFFFFFFFF, false));
        specs.put(6, new StyleSpec(6, "Overlay Minimal EXIF",
            0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0xFFFFFFFF, false));
        specs.put(7, new StyleSpec(7, "Top Wordmark White",
            16.0f, 114.0f, 16.0f, 84.0f, 16.0f, 114.0f, 16.0f, 80.0f,
            0xFFFFFFFF, false));
        specs.put(8, new StyleSpec(8, "Earth Explorer Overlay",
            0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0xFFFFFFFF, false));
        specs.put(9, new StyleSpec(9, "Earth Explorer White",
            16.0f, 16.0f, 16.0f, 118.0f, 16.0f, 16.0f, 16.0f, 106.0f,
            0xFFFFFFFF, false));
        specs.put(12, new StyleSpec(12, "Black Orange",
            16.0f, 16.0f, 16.0f, 114.0f, 16.0f, 16.0f, 16.0f, 98.0f,
            0xFF1D1D1D, false));
        specs.put(13, new StyleSpec(13, "New Year 2026",
            0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0xFFFFFFFF, true));
        specs.put(14, new StyleSpec(14, "Year of the Horse 2026",
            0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
            0xFFFFFFFF, true));
        specs.put(15, new StyleSpec(15, "Film Frame",
            0.0f, 56.0f, 0.0f, 56.0f, 56.0f, 0.0f, 56.0f, 0.0f,
            0xFFFFFFFF, false));
        STYLE_SPECS = Collections.unmodifiableMap(specs);

        VEC_HASSELBLAD_LOGO = new VectorSpec(182.0f, 18.0f, 0xFF000000, PATH_HASSELBLAD_WORDMARK);
        VEC_HASSELBLAD_LOGO_ORANGE = new VectorSpec(182.0f, 18.0f, 0xFF9F795A, PATH_HASSELBLAD_WORDMARK);
        VEC_HBLACK_LOGO = new VectorSpec(43.546f, 43.546f, 0xFF000000, PATH_HBLACK_LOGO);
        VEC_HWHITE_LOGO = new VectorSpec(800.0f, 800.0f, 0xFFFFFFFF, PATH_HWHITE_LOGO);
    }

    private PhocusStyles() {}

    /** Geometry specification for a single Phocus frame/overlay style. */
    public static final class StyleSpec {
        public final int styleId;
        public final String name;
        public final float padLandLeft;
        public final float padLandTop;
        public final float padLandRight;
        public final float padLandBottom;
        public final float padPortLeft;
        public final float padPortTop;
        public final float padPortRight;
        public final float padPortBottom;
        public final int maskColor;
        public final boolean cloudControlled;

        public StyleSpec(
                int styleId,
                String name,
                float padLandLeft,
                float padLandTop,
                float padLandRight,
                float padLandBottom,
                float padPortLeft,
                float padPortTop,
                float padPortRight,
                float padPortBottom,
                int maskColor,
                boolean cloudControlled) {
            this.styleId = styleId;
            this.name = name;
            this.padLandLeft = padLandLeft;
            this.padLandTop = padLandTop;
            this.padLandRight = padLandRight;
            this.padLandBottom = padLandBottom;
            this.padPortLeft = padPortLeft;
            this.padPortTop = padPortTop;
            this.padPortRight = padPortRight;
            this.padPortBottom = padPortBottom;
            this.maskColor = maskColor;
            this.cloudControlled = cloudControlled;
        }

        public String styleKey() {
            return STYLE_PREFIX + styleId;
        }

        public boolean isOverlay() {
            return padLandLeft == 0.0f && padLandTop == 0.0f && padLandRight == 0.0f && padLandBottom == 0.0f
                && padPortLeft == 0.0f && padPortTop == 0.0f && padPortRight == 0.0f && padPortBottom == 0.0f;
        }

        public boolean hasBorder() {
            return !isOverlay();
        }
    }

    /** Normalized EXIF metadata matching Phocus {@code MaskImageInfo}. */
    public static final class PhotoMeta {
        public final String aperture;
        public final String shutter;
        public final String iso;
        public final String lensInfoText;
        public final String fullBodyName;
        public final String createTime;

        public PhotoMeta(
                String aperture,
                String shutter,
                String iso,
                String lensInfoText,
                String fullBodyName,
                String createTime) {
            this.aperture = aperture != null ? aperture : "f/-";
            this.shutter = shutter != null ? shutter : "-s";
            this.iso = iso != null ? iso : "-";
            this.lensInfoText = lensInfoText != null ? lensInfoText : "Third Party Lens";
            this.fullBodyName = fullBodyName != null ? fullBodyName : "Hasselblad X2D 100C";
            this.createTime = createTime != null ? createTime : "";
        }
    }

    /** VectorDrawable specification with cached parsed Android {@link Path}. */
    public static final class VectorSpec {
        public final float viewportWidth;
        public final float viewportHeight;
        public final int fillColor;
        public final String pathData;
        private volatile Path cachedPath;

        public VectorSpec(float viewportWidth, float viewportHeight, int fillColor, String pathData) {
            this.viewportWidth = viewportWidth;
            this.viewportHeight = viewportHeight;
            this.fillColor = fillColor;
            this.pathData = pathData;
        }

        public Path getPath() {
            Path p = cachedPath;
            if (p == null) {
                synchronized (this) {
                    p = cachedPath;
                    if (p == null) {
                        p = parseSvgPath(pathData);
                        cachedPath = p;
                    }
                }
            }
            return new Path(p);
        }
    }

    public static boolean isPhocusStyle(String styleId) {
        return parseStyleId(styleId) >= 0;
    }

    /** Parses {@code personalize_more_phocus_<id>} and returns the style ID, or {@code -1} if invalid. */
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
            return STYLE_SPECS.containsKey(id) ? id : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    public static StyleSpec getStyle(int styleId) {
        return STYLE_SPECS.get(styleId);
    }

    public static StyleSpec getStyle(String styleId) {
        int id = parseStyleId(styleId);
        return id >= 0 ? STYLE_SPECS.get(id) : null;
    }

    public static boolean isOverlayStyle(int styleId) {
        StyleSpec spec = STYLE_SPECS.get(styleId);
        return spec != null && spec.isOverlay();
    }

    public static boolean isOverlayStyle(String styleId) {
        StyleSpec spec = getStyle(styleId);
        return spec != null && spec.isOverlay();
    }

    public static boolean isFramedStyle(int styleId) {
        StyleSpec spec = STYLE_SPECS.get(styleId);
        return spec != null && spec.hasBorder();
    }

    public static boolean isFramedStyle(String styleId) {
        StyleSpec spec = getStyle(styleId);
        return spec != null && spec.hasBorder();
    }

    /** Formats aperture F-number as {@code f/X.Y} or {@code f/-}. */
    public static String formatAperture(Double aperture) {
        if (aperture == null || !Double.isFinite(aperture)) return "f/-";
        return String.format(Locale.US, "f/%.1f", aperture);
    }

    /** Normalizes a string aperture value (e.g. {@code "1.8"}, {@code "F1.8"}, {@code "f/1.80"}) to {@code f/X.Y}. */
    public static String formatAperture(String rawAperture) {
        if (rawAperture == null) return "f/-";
        String s = rawAperture.trim();
        if (s.isEmpty() || "-".equals(s) || "f/-".equalsIgnoreCase(s)) return "f/-";
        if (s.startsWith("f/") || s.startsWith("F/")) {
            s = s.substring(2).trim();
        } else if ((s.startsWith("f") || s.startsWith("F")) && s.length() > 1 && (Character.isDigit(s.charAt(1)) || s.charAt(1) == '.')) {
            s = s.substring(1).trim();
        }
        if (s.isEmpty() || "-".equals(s)) return "f/-";
        try {
            double val = Double.parseDouble(s);
            if (Double.isFinite(val) && val > 0.0) {
                return String.format(Locale.US, "f/%.1f", val);
            }
        } catch (NumberFormatException ignored) {
        }
        return "f/" + s;
    }

    /** Formats exposure time in seconds matching {@code models.py _format_shutter_from_exif} + {@code "s"}. */
    public static String formatShutterFromSeconds(Double secs) {
        if (secs == null || !Double.isFinite(secs) || secs <= 0.0) return "-s";
        if (secs < 1.0) {
            long denom = Math.round(1.0 / secs);
            if (denom > 0 && Math.abs(secs - 1.0 / denom) / secs < 0.02) {
                return "1/" + denom + "s";
            }
        }
        long rounded = Math.round(secs);
        if (Math.abs(secs - rounded) < 1e-4) {
            return rounded + "s";
        }
        String formatted = String.format(Locale.US, "%.1f", secs);
        formatted = stripTrailingZeroDecimal(formatted);
        return formatted + "s";
    }

    /** Formats a shutter string matching {@code models.py format_metadata} (appends {@code "s"} if absent). */
    public static String formatShutter(String rawShutter) {
        if (rawShutter == null) return "-s";
        String s = rawShutter.trim();
        if (s.isEmpty()) return "-s";
        if (!s.toLowerCase(Locale.ROOT).contains("s")) {
            s = s + "s";
        }
        return s;
    }

    /** Parses an EXIF exposure string (fraction {@code "1/250"}, decimal {@code "0.004"}, or {@code "1/250s"}) into Phocus format. */
    public static String formatShutterExif(String rawShutter) {
        if (rawShutter == null) return "-s";
        String s = rawShutter.trim();
        if (s.isEmpty() || "-".equals(s) || "-s".equalsIgnoreCase(s)) return "-s";
        if (s.endsWith("s") || s.endsWith("S")) {
            return s.substring(0, s.length() - 1).trim() + "s";
        }
        int slash = s.indexOf('/');
        if (slash > 0 && slash < s.length() - 1) {
            try {
                double num = Double.parseDouble(s.substring(0, slash).trim());
                double den = Double.parseDouble(s.substring(slash + 1).trim());
                if (den > 0.0 && num > 0.0) {
                    return formatShutterFromSeconds(num / den);
                }
            } catch (NumberFormatException ignored) {
            }
            return formatShutter(s);
        }
        try {
            double secs = Double.parseDouble(s);
            // The camera hands the exposure over in nanoseconds, the gallery in seconds; no exposure
            // lasts a thousand seconds.
            if (secs >= 1000.0) secs /= 1.0e9;
            if (Double.isFinite(secs) && secs > 0.0) {
                return formatShutterFromSeconds(secs);
            }
        } catch (NumberFormatException ignored) {
        }
        return formatShutter(s);
    }

    public static String formatIso(Integer iso) {
        return iso != null && iso > 0 ? String.valueOf(iso) : "-";
    }

    public static String formatIso(String rawIso) {
        if (rawIso == null) return "-";
        String s = rawIso.trim();
        if (s.length() >= 3 && s.substring(0, 3).equalsIgnoreCase("iso")) {
            s = s.substring(3).trim();
        }
        return s.isEmpty() ? "-" : s;
    }

    /** Replicates {@code models.py format_lens_info} against {@link #XCD_LENS_MAP}. */
    public static String formatLensInfo(String lens) {
        String raw = lens != null ? lens.trim() : "-";
        int at = raw.indexOf('@');
        if (at >= 0) {
            raw = raw.substring(0, at);
        }
        if (raw.toLowerCase(Locale.ROOT).contains("xcd")) {
            String mapped = XCD_LENS_MAP.get(raw);
            return mapped != null ? mapped : "Third Party Lens";
        }
        if (!raw.isEmpty() && !"unknown".equalsIgnoreCase(raw)) {
            return raw;
        }
        return "Third Party Lens";
    }

    /**
     * Lens family of the phone's cameras: the counterpart of Hasselblad's XCD (X system, central
     * shutter, digital) is OED (OPPO, electronic shutter, digital).
     */
    private static final String PHONE_LENS_FAMILY = "OED";

    /**
     * Lens text of a phone camera named like the XCD lenses ("OED 1,8 / 23": maximum aperture,
     * 35mm-equivalent focal length), since the phone's lenses are not in the XCD table.
     * A lens name in the photo's metadata that is an XCD lens is mapped through the table instead.
     */
    public static String formatLensOrFocal(String lens, String focal35mm, String aperture) {
        String trimmed = lens != null ? lens.trim() : "";
        if (!trimmed.isEmpty() && !"-".equals(trimmed) && !"unknown".equalsIgnoreCase(trimmed)) {
            return formatLensInfo(trimmed);
        }
        Double focal = parseNumber(focal35mm);
        if (focal == null || focal <= 0) {
            return formatLensInfo(lens);
        }
        Double f = parseNumber(aperture);
        String name = PHONE_LENS_FAMILY + " ";
        if (f != null && f > 0) {
            String value = String.format(Locale.US, "%.1f", f);
            if (value.endsWith(".0")) value = value.substring(0, value.length() - 2);
            name += value.replace('.', ',') + " / ";
        }
        return name + Math.round(focal);
    }

    private static Double parseNumber(String text) {
        if (text == null) return null;
        Matcher matcher = Pattern.compile("[0-9]+(?:[.][0-9]+)?").matcher(text);
        return matcher.find() ? Double.valueOf(matcher.group()) : null;
    }

    /** Normalizes camera model name matching {@code models.py format_metadata}. */
    public static String formatBodyName(String bodyName) {
        String body = bodyName != null ? bodyName.trim() : "";
        if (body.isEmpty()) {
            body = "X2D 100C";
        }
        if (body.toLowerCase(Locale.ROOT).startsWith("hasselblad")) {
            body = body.substring("Hasselblad".length()).trim();
        }
        return body.isEmpty() ? "Hasselblad " : "Hasselblad " + body;
    }

    /** Formats millisecond timestamp + offset ({@code +HH:mm}) as {@code "yyyy MM dd"}. */
    public static String formatTimestampMs(long createDateMs, String offsetTime) {
        if (createDateMs <= 0L) return "";
        String offsetStr = (offsetTime != null && !offsetTime.isEmpty()) ? offsetTime : "+00:00";
        Matcher m = OFFSET_PATTERN.matcher(offsetStr);
        if (!m.matches()) return "";
        int hours = Integer.parseInt(m.group(2));
        int mins = Integer.parseInt(m.group(3));
        if (hours > 18 || mins > 59 || (hours == 18 && mins > 0)) return "";
        int sign = "-".equals(m.group(1)) ? -1 : 1;
        ZoneOffset zone = ZoneOffset.ofHoursMinutes(sign * hours, sign * mins);
        return Instant.ofEpochMilli(createDateMs).atZone(zone).format(DATE_FORMATTER);
    }

    /** Formats millisecond timestamp using system default timezone as {@code "yyyy MM dd"}. */
    public static String formatTimestampMsLocal(long createDateMs) {
        if (createDateMs <= 0L) return "";
        return Instant.ofEpochMilli(createDateMs).atZone(ZoneId.systemDefault()).format(DATE_FORMATTER);
    }

    /** Formats EXIF or Gallery date strings ({@code yyyy:MM:dd...}, {@code yyyy-MM-dd...}, {@code yyyy/MM/dd...}, {@code yyyy年M月d日}) as {@code "yyyy MM dd"}. */
    public static String formatCreateTime(String rawDateTime) {
        if (rawDateTime == null) return "";
        String s = rawDateTime.trim();
        if (s.isEmpty()) return "";
        Matcher m = YMD_PATTERN.matcher(s);
        if (!m.find()) return "";
        int month = Integer.parseInt(m.group(2));
        int day = Integer.parseInt(m.group(3));
        if (month < 1 || month > 12 || day < 1 || day > 31) return "";
        return String.format(Locale.US, "%s %02d %02d", m.group(1), month, day);
    }

    /** Formats metadata matching {@code models.py format_metadata}. */
    public static PhotoMeta formatMeta(
            Double aperture,
            String shutter,
            String iso,
            String lens,
            String bodyName,
            Long createDateMs,
            String offsetTime,
            String createTimeStr) {
        String dateText = (createTimeStr != null)
            ? formatCreateTimeIfNeeded(createTimeStr)
            : (createDateMs != null ? formatTimestampMs(createDateMs, offsetTime) : "");
        return new PhotoMeta(
            formatAperture(aperture),
            formatShutter(shutter),
            formatIso(iso),
            formatLensInfo(lens),
            formatBodyName(bodyName),
            dateText
        );
    }

    /** Convenience formatter for string inputs coming from Camera/Gallery watermark pipelines. */
    public static PhotoMeta formatMetaFromStrings(
            String rawAperture,
            String rawShutter,
            String rawIso,
            String rawLens,
            String rawFocal35mm,
            String rawBodyName,
            String rawCreateTime) {
        return new PhotoMeta(
            formatAperture(rawAperture),
            formatShutterExif(rawShutter),
            formatIso(rawIso),
            formatLensOrFocal(rawLens, rawFocal35mm, rawAperture),
            formatBodyName(rawBodyName),
            formatCreateTime(rawCreateTime)
        );
    }

    private static String formatCreateTimeIfNeeded(String createTimeStr) {
        String formatted = formatCreateTime(createTimeStr);
        return !formatted.isEmpty() ? formatted : createTimeStr.trim();
    }

    private static String stripTrailingZeroDecimal(String value) {
        int dot = value.indexOf('.');
        if (dot < 0) return value;
        int end = value.length();
        while (end > dot && value.charAt(end - 1) == '0') end--;
        if (end == dot + 1) end = dot;
        return value.substring(0, end);
    }

    /** Rasterizes a {@link VectorSpec} into an ARGB_8888 Bitmap of exact {@code width x height} pixels. */
    public static Bitmap rasterizeVector(VectorSpec vec, int width, int height) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.scale(w / vec.viewportWidth, h / vec.viewportHeight);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(vec.fillColor);
        canvas.drawPath(vec.getPath(), paint);
        return bmp;
    }

    /**
     * Pure-Android SVG / VectorDrawable path parser supporting
     * {@code M/m, L/l, H/h, V/v, C/c, S/s, Q/q, T/t, A/a, Z/z}.
     */
    public static Path parseSvgPath(String d) {
        Path path = new Path();
        if (d == null || d.isEmpty()) return path;

        List<String> tokens = new ArrayList<>();
        Matcher matcher = SVG_TOKEN_PATTERN.matcher(d);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }

        int idx = 0;
        int n = tokens.size();
        char cmd = 0;
        char lastCmd = 0;
        float cx = 0f, cy = 0f;
        float sx = 0f, sy = 0f;
        float lastCtrlX = 0f, lastCtrlY = 0f;
        boolean hasLastCtrl = false;

        while (idx < n) {
            String tok = tokens.get(idx);
            if (tok.length() == 1 && Character.isLetter(tok.charAt(0))) {
                cmd = tok.charAt(0);
                idx++;
            }
            if (cmd == 0) break;

            if (cmd == 'Z' || cmd == 'z') {
                path.close();
                cx = sx;
                cy = sy;
                hasLastCtrl = false;
                lastCmd = cmd;
                continue;
            }

            if (cmd == 'M' || cmd == 'm') {
                if (idx + 1 >= n) break;
                float x = Float.parseFloat(tokens.get(idx++));
                float y = Float.parseFloat(tokens.get(idx++));
                if (cmd == 'm') {
                    x += cx;
                    y += cy;
                }
                cx = x;
                cy = y;
                sx = x;
                sy = y;
                path.moveTo(cx, cy);
                hasLastCtrl = false;
                lastCmd = cmd;
                cmd = (cmd == 'M') ? 'L' : 'l';
                continue;
            }

            switch (cmd) {
                case 'L':
                case 'l': {
                    if (idx + 1 >= n) { idx = n; break; }
                    float x = Float.parseFloat(tokens.get(idx++));
                    float y = Float.parseFloat(tokens.get(idx++));
                    if (cmd == 'l') { x += cx; y += cy; }
                    cx = x; cy = y;
                    path.lineTo(cx, cy);
                    hasLastCtrl = false;
                    break;
                }
                case 'H':
                case 'h': {
                    if (idx >= n) { idx = n; break; }
                    float x = Float.parseFloat(tokens.get(idx++));
                    if (cmd == 'h') x += cx;
                    cx = x;
                    path.lineTo(cx, cy);
                    hasLastCtrl = false;
                    break;
                }
                case 'V':
                case 'v': {
                    if (idx >= n) { idx = n; break; }
                    float y = Float.parseFloat(tokens.get(idx++));
                    if (cmd == 'v') y += cy;
                    cy = y;
                    path.lineTo(cx, cy);
                    hasLastCtrl = false;
                    break;
                }
                case 'C':
                case 'c': {
                    if (idx + 5 >= n) { idx = n; break; }
                    float x1 = Float.parseFloat(tokens.get(idx++));
                    float y1 = Float.parseFloat(tokens.get(idx++));
                    float x2 = Float.parseFloat(tokens.get(idx++));
                    float y2 = Float.parseFloat(tokens.get(idx++));
                    float x = Float.parseFloat(tokens.get(idx++));
                    float y = Float.parseFloat(tokens.get(idx++));
                    if (cmd == 'c') {
                        x1 += cx; y1 += cy;
                        x2 += cx; y2 += cy;
                        x += cx; y += cy;
                    }
                    path.cubicTo(x1, y1, x2, y2, x, y);
                    lastCtrlX = x2;
                    lastCtrlY = y2;
                    hasLastCtrl = true;
                    cx = x; cy = y;
                    break;
                }
                case 'S':
                case 's': {
                    if (idx + 3 >= n) { idx = n; break; }
                    float x2 = Float.parseFloat(tokens.get(idx++));
                    float y2 = Float.parseFloat(tokens.get(idx++));
                    float x = Float.parseFloat(tokens.get(idx++));
                    float y = Float.parseFloat(tokens.get(idx++));
                    if (cmd == 's') {
                        x2 += cx; y2 += cy;
                        x += cx; y += cy;
                    }
                    float x1 = cx, y1 = cy;
                    if (hasLastCtrl && (lastCmd == 'C' || lastCmd == 'c' || lastCmd == 'S' || lastCmd == 's')) {
                        x1 = 2f * cx - lastCtrlX;
                        y1 = 2f * cy - lastCtrlY;
                    }
                    path.cubicTo(x1, y1, x2, y2, x, y);
                    lastCtrlX = x2;
                    lastCtrlY = y2;
                    hasLastCtrl = true;
                    cx = x; cy = y;
                    break;
                }
                case 'Q':
                case 'q': {
                    if (idx + 3 >= n) { idx = n; break; }
                    float x1 = Float.parseFloat(tokens.get(idx++));
                    float y1 = Float.parseFloat(tokens.get(idx++));
                    float x = Float.parseFloat(tokens.get(idx++));
                    float y = Float.parseFloat(tokens.get(idx++));
                    if (cmd == 'q') {
                        x1 += cx; y1 += cy;
                        x += cx; y += cy;
                    }
                    path.quadTo(x1, y1, x, y);
                    lastCtrlX = x1;
                    lastCtrlY = y1;
                    hasLastCtrl = true;
                    cx = x; cy = y;
                    break;
                }
                case 'T':
                case 't': {
                    if (idx + 1 >= n) { idx = n; break; }
                    float x = Float.parseFloat(tokens.get(idx++));
                    float y = Float.parseFloat(tokens.get(idx++));
                    if (cmd == 't') { x += cx; y += cy; }
                    float x1 = cx, y1 = cy;
                    if (hasLastCtrl && (lastCmd == 'Q' || lastCmd == 'q' || lastCmd == 'T' || lastCmd == 't')) {
                        x1 = 2f * cx - lastCtrlX;
                        y1 = 2f * cy - lastCtrlY;
                    }
                    path.quadTo(x1, y1, x, y);
                    lastCtrlX = x1;
                    lastCtrlY = y1;
                    hasLastCtrl = true;
                    cx = x; cy = y;
                    break;
                }
                case 'A':
                case 'a': {
                    if (idx + 6 >= n) { idx = n; break; }
                    float rx = Float.parseFloat(tokens.get(idx++));
                    float ry = Float.parseFloat(tokens.get(idx++));
                    float rot = Float.parseFloat(tokens.get(idx++));
                    int laf = (int) Float.parseFloat(tokens.get(idx++));
                    int sf = (int) Float.parseFloat(tokens.get(idx++));
                    float x = Float.parseFloat(tokens.get(idx++));
                    float y = Float.parseFloat(tokens.get(idx++));
                    if (cmd == 'a') { x += cx; y += cy; }
                    appendArc(path, cx, cy, rx, ry, rot, laf != 0, sf != 0, x, y);
                    cx = x; cy = y;
                    hasLastCtrl = false;
                    break;
                }
                default:
                    idx = n;
                    break;
            }
            lastCmd = cmd;
        }
        return path;
    }

    private static void appendArc(
            Path path,
            double x1, double y1,
            double rx, double ry,
            double phiDeg,
            boolean largeArc,
            boolean sweep,
            double x2, double y2) {
        if ((x1 == x2 && y1 == y2) || rx == 0.0 || ry == 0.0) {
            path.lineTo((float) x2, (float) y2);
            return;
        }
        rx = Math.abs(rx);
        ry = Math.abs(ry);
        double phi = Math.toRadians(phiDeg % 360.0);
        double cosP = Math.cos(phi);
        double sinP = Math.sin(phi);
        double dx2 = (x1 - x2) * 0.5;
        double dy2 = (y1 - y2) * 0.5;
        double x1p = cosP * dx2 + sinP * dy2;
        double y1p = -sinP * dx2 + cosP * dy2;

        double lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry);
        if (lam > 1.0) {
            double s = Math.sqrt(lam);
            rx *= s;
            ry *= s;
        }

        double rx2 = rx * rx;
        double ry2 = ry * ry;
        double x1p2 = x1p * x1p;
        double y1p2 = y1p * y1p;
        double num = Math.max(0.0, rx2 * ry2 - rx2 * y1p2 - ry2 * x1p2);
        double den = rx2 * y1p2 + ry2 * x1p2;
        double coef = den > 1e-12 ? Math.sqrt(num / den) : 0.0;
        if (largeArc == sweep) coef = -coef;

        double cxp = coef * (rx * y1p / ry);
        double cyp = coef * (-ry * x1p / rx);
        double cx = cosP * cxp - sinP * cyp + (x1 + x2) * 0.5;
        double cy = sinP * cxp + cosP * cyp + (y1 + y2) * 0.5;

        double theta1 = vectorAngle(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry);
        double dtheta = vectorAngle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry);
        if (!sweep && dtheta > 0.0) {
            dtheta -= 2.0 * Math.PI;
        } else if (sweep && dtheta < 0.0) {
            dtheta += 2.0 * Math.PI;
        }

        final int steps = 24;
        for (int k = 1; k <= steps; k++) {
            double t = theta1 + dtheta * (k / (double) steps);
            double xr = rx * Math.cos(t);
            double yr = ry * Math.sin(t);
            path.lineTo((float) (cosP * xr - sinP * yr + cx), (float) (sinP * xr + cosP * yr + cy));
        }
    }

    private static double vectorAngle(double ux, double uy, double vx, double vy) {
        double dot = ux * vx + uy * vy;
        double mag = Math.hypot(ux, uy) * Math.hypot(vx, vy);
        double c = mag > 1e-12 ? Math.max(-1.0, Math.min(1.0, dot / mag)) : 1.0;
        double a = Math.acos(c);
        return (ux * vy - uy * vx) < 0.0 ? -a : a;
    }
}
