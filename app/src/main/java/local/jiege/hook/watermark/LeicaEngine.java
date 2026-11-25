package local.jiege.hook.watermark;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import local.jiege.hook.common.Log;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Native Android View-Tree parser, layout engine, and layered Canvas renderer for the
 * 19 Xiaomi HyperOS Leica watermark styles (1:1 port of {@code xiaomi_leica_watermark}).
 */
public final class LeicaEngine {
    private static final String TAG = "LeicaEngine";

    public static final String GALLERY_ASSET_DIR = "/data/user/0/com.coloros.gallery3d/files/watermark_master/leica";
    public static final String CAMERA_ASSET_DIR = "/data/user/0/com.oplus.camera/files/jiege/leica";

    private static final int GRAVITY_NONE = 0;
    private static final int GRAVITY_TOP = 1;
    private static final int GRAVITY_BOTTOM = 2;
    private static final int GRAVITY_LEFT = 4;
    private static final int GRAVITY_RIGHT = 8;
    private static final int GRAVITY_CENTER_VERTICAL = 16;
    private static final int GRAVITY_CENTER_HORIZONTAL = 32;

    private static final int DIM_WRAP_CONTENT = -1;
    private static final int DIM_MATCH_PARENT = -2;

    private static final Pattern WGHT_PATTERN = Pattern.compile("'wght'\\s+(\\d+)");
    private static final Pattern LOC_LIMIT_PATTERN = Pattern.compile("@locationLimit=([0-9.]+)");
    private static final Pattern LOC_LINES_PATTERN = Pattern.compile("@locationMaxLines=(\\d+)");

    private static final Map<String, StyleConfig> CONFIG_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, Typeface> FONT_CACHE = new ConcurrentHashMap<>();
    /** The Leica typeface (Leica Gothic), shared under the asset dir's fonts. */
    private static final Map<String, Bitmap> BITMAP_CACHE = new ConcurrentHashMap<>();

    private LeicaEngine() {}

    public static final class Metrics {
        public final int imgW;
        public final int imgH;
        public final double scale;
        public final int padLeft;
        public final int padTop;
        public final int padRight;
        public final int padBottom;
        public final int canvasW;
        public final int canvasH;
        public final boolean isLandscape;
        public final boolean hasBorder;
        public final int bgColor;
        public final boolean isBlur;
        public final boolean isLightBlur;

        Metrics(
                int imgW,
                int imgH,
                double scale,
                int padLeft,
                int padTop,
                int padRight,
                int padBottom,
                int canvasW,
                int canvasH,
                boolean hasBorder,
                int bgColor,
                boolean isBlur,
                boolean isLightBlur) {
            this.imgW = imgW;
            this.imgH = imgH;
            this.scale = scale;
            this.padLeft = padLeft;
            this.padTop = padTop;
            this.padRight = padRight;
            this.padBottom = padBottom;
            this.canvasW = canvasW;
            this.canvasH = canvasH;
            this.isLandscape = imgW > imgH;
            this.hasBorder = hasBorder;
            this.bgColor = bgColor;
            this.isBlur = isBlur;
            this.isLightBlur = isLightBlur;
        }
    }

    public static final class RenderLayers {
        public final Metrics metrics;
        public final int borderBgColor;
        public final Bitmap excludedBitmap;
        public final Bitmap overlayMainBitmap;

        RenderLayers(Metrics metrics, int borderBgColor, Bitmap excludedBitmap, Bitmap overlayMainBitmap) {
            this.metrics = metrics;
            this.borderBgColor = borderBgColor;
            this.excludedBitmap = excludedBitmap;
            this.overlayMainBitmap = overlayMainBitmap;
        }

        public void recycle() {
            if (excludedBitmap != null && !excludedBitmap.isRecycled()) excludedBitmap.recycle();
            if (overlayMainBitmap != null && !overlayMainBitmap.isRecycled()) overlayMainBitmap.recycle();
        }
    }

    private static final class BgPalette {
        final String name;
        final String value;
        final List<String> foreground;

        BgPalette(String name, String value, List<String> foreground) {
            this.name = name;
            this.value = value;
            this.foreground = foreground;
        }
    }

    private static final class StyleConfig {
        final File styleDir;
        final JSONObject raw;
        final Map<String, Map<String, String>> i18n;
        final Map<String, BgPalette> backgrounds;
        final Map<String, List<JSONObject>> layoutGroups;

        StyleConfig(
                File styleDir,
                JSONObject raw,
                Map<String, Map<String, String>> i18n,
                Map<String, BgPalette> backgrounds,
                Map<String, List<JSONObject>> layoutGroups) {
            this.styleDir = styleDir;
            this.raw = raw;
            this.i18n = i18n;
            this.backgrounds = backgrounds;
            this.layoutGroups = layoutGroups;
        }
    }

    private static final class FontSpec {
        final float size;
        final int color;
        final String typeface;
        final int weight;
        final float letterSpacing;
        final float shadowRadius;
        final float shadowDx;
        final float shadowDy;
        final int shadowColor;

        FontSpec(
                float size,
                int color,
                String typeface,
                int weight,
                float letterSpacing,
                float shadowRadius,
                float shadowDx,
                float shadowDy,
                int shadowColor) {
            this.size = size;
            this.color = color;
            this.typeface = typeface;
            this.weight = weight;
            this.letterSpacing = letterSpacing;
            this.shadowRadius = shadowRadius;
            this.shadowDx = shadowDx;
            this.shadowDy = shadowDy;
            this.shadowColor = shadowColor;
        }

        FontSpec withLetterSpacing(float ls) {
            return new FontSpec(size, color, typeface, weight, ls, shadowRadius, shadowDx, shadowDy, shadowColor);
        }

    }

    private static final class RectParamsSpec {
        final float textWidthGap;
        final float textHeightGap;
        final float lineWidth;
        final float radius;
        final int lineColor;
        final int fillColor;
        final int fixedW;
        final int fixedH;

        RectParamsSpec(
                float textWidthGap,
                float textHeightGap,
                float lineWidth,
                float radius,
                int lineColor,
                int fillColor,
                int fixedW,
                int fixedH) {
            this.textWidthGap = textWidthGap;
            this.textHeightGap = textHeightGap;
            this.lineWidth = lineWidth;
            this.radius = radius;
            this.lineColor = lineColor;
            this.fillColor = fillColor;
            this.fixedW = fixedW;
            this.fixedH = fixedH;
        }
    }

    private static final class PreparedLayout {
        final Metrics metrics;
        final ContainerNode root;
        final BgPalette bgPalette;
        final boolean isBlur;
        final boolean isLightBlur;
        final int bgColorArgb;

        PreparedLayout(
                Metrics metrics,
                ContainerNode root,
                BgPalette bgPalette,
                boolean isBlur,
                boolean isLightBlur,
                int bgColorArgb) {
            this.metrics = metrics;
            this.root = root;
            this.bgPalette = bgPalette;
            this.isBlur = isBlur;
            this.isLightBlur = isLightBlur;
            this.bgColorArgb = bgColorArgb;
        }
    }

    public static Metrics computeMetrics(
            LeicaStyles.StylePreset preset,
            int imgW,
            int imgH,
            LeicaStyles.PhotoMeta meta,
            String assetDir,
            LeicaStyles.BgChoice bgChoice,
            boolean hasselbladBadge,
            boolean alignEven) {
        PreparedLayout layout = prepareLayout(
                preset, imgW, imgH, meta, assetDir, bgChoice, hasselbladBadge, alignEven);
        return layout != null ? layout.metrics : new Metrics(
                imgW, imgH, Math.min(imgW, imgH) / 1080.0, 0, 0, 0, 0, imgW, imgH, false, 0, false, false);
    }

    public static RenderLayers render(
            LeicaStyles.StylePreset preset,
            int imgW,
            int imgH,
            LeicaStyles.PhotoMeta meta,
            String assetDir,
            Bitmap photoBitmapOrNull,
            LeicaStyles.BgChoice bgChoice,
            boolean hasselbladBadge,
            boolean alignEven) {
        PreparedLayout layout = prepareLayout(
                preset, imgW, imgH, meta, assetDir, bgChoice, hasselbladBadge, alignEven);
        if (layout == null) {
            Bitmap ov = Bitmap.createBitmap(Math.max(1, imgW), Math.max(1, imgH), Bitmap.Config.ARGB_8888);
            Metrics fallback = new Metrics(imgW, imgH, Math.min(imgW, imgH) / 1080.0, 0, 0, 0, 0, imgW, imgH, false, 0, false, false);
            return new RenderLayers(fallback, 0, null, ov);
        }

        Metrics m = layout.metrics;
        Bitmap excluded = null;
        if (m.hasBorder && layout.isBlur) {
            excluded = Bitmap.createBitmap(m.canvasW, m.canvasH, Bitmap.Config.ARGB_8888);
            Canvas ec = new Canvas(excluded);
            if (photoBitmapOrNull != null && !photoBitmapOrNull.isRecycled()) {
                Bitmap blurBmp = makeBlurBackground(photoBitmapOrNull, layout.isLightBlur);
                ec.drawBitmap(blurBmp, null, new Rect(0, 0, m.canvasW, m.canvasH), new Paint(Paint.FILTER_BITMAP_FLAG));
                blurBmp.recycle();
            } else {
                // YUV capture path: native MasterWatermarkAssembler.gaussianBlur blurs YUV into dstPtr;
                // scrim dims or brightens the blurred border before drawOriginBitmap stamps the sharp photo.
                ec.drawColor(layout.isLightBlur ? 0x80FFFFFF : 0x80000000);
            }
        }

        Bitmap overlay = Bitmap.createBitmap(m.canvasW, m.canvasH, Bitmap.Config.ARGB_8888);
        Canvas oc = new Canvas(overlay);
        layout.root.drawInto(oc, 0, 0, layout.isBlur);

        int borderBg = (!m.hasBorder || layout.isBlur) ? 0 : layout.bgColorArgb;
        return new RenderLayers(m, borderBg, excluded, overlay);
    }

    public static Bitmap compositeSdr(RenderLayers layers, Bitmap photoBitmap) {
        Metrics m = layers.metrics;
        Bitmap out = createSdrBitmap(m.canvasW, m.canvasH, photoBitmap);
        Canvas canvas = new Canvas(out);
        if (m.hasBorder && layers.borderBgColor != 0) {
            drawBorderRects(canvas, m, layers.borderBgColor);
        }
        if (layers.excludedBitmap != null) {
            canvas.drawBitmap(layers.excludedBitmap, 0f, 0f, null);
        }
        if (photoBitmap != null) {
            Bitmap src = photoBitmap;
            boolean needRecycle = false;
            if (src.getConfig() == Bitmap.Config.HARDWARE) {
                src = src.copy(Bitmap.Config.ARGB_8888, false);
                needRecycle = true;
            }
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            Rect dst = new Rect(m.padLeft, m.padTop, m.padLeft + m.imgW, m.padTop + m.imgH);
            canvas.drawBitmap(src, null, dst, p);
            if (needRecycle) src.recycle();
        }
        if (layers.overlayMainBitmap != null) {
            canvas.drawBitmap(layers.overlayMainBitmap, 0f, 0f, null);
        }
        return out;
    }

    public static Bitmap compositeTransparentOverlay(RenderLayers layers) {
        Metrics m = layers.metrics;
        Bitmap out = Bitmap.createBitmap(m.canvasW, m.canvasH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        if (m.hasBorder && layers.borderBgColor != 0) {
            drawBorderRects(canvas, m, layers.borderBgColor);
        }
        if (layers.excludedBitmap != null) {
            canvas.drawBitmap(layers.excludedBitmap, 0f, 0f, null);
        }
        if (layers.overlayMainBitmap != null) {
            canvas.drawBitmap(layers.overlayMainBitmap, 0f, 0f, null);
        }
        return out;
    }

    private static Bitmap createSdrBitmap(int width, int height, Bitmap photoBitmap) {
        if (photoBitmap != null) {
            ColorSpace cs = photoBitmap.getColorSpace();
            if (cs != null && !cs.isSrgb() && cs.getModel() == ColorSpace.Model.RGB) {
                try {
                    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, true, cs);
                } catch (IllegalArgumentException error) {
                    Log.e(TAG, "unsupported wide-gamut colorspace " + cs.getName(), error);
                }
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }

    private static void drawBorderRects(Canvas canvas, Metrics m, int color) {
        Paint p = new Paint();
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        if (m.padTop > 0) canvas.drawRect(0, 0, m.canvasW, m.padTop, p);
        if (m.padBottom > 0) canvas.drawRect(0, m.padTop + m.imgH, m.canvasW, m.canvasH, p);
        if (m.padLeft > 0) canvas.drawRect(0, 0, m.padLeft, m.canvasH, p);
        if (m.padRight > 0) canvas.drawRect(m.padLeft + m.imgW, 0, m.canvasW, m.canvasH, p);
    }

    // =========================================================================
    // Blur background generator matching Jm.C0998a (light_blur / dark_blur)
    // =========================================================================

    private static Bitmap makeBlurBackground(Bitmap src, boolean isLightBlur) {
        Bitmap work = src;
        boolean needRecycle = false;
        if (src.getConfig() == Bitmap.Config.HARDWARE) {
            work = src.copy(Bitmap.Config.ARGB_8888, false);
            needRecycle = true;
        }
        int w = Math.max(1, work.getWidth());
        int h = Math.max(1, work.getHeight());
        int maxDim = Math.max(w, h);
        float factor = maxDim > 100 ? (100f / maxDim) : 1f;
        int sw = Math.max(1, Math.round(w * factor));
        int sh = Math.max(1, Math.round(h * factor));

        Bitmap small = Bitmap.createScaledBitmap(work, sw, sh, true);
        if (needRecycle) work.recycle();

        int[] pixels = new int[sw * sh];
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh);
        gaussianBlur4(pixels, sw, sh);
        int offset = isLightBlur ? 128 : 0;
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int r = ((c >> 16) & 0xFF) / 2 + offset;
            int g = ((c >> 8) & 0xFF) / 2 + offset;
            int b = (c & 0xFF) / 2 + offset;
            pixels[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
        }
        small.setPixels(pixels, 0, sw, 0, 0, sw, sh);
        return small;
    }

    private static void gaussianBlur4(int[] pixels, int w, int h) {
        // Separable 1D Gaussian kernel (sigma ~ 2.0, radius 4 -> 9 taps) over <= 100px buffer.
        final float[] k = {0.027f, 0.065f, 0.122f, 0.176f, 0.220f, 0.176f, 0.122f, 0.065f, 0.027f};
        final int r = 4;
        int[] tmp = new int[pixels.length];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                float sr = 0f, sg = 0f, sb = 0f;
                for (int d = -r; d <= r; d++) {
                    int sx = Math.max(0, Math.min(w - 1, x + d));
                    int p = pixels[row + sx];
                    float wgt = k[d + r];
                    sr += ((p >> 16) & 0xFF) * wgt;
                    sg += ((p >> 8) & 0xFF) * wgt;
                    sb += (p & 0xFF) * wgt;
                }
                tmp[row + x] = 0xFF000000
                        | (clampByte(Math.round(sr)) << 16)
                        | (clampByte(Math.round(sg)) << 8)
                        | clampByte(Math.round(sb));
            }
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                float sr = 0f, sg = 0f, sb = 0f;
                for (int d = -r; d <= r; d++) {
                    int sy = Math.max(0, Math.min(h - 1, y + d));
                    int p = tmp[sy * w + x];
                    float wgt = k[d + r];
                    sr += ((p >> 16) & 0xFF) * wgt;
                    sg += ((p >> 8) & 0xFF) * wgt;
                    sb += (p & 0xFF) * wgt;
                }
                pixels[y * w + x] = 0xFF000000
                        | (clampByte(Math.round(sr)) << 16)
                        | (clampByte(Math.round(sg)) << 8)
                        | clampByte(Math.round(sb));
            }
        }
    }

    private static int clampByte(int v) {
        return Math.max(0, Math.min(255, v));
    }

    // =========================================================================
    // Config loader & View-Tree compiler
    // =========================================================================

    private static StyleConfig loadStyleConfig(int styleId, String assetDir) {
        String resolvedDir = (assetDir != null && !assetDir.isEmpty()) ? assetDir : CAMERA_ASSET_DIR;
        String key = resolvedDir + "|" + styleId;
        StyleConfig cached = CONFIG_CACHE.get(key);
        if (cached != null) return cached;

        File styleDir = new File(new File(resolvedDir, "leica"), String.valueOf(styleId));
        File cfgFile = new File(styleDir, "config.json");
        if (!cfgFile.isFile()) {
            Log.e(TAG, "missing leica config: " + cfgFile.getAbsolutePath(), new IllegalStateException("missing config"));
            return null;
        }
        try {
            String rawStr = new String(Files.readAllBytes(cfgFile.toPath()), StandardCharsets.UTF_8);
            JSONObject raw = new JSONObject(rawStr);

            Map<String, Map<String, String>> i18n = new HashMap<>();
            File i18nFile = new File(styleDir, "i18n.json");
            if (i18nFile.isFile()) {
                JSONObject i18nJson = new JSONObject(
                        new String(Files.readAllBytes(i18nFile.toPath()), StandardCharsets.UTF_8));
                Iterator<String> keys = i18nJson.keys();
                while (keys.hasNext()) {
                    String ik = keys.next();
                    JSONObject entry = i18nJson.optJSONObject(ik);
                    if (entry != null) {
                        Map<String, String> lm = new HashMap<>();
                        Iterator<String> lks = entry.keys();
                        while (lks.hasNext()) {
                            String lk = lks.next();
                            lm.put(lk, entry.optString(lk, ""));
                        }
                        i18n.put(ik, lm);
                    }
                }
            }

            Map<String, BgPalette> bgs = new HashMap<>();
            JSONObject wmRes = raw.optJSONObject("wmRes");
            JSONArray bgArr = wmRes != null ? wmRes.optJSONArray("background") : null;
            if (bgArr != null) {
                for (int i = 0; i < bgArr.length(); i++) {
                    JSONObject entry = bgArr.optJSONObject(i);
                    if (entry == null) continue;
                    String name = entry.optString("name", "white");
                    String value = entry.optString("value", "@color=#FFFFFFFF");
                    List<String> fg = new ArrayList<>();
                    JSONArray fgArr = entry.optJSONArray("foreground");
                    if (fgArr != null) {
                        for (int j = 0; j < fgArr.length(); j++) {
                            fg.add(fgArr.optString(j, ""));
                        }
                    }
                    bgs.put(name, new BgPalette(name, value, fg));
                }
            }

            Map<String, List<JSONObject>> groups = new HashMap<>();
            JSONArray groupArr = raw.optJSONArray("layout_group");
            if (groupArr != null) {
                for (int i = 0; i < groupArr.length(); i++) {
                    JSONObject item = groupArr.optJSONObject(i);
                    if (item == null) continue;
                    Iterator<String> gks = item.keys();
                    while (gks.hasNext()) {
                        String gk = gks.next();
                        JSONArray arr = item.optJSONArray(gk);
                        List<JSONObject> list = new ArrayList<>();
                        if (arr != null) {
                            for (int j = 0; j < arr.length(); j++) {
                                JSONObject n = arr.optJSONObject(j);
                                if (n != null) list.add(n);
                            }
                        }
                        groups.put(gk, list);
                    }
                }
            }

            StyleConfig cfg = new StyleConfig(styleDir, raw, i18n, bgs, groups);
            CONFIG_CACHE.putIfAbsent(key, cfg);
            return cfg;
        } catch (Throwable error) {
            Log.e(TAG, "failed loading style " + styleId, error);
            return null;
        }
    }

    private static BgPalette resolveEffectivePalette(
            StyleConfig cfg, LeicaStyles.StylePreset preset, LeicaStyles.BgChoice choice) {
        if (choice == null || cfg.backgrounds.isEmpty()) {
            return new BgPalette("white", "@color=#FFFFFFFF", Collections.emptyList());
        }
        if ("absorb".equals(choice.name)) {
            int r = Color.red(choice.customBgColor);
            int g = Color.green(choice.customBgColor);
            int b = Color.blue(choice.customBgColor);
            double lum = 0.299 * r + 0.587 * g + 0.114 * b;
            BgPalette ref = cfg.backgrounds.get(lum > 128.0 ? "white" : "black");
            if (ref == null) ref = cfg.backgrounds.values().iterator().next();
            String hex = String.format(Locale.US, "#FF%02X%02X%02X", r, g, b);
            return new BgPalette("absorb", "@color=" + hex, ref.foreground);
        }
        BgPalette exact = cfg.backgrounds.get(choice.name);
        if (exact != null) return exact;
        if (choice.isBlur) {
            BgPalette blur = cfg.backgrounds.get(choice.isLightBlur ? "light_blur" : "dark_blur");
            if (blur != null) return blur;
            BgPalette blk = cfg.backgrounds.get("black");
            if (blk != null) return new BgPalette("dark_blur", "@algo=dark_blur", blk.foreground);
        }
        BgPalette fallback = cfg.backgrounds.get(preset.defaultBackground);
        return fallback != null ? fallback : cfg.backgrounds.values().iterator().next();
    }

    private static String resolveEffectiveLayoutKey(
            StyleConfig cfg, LeicaStyles.StylePreset preset, LeicaStyles.PhotoMeta meta) {
        String locOpt = preset.locationOption;
        boolean hasLoc = meta != null
                && (!meta.address.isEmpty() || (meta.latitude != null && meta.longitude != null));
        if (("location_off".equals(locOpt) || !hasLoc) && cfg.layoutGroups.containsKey("layout_loc_off")) {
            return "layout_loc_off";
        }
        if (cfg.layoutGroups.containsKey(preset.defaultLayout)) {
            return preset.defaultLayout;
        }
        return cfg.layoutGroups.keySet().iterator().next();
    }

    private static PreparedLayout prepareLayout(
            LeicaStyles.StylePreset preset,
            int imgW,
            int imgH,
            LeicaStyles.PhotoMeta meta,
            String assetDir,
            LeicaStyles.BgChoice bgChoice,
            boolean hasselbladBadge,
            boolean alignEven) {
        StyleConfig cfg = loadStyleConfig(preset.id, assetDir);
        if (cfg == null || cfg.layoutGroups.isEmpty()) return null;

        String resolvedDir = (assetDir != null && !assetDir.isEmpty()) ? assetDir : CAMERA_ASSET_DIR;
        BgPalette bgPalette = resolveEffectivePalette(cfg, preset, bgChoice);
        String layoutKey = resolveEffectiveLayoutKey(cfg, preset, meta);
        Map<String, FontSpec> fonts = parseFonts(cfg.raw, bgPalette.foreground);
        double scale = Math.min(imgW, imgH) / 1080.0;

        ContainerNode root = new ContainerNode(
                "root", DIM_WRAP_CONTENT, DIM_WRAP_CONTENT, GRAVITY_NONE, 0, 0, 0, alignEven, 0, false, "overlap");
        List<JSONObject> tops = cfg.layoutGroups.get(layoutKey);
        if (tops != null) {
            for (JSONObject top : tops) {
                ViewNode built = buildNode(
                        top, cfg, preset, meta, bgPalette, fonts, imgW, imgH, scale,
                        resolvedDir, hasselbladBadge, alignEven, 0);
                if (built != null) root.children.add(built);
            }
        }

        int[] outSize = root.intrinsicSize();
        int cw = alignEven ? evenSize(Math.max(1, outSize[0]), true) : Math.max(1, outSize[0]);
        int ch = alignEven ? evenSize(Math.max(1, outSize[1]), true) : Math.max(1, outSize[1]);
        root.layoutChildren(cw, ch);

        int[] srcBounds = findNodeBounds(root, "src_image_view", 0, 0);
        int padL = snapPos(srcBounds != null ? srcBounds[0] : 0, alignEven);
        int padT = snapPos(srcBounds != null ? srcBounds[1] : 0, alignEven);
        int sw = srcBounds != null ? srcBounds[2] : imgW;
        int sh = srcBounds != null ? srcBounds[3] : imgH;
        int padR = Math.max(0, cw - padL - sw);
        int padB = Math.max(0, ch - padT - sh);
        boolean hasBorder = padL > 0 || padT > 0 || padR > 0 || padB > 0;

        boolean isBlur = bgPalette.value.startsWith("@algo=")
                && (bgPalette.value.contains("light_blur") || bgPalette.value.contains("dark_blur"));
        boolean isLightBlur = isBlur && bgPalette.value.contains("light_blur");
        int bgColorArgb = bgPalette.value.startsWith("@color=")
                ? parseArgbHex(bgPalette.value.substring("@color=".length()), 0xFFFFFFFF)
                : 0;

        Metrics m = new Metrics(
                imgW, imgH, scale, padL, padT, padR, padB, cw, ch, hasBorder, bgColorArgb, isBlur, isLightBlur);
        return new PreparedLayout(m, root, bgPalette, isBlur, isLightBlur, bgColorArgb);
    }

    private static ViewNode buildNode(
            JSONObject nd,
            StyleConfig cfg,
            LeicaStyles.StylePreset preset,
            LeicaStyles.PhotoMeta meta,
            BgPalette bgPalette,
            Map<String, FontSpec> fonts,
            int imgW,
            int imgH,
            double scale,
            String assetDir,
            boolean hasselbladBadge,
            boolean alignEven,
            int containerBg) {
        if (!isSettingEnabled(nd, preset, meta)) return null;

        String ntype = nd.optString("type", "Layout");
        String nid = nd.optString("id", "");
        int ml = resolveDim(nd.optString("margin_left", "0dp"), imgW, imgH, scale, false);
        int mt = resolveDim(nd.optString("margin_top", "0dp"), imgW, imgH, scale, false);
        int degree = nd.optInt("degree", 0);
        int grav = parseGravity(nd.optString("layout_gravity", nd.optString("gravity", "")));

        if ("ImageView".equals(ntype)) {
            String rawPath = nd.optString("path", "");
            if (rawPath.isEmpty()) {
                int dw = resolveDim(nd.optString("display_width", "0dp"), imgW, imgH, scale, false);
                int dh = resolveDim(nd.optString("display_height", "0dp"), imgW, imgH, scale, false);
                int tw = dw > 0 ? dw : imgW;
                int th = dh > 0 ? dh : imgH;
                return new ImageNode(nid, tw, th, grav, ml, mt, degree, alignEven, null, null, tw, th, true);
            }
            File assetFile = resolveImagePath(rawPath, cfg, meta, bgPalette.foreground);
            if (assetFile == null) return null;
            Bitmap origBmp = loadBitmap(assetFile);
            if (origBmp == null) return null;
            int origW = Math.max(1, origBmp.getWidth());
            int origH = Math.max(1, origBmp.getHeight());

            int dw = resolveDim(nd.optString("display_width", "0dp"), imgW, imgH, scale, false);
            int dh = resolveDim(nd.optString("display_height", "0dp"), imgW, imgH, scale, false);
            if (dw > 0 && dh <= 0) {
                dh = Math.max(1, Math.round(origH * (dw / (float) origW)));
            } else if (dh > 0 && dw <= 0) {
                dw = Math.max(1, Math.round(origW * (dh / (float) origH)));
            } else if (dw <= 0 && dh <= 0) {
                dw = Math.max(1, (int) Math.round(origW * scale));
                dh = Math.max(1, (int) Math.round(origH * scale));
            }

            if (hasselbladBadge && isLeicaBadgeNode(nid, assetFile)) {
                int tint = badgeTintArgb(preset, bgPalette, containerBg);
                return createHasselbladBadgeNode(
                        nid, grav, ml, mt, degree, alignEven, origW, origH, dw, dh, tint);
            }
            return new ImageNode(nid, dw, dh, grav, ml, mt, degree, alignEven, origBmp, null, dw, dh, false);
        }

        if ("TextView".equals(ntype)) {
            String rawText = nd.optString("text", "");
            boolean textUpper = nd.optBoolean("text_upper", false);
            String fontRef = nd.optString("font_ref", "f1");
            FontSpec baseFont = fonts.get(fontRef);
            if (baseFont == null && !fonts.isEmpty()) baseFont = fonts.values().iterator().next();
            if (baseFont == null) {
                baseFont = new FontSpec(20f, 0xFF000000, "default", 400, 0f, 0f, 0f, 0f, 0);
            }
            FontSpec fontSpec = baseFont;
            if (nd.has("letterSpacing") || nd.has("letter_spacing")) {
                double ls = nd.has("letterSpacing")
                        ? nd.optDouble("letterSpacing", baseFont.letterSpacing)
                        : nd.optDouble("letter_spacing", baseFont.letterSpacing);
                fontSpec = baseFont.withLetterSpacing((float) ls);
            }

            String resolvedText = resolveTextExpr(rawText, cfg, preset, meta, hasselbladBadge, textUpper);
            if (resolvedText.isEmpty()) return null;

            LeicaStyles.TextSpan[] spans = null;
            if ("model".equals(nid)) {
                ml = 0;
                spans = LeicaStyles.formatPureTextModelSpans(meta.marketName, fontSpec.weight, textUpper);
                if (spans.length == 0) return null;
            }

            int[] limits = parseTextLimits(nd, rawText, imgW, imgH, scale);
            RectParamsSpec rectSpec = null;
            JSONObject rp = nd.optJSONObject("rect_params");
            if (rp != null) {
                int lineCol = resolveColorToken(rp.optString("rect_line_color", "#FF000000"), bgPalette.foreground);
                int fillCol = resolveColorToken(rp.optString("rect_fill_color", ""), bgPalette.foreground);
                int rwRaw = resolveDim(rp.optString("rect_width", "wrap_content"), imgW, imgH, scale, false);
                int rhRaw = resolveDim(rp.optString("rect_height", "wrap_content"), imgW, imgH, scale, false);
                JSONArray radArr = rp.optJSONArray("rect_radius");
                float rad = radArr != null && radArr.length() > 0 ? (float) radArr.optDouble(0, 8.0) : 8f;
                rectSpec = new RectParamsSpec(
                        (float) rp.optDouble("rect_text_width_gap", 10.0),
                        (float) rp.optDouble("rect_text_height_gap", 0.0),
                        (float) rp.optDouble("rect_line_width", 2.0),
                        rad,
                        lineCol,
                        fillCol,
                        rwRaw > 0 ? rwRaw : -1,
                        rhRaw > 0 ? rhRaw : -1);
            }

            return new TextNode(
                    nid, grav, ml, mt, degree, alignEven, resolvedText, spans,
                    cfg.styleDir, assetDir, fontSpec, scale, rectSpec, limits[0], limits[1]);
        }

        // Container
        int bgColor = 0;
        boolean containerUsesPhotoBlur = false;
        String rawBg = nd.optString("background", "");
        boolean isFramePaddingBox = "horizontal_padding_48".equals(nid)
                || "vertical_padding_48".equals(nid)
                || "bottom_container".equals(nid);
        if (!rawBg.isEmpty()) {
            if (isFramePaddingBox && "absorb".equals(bgPalette.name) && bgPalette.value.startsWith("@color=")) {
                bgColor = parseArgbHex(bgPalette.value.substring("@color=".length()), 0);
            } else if (rawBg.startsWith("@foreground=") || rawBg.startsWith("#")) {
                bgColor = resolveColorToken(rawBg, bgPalette.foreground);
            } else if (rawBg.startsWith("@background=")) {
                BgPalette targetBg = nd.optBoolean("background_optional", false)
                        ? bgPalette
                        : cfg.backgrounds.get(rawBg.substring("@background=".length()));
                if (targetBg == null) targetBg = bgPalette;
                if (targetBg.value.startsWith("@color=")) {
                    bgColor = parseArgbHex(targetBg.value.substring("@color=".length()), 0);
                } else if (targetBg.value.startsWith("@algo=")) {
                    containerUsesPhotoBlur = true;
                }
            }
        }
        int effBg = Color.alpha(bgColor) >= 128 ? bgColor : containerBg;
        String orient = nd.optString("orientation", "overlap");
        JSONArray childrenJson = nd.optJSONArray("childViews");
        boolean hadChildren = childrenJson != null && childrenJson.length() > 0;
        List<ViewNode> builtChildren = new ArrayList<>();
        if (childrenJson != null) {
            for (int i = 0; i < childrenJson.length(); i++) {
                JSONObject chObj = childrenJson.optJSONObject(i);
                if (chObj == null) continue;
                ViewNode ch = buildNode(
                        chObj, cfg, preset, meta, bgPalette, fonts, imgW, imgH, scale,
                        assetDir, hasselbladBadge, alignEven, effBg);
                if (ch != null) builtChildren.add(ch);
            }
        }
        builtChildren = pruneDividers(builtChildren, orient);
        if (hadChildren && builtChildren.isEmpty()) return null;

        int wSpec = resolveDim(nd.optString("layout_width", "wrap_content"), imgW, imgH, scale, alignEven);
        int hSpec = resolveDim(nd.optString("layout_height", "wrap_content"), imgW, imgH, scale, alignEven);
        ContainerNode cn = new ContainerNode(
                nid, wSpec, hSpec, grav, ml, mt, degree, alignEven, bgColor, containerUsesPhotoBlur, orient);
        cn.children.addAll(builtChildren);
        return cn;
    }

    private static boolean isSettingEnabled(
            JSONObject nd, LeicaStyles.StylePreset preset, LeicaStyles.PhotoMeta meta) {
        String nid = nd.optString("id", "");
        String rawText = nd.optString("text", "");
        String rawPath = nd.optString("path", "");

        // Pure-text mode omits OEM logo bitmap and model_versionName pill; "model" renders the full name.
        if ("logo".equals(nid) || "model_versionName".equals(nid)) return false;

        String sType = nd.optString("setting_optional_type", "");
        if ("type_model".equals(sType)) return preset.modelSwitch;
        if ("type_exif".equals(sType)) return preset.exifSwitch;
        if ("type_time".equals(sType)) return preset.timeSwitch;
        if ("type_location".equals(sType)) return !"location_off".equals(preset.locationOption);
        if ("type_custom_signature".equals(sType)) return false;
        if ("type_custom_text".equals(sType)) return meta != null && !meta.customText.isEmpty();
        if ("type_leica_lens_container".equals(sType)) return meta != null && !meta.cvLens.isEmpty();
        if ("type_leica_looks".equals(sType) || "type_leica_looks_container".equals(sType)) {
            return meta != null && !meta.filterName.isEmpty();
        }

        if (!preset.modelSwitch && ("brand_layout".equals(nid) || rawText.startsWith("@model="))) return false;
        if (!preset.exifSwitch && (nid.startsWith("exif") || nid.startsWith("parameter_")
                || "S".equals(nid) || "ISO".equals(nid) || "mm".equals(nid) || "F".equals(nid)
                || rawText.startsWith("@exif="))) {
            return false;
        }
        if (rawText.startsWith("@location=") || "location".equals(nid)) {
            return !"location_off".equals(preset.locationOption);
        }
        if ("time".equals(nid) || rawText.startsWith("@time=")) return preset.timeSwitch;
        if ("custom_signature_layout".equals(nid) || "custom_signature".equals(nid)
                || rawPath.contains("icon_signature")) {
            return false;
        }
        if (rawText.startsWith("@custom=") || "custom_text".equals(nid)) {
            return meta != null && !meta.customText.isEmpty();
        }
        if ("leica_lens".equals(nid) || "type_leica_lens".equals(nid)
                || rawPath.contains("cvLens") || rawPath.contains("@type_leica_lens")) {
            return meta != null && !meta.cvLens.isEmpty();
        }
        if (rawText.startsWith("@filter=") || rawPath.contains("@type_leica_looks")) {
            return meta != null && !meta.filterName.isEmpty();
        }
        return nd.optBoolean("enable", true);
    }

    private static List<ViewNode> pruneDividers(List<ViewNode> children, String orient) {
        if (!"horizontal".equals(orient) && !"vertical".equals(orient)) return children;
        boolean hasDiv = false;
        for (ViewNode c : children) {
            if (isDivider(c)) {
                hasDiv = true;
                break;
            }
        }
        if (!hasDiv) return children;
        List<ViewNode> out = new ArrayList<>();
        for (ViewNode ch : children) {
            if (isDivider(ch)) {
                if (!out.isEmpty() && !isDivider(out.get(out.size() - 1))) out.add(ch);
            } else {
                out.add(ch);
            }
        }
        while (!out.isEmpty() && isDivider(out.get(out.size() - 1))) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static boolean isDivider(ViewNode n) {
        return "line_layout".equals(n.nodeId) || "separator".equals(n.nodeId);
    }

    private static boolean isLeicaBadgeNode(String nid, File assetFile) {
        String name = assetFile.getName().toLowerCase(Locale.US);
        if (name.startsWith("leica_lens") || name.startsWith("leica_looks")) return false;
        return "leica".equals(nid)
                || "leica.webp".equals(name)
                || "leica_p3.webp".equals(name)
                || "leica_white.webp".equals(name)
                || "leica_black.webp".equals(name);
    }

    private static int badgeTintArgb(
            LeicaStyles.StylePreset preset, BgPalette bgPalette, int containerBg) {
        if (preset.id == 111) return 0xFFE6A54F;
        if (Color.alpha(containerBg) >= 128) {
            double lum = 0.299 * Color.red(containerBg)
                    + 0.587 * Color.green(containerBg)
                    + 0.114 * Color.blue(containerBg);
            return lum > 128.0 ? 0xFF000000 : 0xFFFFFFFF;
        }
        if ("white".equals(bgPalette.name) || "light_blur".equals(bgPalette.name)) return 0xFF000000;
        if ("absorb".equals(bgPalette.name) && bgPalette.value.startsWith("@color=")) {
            int col = parseArgbHex(bgPalette.value.substring("@color=".length()), 0xFFFFFFFF);
            double lum = 0.299 * Color.red(col) + 0.587 * Color.green(col) + 0.114 * Color.blue(col);
            return lum > 128.0 ? 0xFF000000 : 0xFFFFFFFF;
        }
        return 0xFFFFFFFF;
    }

    private static ImageNode createHasselbladBadgeNode(
            String nid,
            int grav,
            int ml,
            int mt,
            int degree,
            boolean alignEven,
            int origW,
            int origH,
            int dw,
            int dh,
            int tintArgb) {
        float aspect = origW / (float) Math.max(1, origH);
        if (aspect <= 1.3f) {
            int side = Math.max(1, dh > 0 ? dh : dw);
            boolean isDark = (0.299 * Color.red(tintArgb)
                    + 0.587 * Color.green(tintArgb)
                    + 0.114 * Color.blue(tintArgb)) < 128.0;
            PhocusStyles.VectorSpec base = isDark
                    ? PhocusStyles.VEC_HBLACK_LOGO
                    : PhocusStyles.VEC_HWHITE_LOGO;
            PhocusStyles.VectorSpec tinted = new PhocusStyles.VectorSpec(
                    base.viewportWidth, base.viewportHeight, tintArgb, base.pathData);
            return new ImageNode(nid, side, side, grav, ml, mt, degree, alignEven, null, tinted, side, side, false);
        }
        int boxH = Math.max(1, dh);
        int wordH = Math.max(1, Math.round(boxH * 0.78f));
        int wordW = Math.max(1, Math.round(wordH * (182.0f / 18.0f)));
        PhocusStyles.VectorSpec base = PhocusStyles.VEC_HASSELBLAD_LOGO;
        PhocusStyles.VectorSpec tinted = new PhocusStyles.VectorSpec(
                base.viewportWidth, base.viewportHeight, tintArgb, base.pathData);
        return new ImageNode(nid, wordW, boxH, grav, ml, mt, degree, alignEven, null, tinted, wordW, boxH, false);
    }

    private static File resolveImagePath(
            String rawPath, StyleConfig cfg, LeicaStyles.PhotoMeta meta, List<String> fgPalette) {
        Map<String, String> params = new HashMap<>();
        for (String kv : rawPath.split(";")) {
            int eq = kv.indexOf('=');
            if (eq > 0) params.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
        }
        String ptype = params.getOrDefault("pathType", "direct");
        String pathExpr = params.getOrDefault("path", "");
        if (pathExpr.isEmpty()) return null;

        if ("direct".equals(ptype)) {
            File f = new File(cfg.styleDir, pathExpr);
            return f.isFile() ? f : null;
        }

        String fgSuffix = "";
        String fgStr = params.get("foreground");
        if (fgStr != null) {
            try {
                int idx = Integer.parseInt(fgStr);
                if (idx >= 0 && idx < fgPalette.size()) fgSuffix = fgPalette.get(idx);
            } catch (NumberFormatException ignored) {
            }
        }

        if ("select".equals(ptype)) {
            if (pathExpr.contains("@type_leica_lens")) {
                if (meta == null || meta.cvLens.isEmpty()) return null;
                pathExpr = pathExpr.replace("@type_leica_lens", "leica_lens_" + meta.cvLens.toLowerCase(Locale.US));
            }
            if (pathExpr.contains("@type_leica_looks")) {
                if (meta == null || meta.filterName.isEmpty()) return null;
                String key = meta.filterName.replace(' ', '_').toLowerCase(Locale.US);
                pathExpr = pathExpr.replace("@type_leica_looks", "leica_looks_" + key);
            }
            if (pathExpr.contains("@{cvLens}")) {
                if (meta == null || meta.cvLens.isEmpty()) return null;
                pathExpr = pathExpr.replace("@{cvLens}", meta.cvLens);
            }
            String stem = fgSuffix.isEmpty() ? pathExpr : (pathExpr + "_" + fgSuffix);
            File f = new File(cfg.styleDir, stem + ".webp");
            return f.isFile() ? f : null;
        }
        return null;
    }

    private static String resolveTextExpr(
            String rawText,
            StyleConfig cfg,
            LeicaStyles.StylePreset preset,
            LeicaStyles.PhotoMeta meta,
            boolean hasselbladBadge,
            boolean textUpper) {
        if (rawText.startsWith("@model=")) {
            return preset.modelSwitch && meta != null ? meta.marketName : "";
        }
        if (rawText.startsWith("@exif=")) {
            return preset.exifSwitch ? LeicaStyles.formatExifText(rawText, meta) : "";
        }
        if (rawText.startsWith("@time=")) {
            return preset.timeSwitch ? LeicaStyles.formatTimeText(rawText, meta, cfg.i18n, "zh-rCN") : "";
        }
        if (rawText.startsWith("@location=")) {
            return LeicaStyles.formatLocationText(rawText, meta, preset.locationOption);
        }
        if (rawText.startsWith("@custom=")) {
            return meta != null ? meta.customText : "";
        }
        if (rawText.startsWith("@filter=")) {
            return LeicaStyles.formatFilterText(rawText, meta, hasselbladBadge);
        }
        return textUpper ? rawText.toUpperCase(Locale.US) : rawText;
    }

    private static Map<String, FontSpec> parseFonts(JSONObject raw, List<String> fgPalette) {
        Map<String, FontSpec> out = new HashMap<>();
        JSONObject wmRes = raw.optJSONObject("wmRes");
        JSONObject rawFonts = wmRes != null ? wmRes.optJSONObject("fonts") : null;
        if (rawFonts == null) return out;
        Iterator<String> keys = rawFonts.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            JSONObject f = rawFonts.optJSONObject(k);
            if (f == null) continue;
            float size = (float) f.optDouble("size", 20.0);
            int col = resolveColorToken(f.optString("color", "#FFFFFFFF"), fgPalette);
            String tf = f.optString("typeface", "default");
            int wght = 400;
            String var = f.optString("variation", "");
            if (!var.isEmpty()) {
                Matcher m = WGHT_PATTERN.matcher(var);
                if (m.find()) {
                    try { wght = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
                }
            }
            float ls = (float) (f.has("letterSpacing")
                    ? f.optDouble("letterSpacing", 0.0)
                    : f.optDouble("letter_spacing", 0.0));
            float sRad = 0f, sDx = 0f, sDy = 0f;
            int sCol = 0;
            JSONArray sh = f.optJSONArray("shadow");
            if (sh != null && sh.length() >= 4) {
                sRad = parseFloatLiteral(sh.optString(0, "0"));
                sDx = parseFloatLiteral(sh.optString(1, "0"));
                sDy = parseFloatLiteral(sh.optString(2, "0"));
                sCol = parseArgbHex(sh.optString(3, "#00000000"), 0);
            }
            out.put(k, new FontSpec(size, col, tf, wght, ls, sRad, sDx, sDy, sCol));
        }
        return out;
    }

    private static float parseFloatLiteral(String s) {
        String clean = s.trim();
        if (clean.endsWith("f") || clean.endsWith("F")) clean = clean.substring(0, clean.length() - 1);
        try { return Float.parseFloat(clean); } catch (NumberFormatException ignored) { return 0f; }
    }

    private static int resolveColorToken(String token, List<String> fgPalette) {
        if (token == null || token.isEmpty()) return 0;
        String t = token.trim();
        if (t.startsWith("@foreground=")) {
            try {
                int idx = Integer.parseInt(t.substring("@foreground=".length()));
                if (idx >= 0 && idx < fgPalette.size()) t = fgPalette.get(idx);
                else return 0;
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return t.startsWith("#") ? parseArgbHex(t, 0) : 0;
    }

    private static int parseArgbHex(String hex, int fallback) {
        try {
            return Color.parseColor(hex.trim());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static int parseGravity(String expr) {
        if (expr == null || expr.isEmpty()) return GRAVITY_NONE;
        int mask = 0;
        for (String part : expr.split("\\|")) {
            String k = part.trim().toLowerCase(Locale.US);
            switch (k) {
                case "top": mask |= GRAVITY_TOP; break;
                case "bottom": mask |= GRAVITY_BOTTOM; break;
                case "left": mask |= GRAVITY_LEFT; break;
                case "right": mask |= GRAVITY_RIGHT; break;
                case "center_vertical": mask |= GRAVITY_CENTER_VERTICAL; break;
                case "center_horizontal": mask |= GRAVITY_CENTER_HORIZONTAL; break;
                case "center": mask |= (GRAVITY_CENTER_VERTICAL | GRAVITY_CENTER_HORIZONTAL); break;
                default: break;
            }
        }
        return mask;
    }

    private static int resolveDim(String expr, int imgW, int imgH, double scale, boolean alignEven) {
        if (expr == null || expr.isEmpty()) return 0;
        String s = expr.trim();
        if ("wrap_content".equals(s)) return DIM_WRAP_CONTENT;
        if ("match_parent".equals(s)) return DIM_MATCH_PARENT;
        if ("fit_content".equals(s)) return -3;
        int res;
        if (s.endsWith("%w")) {
            double v = Double.parseDouble(s.substring(0, s.length() - 2));
            res = (int) ((v / 100.0) * imgW);
        } else if (s.endsWith("%h")) {
            double v = Double.parseDouble(s.substring(0, s.length() - 2));
            res = (int) ((v / 100.0) * imgH);
        } else {
            if (s.endsWith("dp") || s.endsWith("px") || s.endsWith("sp")) {
                s = s.substring(0, s.length() - 2);
            }
            try {
                res = (int) (Double.parseDouble(s) * scale);
            } catch (NumberFormatException ignored) {
                return parseGravity(s);
            }
        }
        if (alignEven && res > 0) res += (res % 2);
        return res;
    }

    private static int[] parseTextLimits(
            JSONObject nd, String rawText, int imgW, int imgH, double scale) {
        int maxW = 0;
        int maxLines = 0;
        String lw = nd.optString("layout_width", "");
        if (!lw.isEmpty() && !"wrap_content".equals(lw) && !"match_parent".equals(lw)) {
            int dim = resolveDim(lw, imgW, imgH, scale, false);
            if (dim > 0) maxW = dim;
        }
        Matcher mLim = LOC_LIMIT_PATTERN.matcher(rawText);
        if (mLim.find()) {
            try {
                int lp = Math.max(1, (int) Math.round(Double.parseDouble(mLim.group(1)) * imgW));
                maxW = maxW > 0 ? Math.min(maxW, lp) : lp;
            } catch (NumberFormatException ignored) {}
        }
        if (rawText.startsWith("@custom=")) {
            try {
                String v = rawText.substring("@custom=".length()).split(";", 2)[0];
                double r = Double.parseDouble(v);
                if (r > 0.0 && r <= 1.0) {
                    int lp = Math.max(1, (int) Math.round(r * imgW));
                    maxW = maxW > 0 ? Math.min(maxW, lp) : lp;
                }
            } catch (NumberFormatException ignored) {}
        }
        Matcher mLines = LOC_LINES_PATTERN.matcher(rawText);
        if (mLines.find()) {
            try { maxLines = Math.max(1, Integer.parseInt(mLines.group(1))); } catch (NumberFormatException ignored) {}
        }
        return new int[] {maxW, maxLines};
    }

    // =========================================================================
    // View Hierarchy Nodes (matching Hn.a/c/e/f/g/h/j/l/m)
    // =========================================================================

    private static int alignX(int parentW, int childW, int gravity, int ml) {
        if ((gravity & GRAVITY_RIGHT) != 0) return parentW - childW + ml;
        if ((gravity & GRAVITY_CENTER_HORIZONTAL) != 0) return (parentW - childW) / 2 + ml;
        return ml;
    }

    private static int alignY(int parentH, int childH, int gravity, int mt) {
        if ((gravity & GRAVITY_BOTTOM) != 0) return parentH - childH + mt;
        if ((gravity & GRAVITY_CENTER_VERTICAL) != 0) return (parentH - childH) / 2 + mt;
        return mt;
    }

    private static int evenSize(int v, boolean align) {
        return (align && v > 0) ? ((v + 1) & ~1) : v;
    }

    private static int snapPos(int v, boolean align) {
        return align ? (v & ~1) : v;
    }

    private abstract static class ViewNode {
        final String nodeId;
        final int widthSpec;
        final int heightSpec;
        final int gravity;
        final int marginLeft;
        final int marginTop;
        final int degree;
        final boolean alignEven;
        int relX;
        int relY;
        int layoutW;
        int layoutH;

        ViewNode(String nodeId, int widthSpec, int heightSpec, int gravity,
                 int marginLeft, int marginTop, int degree, boolean alignEven) {
            this.nodeId = nodeId;
            this.widthSpec = widthSpec;
            this.heightSpec = heightSpec;
            this.gravity = gravity;
            this.marginLeft = marginLeft;
            this.marginTop = marginTop;
            this.degree = degree;
            this.alignEven = alignEven;
        }

        abstract int[] intrinsicSize();

        void layoutChildren(int boxW, int boxH) {
            this.layoutW = boxW;
            this.layoutH = boxH;
        }

        abstract void drawInto(Canvas canvas, int absX, int absY, boolean skipBlurBg);
    }

    private static final class ImageNode extends ViewNode {
        final Bitmap bitmap;
        final PhocusStyles.VectorSpec vector;
        final int targetW;
        final int targetH;
        final boolean isSourcePhoto;

        ImageNode(String nodeId, int w, int h, int grav, int ml, int mt, int deg, boolean alignEven,
                  Bitmap bitmap, PhocusStyles.VectorSpec vector, int targetW, int targetH, boolean isSourcePhoto) {
            super(nodeId, w, h, grav, ml, mt, deg, alignEven);
            this.bitmap = bitmap;
            this.vector = vector;
            this.targetW = targetW;
            this.targetH = targetH;
            this.isSourcePhoto = isSourcePhoto;
        }

        @Override
        int[] intrinsicSize() {
            int w = targetW > 0 ? targetW : (bitmap != null ? bitmap.getWidth() : 0);
            int h = targetH > 0 ? targetH : (bitmap != null ? bitmap.getHeight() : 0);
            return (degree % 180 != 0) ? new int[] {h, w} : new int[] {w, h};
        }

        @Override
        void drawInto(Canvas canvas, int absX, int absY, boolean skipBlurBg) {
            if (isSourcePhoto || layoutW <= 0 || layoutH <= 0) return;
            int preW = (degree % 180 != 0) ? layoutH : layoutW;
            int preH = (degree % 180 != 0) ? layoutW : layoutH;
            canvas.save();
            applyRotation(canvas, absX, absY, layoutW, layoutH, degree);
            if (vector != null) {
                drawVectorBadge(canvas, vector, preW, preH);
            } else if (bitmap != null && !bitmap.isRecycled()) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                canvas.drawBitmap(bitmap, null, new Rect(0, 0, preW, preH), p);
            }
            canvas.restore();
        }
    }

    private static final class TextNode extends ViewNode {
        final String text;
        final LeicaStyles.TextSpan[] spans;
        final File styleDir;
        final String assetDir;
        final FontSpec fontSpec;
        final double scale;
        final RectParamsSpec rectParams;
        final int maxWidth;
        final int maxLines;

        private boolean measured;
        private List<String> lines = Collections.emptyList();
        private Paint[] spanPaints;
        private float[] spanAscents;
        private float maxAscent;
        private int textW;
        private int textH;
        private int lineHeight;
        private Paint textPaint;
        private float baseAscent;

        TextNode(String nodeId, int grav, int ml, int mt, int deg, boolean alignEven,
                 String text, LeicaStyles.TextSpan[] spans, File styleDir, String assetDir,
                 FontSpec fontSpec, double scale, RectParamsSpec rectParams, int maxWidth, int maxLines) {
            super(nodeId, DIM_WRAP_CONTENT, DIM_WRAP_CONTENT, grav, ml, mt, deg, alignEven);
            this.text = text;
            this.spans = spans;
            this.styleDir = styleDir;
            this.assetDir = assetDir;
            this.fontSpec = fontSpec;
            this.scale = scale;
            this.rectParams = rectParams;
            this.maxWidth = maxWidth;
            this.maxLines = maxLines;
        }

        private void ensureMeasured() {
            if (measured) return;
            measured = true;
            float px = Math.max(1f, (float) Math.round(fontSpec.size * scale));
            if (spans != null && spans.length > 0) {
                spanPaints = new Paint[spans.length];
                spanAscents = new float[spans.length];
                float totalW = 0f;
                float mAsc = 1f;
                float mDesc = 1f;
                for (int i = 0; i < spans.length; i++) {
                    Paint p = makePaint(spans[i].weight, px);
                    Paint.FontMetrics fm = p.getFontMetrics();
                    float asc = Math.max(1f, -fm.ascent);
                    float desc = Math.max(0f, fm.descent);
                    mAsc = Math.max(mAsc, asc);
                    mDesc = Math.max(mDesc, desc);
                    spanPaints[i] = p;
                    spanAscents[i] = asc;
                    totalW += p.measureText(spans[i].text);
                }
                maxAscent = mAsc;
                lineHeight = Math.max(1, (int) Math.ceil(mAsc + mDesc));
                textW = Math.max(1, (int) Math.ceil(totalW));
                textH = lineHeight;
            } else {
                textPaint = makePaint(fontSpec.weight, px);
                Paint.FontMetrics fm = textPaint.getFontMetrics();
                baseAscent = -fm.ascent;
                lineHeight = Math.max(1, (int) Math.ceil(fm.descent - fm.ascent));
                lines = wrapLines(text, textPaint, maxWidth, maxLines);
                float maxLineW = 0f;
                for (String l : lines) {
                    maxLineW = Math.max(maxLineW, textPaint.measureText(l));
                }
                textW = Math.max(1, (int) Math.ceil(maxLineW));
                textH = Math.max(1, lineHeight * Math.max(1, lines.size()));
            }
        }

        private Paint makePaint(int weight, float px) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            p.setTypeface(loadTypeface(styleDir, assetDir, fontSpec.typeface, weight));
            p.setTextSize(px);
            p.setColor(fontSpec.color);
            if (Math.abs(fontSpec.letterSpacing) > 1e-4f) {
                p.setLetterSpacing(fontSpec.letterSpacing);
            }
            if (fontSpec.shadowRadius > 0f && Color.alpha(fontSpec.shadowColor) > 0) {
                float rad = Math.max(0.5f, (float) (fontSpec.shadowRadius * scale));
                float dx = (float) (fontSpec.shadowDx * scale);
                float dy = (float) (fontSpec.shadowDy * scale);
                p.setShadowLayer(rad, dx, dy, fontSpec.shadowColor);
            }
            return p;
        }

        @Override
        int[] intrinsicSize() {
            ensureMeasured();
            int w, h;
            if (rectParams == null) {
                w = textW;
                h = textH;
            } else {
                int gapW = (int) (rectParams.textWidthGap * 2.0 * scale);
                int gapH = (int) (rectParams.textHeightGap * 2.0 * scale);
                w = rectParams.fixedW > 0 ? rectParams.fixedW : (textW + gapW);
                h = rectParams.fixedH > 0 ? rectParams.fixedH : (textH + gapH);
            }
            return (degree % 180 != 0) ? new int[] {h, w} : new int[] {w, h};
        }

        @Override
        void drawInto(Canvas canvas, int absX, int absY, boolean skipBlurBg) {
            ensureMeasured();
            if (layoutW <= 0 || layoutH <= 0) return;
            int preW = (degree % 180 != 0) ? layoutH : layoutW;
            int preH = (degree % 180 != 0) ? layoutW : layoutH;
            canvas.save();
            applyRotation(canvas, absX, absY, layoutW, layoutH, degree);
            if (rectParams != null) {
                float stroke = (float) Math.ceil(rectParams.lineWidth * scale);
                float rad = (float) (rectParams.radius * scale);
                float half = stroke * 0.5f;
                RectF box = new RectF(half, half, Math.max(half + 1f, preW - half), Math.max(half + 1f, preH - half));
                if (Color.alpha(rectParams.fillColor) > 0) {
                    Paint fp = new Paint(Paint.ANTI_ALIAS_FLAG);
                    fp.setStyle(Paint.Style.FILL);
                    fp.setColor(rectParams.fillColor);
                    canvas.drawRoundRect(box, rad, rad, fp);
                }
                if (Color.alpha(rectParams.lineColor) > 0 && stroke > 0f) {
                    Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
                    sp.setStyle(Paint.Style.STROKE);
                    sp.setStrokeWidth(stroke);
                    sp.setColor(rectParams.lineColor);
                    canvas.drawRoundRect(box, rad, rad, sp);
                }
                float tx = (preW - textW) * 0.5f;
                float ty = (preH - textH) * 0.5f;
                drawTextAt(canvas, tx, ty);
            } else {
                drawTextAt(canvas, 0f, 0f);
            }
            canvas.restore();
        }

        private void drawTextAt(Canvas canvas, float ox, float oy) {
            if (spans != null && spanPaints != null) {
                float cx = ox;
                float baseline = oy + maxAscent;
                for (int i = 0; i < spans.length; i++) {
                    canvas.drawText(spans[i].text, cx, baseline, spanPaints[i]);
                    cx += spanPaints[i].measureText(spans[i].text);
                }
            } else if (textPaint != null) {
                for (int i = 0; i < lines.size(); i++) {
                    canvas.drawText(lines.get(i), ox, oy + i * lineHeight + baseAscent, textPaint);
                }
            }
        }
    }

    private static final class ContainerNode extends ViewNode {
        final int bgColor;
        final boolean usesBlurBg;
        final String orientation;
        final List<ViewNode> children = new ArrayList<>();
        private int[] cachedPre;

        ContainerNode(String nodeId, int wSpec, int hSpec, int grav, int ml, int mt,
                      int deg, boolean alignEven, int bgColor, boolean usesBlurBg, String orient) {
            super(nodeId, wSpec, hSpec, grav, ml, mt, deg, alignEven);
            this.bgColor = bgColor;
            this.usesBlurBg = usesBlurBg;
            this.orientation = orient;
        }

        private int[] measurePre() {
            if (cachedPre != null) return cachedPre;
            int rawW = 0;
            int rawH = 0;
            if ("horizontal".equals(orientation)) {
                for (ViewNode ch : children) {
                    int[] is = ch.intrinsicSize();
                    int cw = ch.widthSpec > 0 ? ch.widthSpec : is[0];
                    int chH = ch.heightSpec > 0 ? ch.heightSpec : is[1];
                    rawW += cw + ch.marginLeft;
                    rawH = Math.max(rawH, chH + Math.max(0, ch.marginTop));
                }
            } else if ("vertical".equals(orientation)) {
                for (ViewNode ch : children) {
                    int[] is = ch.intrinsicSize();
                    int cw = ch.widthSpec > 0 ? ch.widthSpec : is[0];
                    int chH = ch.heightSpec > 0 ? ch.heightSpec : is[1];
                    rawW = Math.max(rawW, cw + Math.max(0, ch.marginLeft));
                    rawH += chH + ch.marginTop;
                }
            } else {
                for (ViewNode ch : children) {
                    int[] is = ch.intrinsicSize();
                    int cw = ch.widthSpec > 0 ? ch.widthSpec : is[0];
                    int chH = ch.heightSpec > 0 ? ch.heightSpec : is[1];
                    rawW = Math.max(rawW, cw + Math.max(0, ch.marginLeft));
                    rawH = Math.max(rawH, chH + Math.max(0, ch.marginTop));
                }
            }
            int w = widthSpec > 0 ? widthSpec : evenSize(Math.max(0, rawW), alignEven);
            int h = heightSpec > 0 ? heightSpec : evenSize(Math.max(0, rawH), alignEven);
            cachedPre = new int[] {w, h};
            return cachedPre;
        }

        @Override
        int[] intrinsicSize() {
            int[] p = measurePre();
            return (degree % 180 != 0) ? new int[] {p[1], p[0]} : p;
        }

        private int[] resolveChildBox(ViewNode ch, int preW, int preH) {
            int[] is = ch.intrinsicSize();
            int cw = ch.widthSpec == DIM_MATCH_PARENT ? preW : (ch.widthSpec > 0 ? ch.widthSpec : is[0]);
            int chH = ch.heightSpec == DIM_MATCH_PARENT ? preH : (ch.heightSpec > 0 ? ch.heightSpec : is[1]);
            return new int[] {cw, chH};
        }

        @Override
        void layoutChildren(int boxW, int boxH) {
            this.layoutW = boxW;
            this.layoutH = boxH;
            int preW = (degree % 180 != 0) ? boxH : boxW;
            int preH = (degree % 180 != 0) ? boxW : boxH;
            if ("horizontal".equals(orientation)) {
                int cursorX = 0;
                for (ViewNode ch : children) {
                    int[] cb = resolveChildBox(ch, preW, preH);
                    boolean anchorX = (ch.gravity & (GRAVITY_RIGHT | GRAVITY_CENTER_HORIZONTAL)) != 0;
                    int rx;
                    if (anchorX && widthSpec == DIM_MATCH_PARENT) {
                        rx = alignX(preW, cb[0], ch.gravity, ch.marginLeft);
                        ch.relX = snapPos(rx, alignEven);
                    } else {
                        rx = cursorX + ch.marginLeft;
                        ch.relX = snapPos(rx, alignEven);
                        cursorX = ch.relX + cb[0];
                    }
                    int ry = alignY(preH, cb[1], ch.gravity, ch.marginTop);
                    ch.relY = snapPos(ry, alignEven);
                    ch.layoutChildren(cb[0], cb[1]);
                }
            } else if ("vertical".equals(orientation)) {
                int cursorY = 0;
                for (ViewNode ch : children) {
                    int[] cb = resolveChildBox(ch, preW, preH);
                    boolean anchorY = (ch.gravity & (GRAVITY_BOTTOM | GRAVITY_CENTER_VERTICAL)) != 0;
                    int ry;
                    if (anchorY && heightSpec == DIM_MATCH_PARENT) {
                        ry = alignY(preH, cb[1], ch.gravity, ch.marginTop);
                        ch.relY = snapPos(ry, alignEven);
                    } else {
                        ry = cursorY + ch.marginTop;
                        ch.relY = snapPos(ry, alignEven);
                        cursorY = ch.relY + cb[1];
                    }
                    int rx = alignX(preW, cb[0], ch.gravity, ch.marginLeft);
                    ch.relX = snapPos(rx, alignEven);
                    ch.layoutChildren(cb[0], cb[1]);
                }
            } else {
                for (ViewNode ch : children) {
                    int[] cb = resolveChildBox(ch, preW, preH);
                    ch.relX = snapPos(alignX(preW, cb[0], ch.gravity, ch.marginLeft), alignEven);
                    ch.relY = snapPos(alignY(preH, cb[1], ch.gravity, ch.marginTop), alignEven);
                    ch.layoutChildren(cb[0], cb[1]);
                }
            }
        }

        @Override
        void drawInto(Canvas canvas, int absX, int absY, boolean skipBlurBg) {
            if (layoutW <= 0 || layoutH <= 0) return;
            boolean skipOwnBg = usesBlurBg || "all_content".equals(nodeId)
                    || (skipBlurBg && ("horizontal_padding_48".equals(nodeId)
                    || "vertical_padding_48".equals(nodeId)
                    || "bottom_container".equals(nodeId)));
            if (degree % 360 == 0) {
                if (!skipOwnBg && Color.alpha(bgColor) > 0) {
                    Paint bp = new Paint();
                    bp.setColor(bgColor);
                    canvas.drawRect(absX, absY, absX + layoutW, absY + layoutH, bp);
                }
                for (ViewNode ch : children) {
                    ch.drawInto(canvas, absX + ch.relX, absY + ch.relY, skipBlurBg);
                }
                return;
            }
            int preW = (degree % 180 != 0) ? layoutH : layoutW;
            int preH = (degree % 180 != 0) ? layoutW : layoutH;
            canvas.save();
            applyRotation(canvas, absX, absY, layoutW, layoutH, degree);
            if (!skipOwnBg && Color.alpha(bgColor) > 0) {
                Paint bp = new Paint();
                bp.setColor(bgColor);
                canvas.drawRect(0, 0, preW, preH, bp);
            }
            for (ViewNode ch : children) {
                ch.drawInto(canvas, ch.relX, ch.relY, skipBlurBg);
            }
            canvas.restore();
        }
    }

    private static int[] findNodeBounds(ViewNode root, String targetId, int ox, int oy) {
        if (targetId.equals(root.nodeId)) {
            return new int[] {ox, oy, root.layoutW, root.layoutH};
        }
        if (root instanceof ContainerNode) {
            ContainerNode cn = (ContainerNode) root;
            for (ViewNode ch : cn.children) {
                int[] found = findNodeBounds(ch, targetId, ox + ch.relX, oy + ch.relY);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void applyRotation(Canvas canvas, int x, int y, int boxW, int boxH, int degree) {
        int d = ((degree % 360) + 360) % 360;
        if (d == 90) {
            canvas.translate(x + boxW, y);
            canvas.rotate(90f);
        } else if (d == 180) {
            canvas.translate(x + boxW, y + boxH);
            canvas.rotate(180f);
        } else if (d == 270) {
            canvas.translate(x, y + boxH);
            canvas.rotate(270f);
        } else {
            canvas.translate(x, y);
        }
    }

    private static void drawVectorBadge(
            Canvas canvas, PhocusStyles.VectorSpec vec, int boxW, int boxH) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(vec.fillColor);
        if (boxW == boxH) {
            // Circular H badge: match 1.15x optical size inside clipped box.
            canvas.save();
            canvas.clipRect(0, 0, boxW, boxH);
            float renderSide = boxW * 1.15f;
            float offset = (boxW - renderSide) * 0.5f;
            canvas.translate(offset, offset);
            canvas.scale(renderSide / vec.viewportWidth, renderSide / vec.viewportHeight);
            canvas.drawPath(vec.getPath(), paint);
            canvas.restore();
        } else {
            // Horizontal wordmark: 0.78x vertical box scaled cleanly at viewport aspect ratio.
            float wordH = boxH * 0.78f;
            float wordW = wordH * (vec.viewportWidth / vec.viewportHeight);
            float top = (boxH - wordH) * 0.5f;
            canvas.save();
            canvas.translate(0f, top);
            canvas.scale(wordW / vec.viewportWidth, wordH / vec.viewportHeight);
            canvas.drawPath(vec.getPath(), paint);
            canvas.restore();
        }
    }

    // =========================================================================
    // Wrapping & Font/Bitmap loading
    // =========================================================================

    private static List<String> wrapLines(String text, Paint paint, int maxWidth, int maxLines) {
        List<String> out = new ArrayList<>();
        for (String para : text.split("\n", -1)) {
            if (maxWidth > 0) {
                out.addAll(wrapParagraph(para, paint, maxWidth));
            } else {
                out.add(para);
            }
        }
        if (maxLines > 0 && out.size() > maxLines) {
            List<String> kept = new ArrayList<>(out.subList(0, maxLines));
            String last = kept.get(maxLines - 1).trim();
            if (maxWidth > 0) {
                while (!last.isEmpty() && paint.measureText(last + "…") > maxWidth) {
                    last = last.substring(0, last.length() - 1).trim();
                }
            }
            kept.set(maxLines - 1, last.isEmpty() ? "…" : (last + "…"));
            return kept;
        }
        return out;
    }

    private static List<String> wrapParagraph(String para, Paint paint, int maxWidth) {
        if (para.isEmpty() || paint.measureText(para) <= maxWidth) {
            return Collections.singletonList(para);
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < para.length(); i++) {
            char ch = para.charAt(i);
            if (ch == ' ') {
                if (buf.length() > 0) {
                    tokens.add(buf.toString());
                    buf.setLength(0);
                }
                tokens.add(" ");
            } else if (ch > 0x2E7F) {
                if (buf.length() > 0) {
                    tokens.add(buf.toString());
                    buf.setLength(0);
                }
                tokens.add(String.valueOf(ch));
            } else {
                buf.append(ch);
            }
        }
        if (buf.length() > 0) tokens.add(buf.toString());

        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String tok : tokens) {
            if (cur.length() == 0 && " ".equals(tok)) continue;
            String candidate = cur.toString() + tok;
            if (paint.measureText(candidate) <= maxWidth) {
                cur.append(tok);
            } else if (cur.length() > 0) {
                lines.add(cur.toString().replaceAll("\\s+$", ""));
                cur.setLength(0);
                if (!" ".equals(tok)) cur.append(tok);
            } else {
                for (int j = 0; j < tok.length(); j++) {
                    char c = tok.charAt(j);
                    if (cur.length() > 0 && paint.measureText(cur.toString() + c) > maxWidth) {
                        lines.add(cur.toString());
                        cur.setLength(0);
                    }
                    cur.append(c);
                }
            }
        }
        if (cur.toString().trim().length() > 0 || lines.isEmpty()) {
            lines.add(cur.toString().replaceAll("\\s+$", ""));
        }
        return lines;
    }

    private static Typeface loadTypeface(File styleDir, String assetDir, String tfSpec, int weight) {
        String tf = tfSpec != null ? tfSpec.trim() : "default";
        File file;
        boolean variable = false;
        if (tf.startsWith("@file=")) {
            String rel = tf.substring("@file=".length()).trim();
            file = new File(styleDir, rel);
            if (!file.isFile()) file = new File(new File(assetDir, "fonts"), rel);
        } else if ("oxanium".equals(tf)) {
            String name = weight <= 400
                    ? "Oxanium-Regular.ttf"
                    : (weight >= 700 ? "Oxanium-Bold.ttf" : "Oxanium-Medium.ttf");
            file = new File(new File(assetDir, "fonts"), name);
        } else {
            file = new File(new File(assetDir, "fonts"), "MiSansVF.ttf");
            variable = true;
        }
        String cacheKey = file.getAbsolutePath() + "|w=" + (variable ? weight : 0);
        Typeface cached = FONT_CACHE.get(cacheKey);
        if (cached != null) return cached;
        if (!file.isFile()) {
            Log.e(TAG, "font missing: " + file.getAbsolutePath(), new IllegalStateException("missing font"));
            return Typeface.DEFAULT;
        }
        try {
            Typeface built = variable
                    ? new Typeface.Builder(file).setFontVariationSettings("'wght' " + weight).build()
                    : Typeface.createFromFile(file);
            if (built != null) {
                FONT_CACHE.putIfAbsent(cacheKey, built);
                return built;
            }
        } catch (Throwable error) {
            Log.e(TAG, "font load failed: " + file.getAbsolutePath(), error);
        }
        return Typeface.DEFAULT;
    }

    private static Bitmap loadBitmap(File file) {
        String key = file.getAbsolutePath();
        Bitmap cached = BITMAP_CACHE.get(key);
        if (cached != null && !cached.isRecycled()) return cached;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeFile(key, opts);
            if (bmp != null) {
                Bitmap prev = BITMAP_CACHE.putIfAbsent(key, bmp);
                if (prev != null && !prev.isRecycled()) {
                    bmp.recycle();
                    return prev;
                }
                return bmp;
            }
        } catch (Throwable error) {
            Log.e(TAG, "bitmap load failed: " + key, error);
        }
        return null;
    }
}
