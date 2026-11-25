package local.jiege.hook.watermark;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import java.io.File;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import local.jiege.hook.common.Log;
import local.jiege.hook.watermark.PhocusStyles.PhotoMeta;
import local.jiege.hook.watermark.PhocusStyles.StyleSpec;
import local.jiege.hook.watermark.PhocusStyles.VectorSpec;

/**
 * 1:1 Android rendering engine for Hasselblad Phocus Mobile 1.2.0 frame and overlay watermarks.
 *
 * <p>Matches {@code phocus_watermark/renderer.py} 1:1 across SDR layer separation
 * ({@code borderBgColor}, {@code excludedBitmap}, {@code overlayMainBitmap}) and 2-layer HDR Gainmap
 * composition.
 */
public final class PhocusEngine {
    private static final String TAG = "PhocusEngine";

    public static final String GALLERY_ASSET_DIR = "/data/user/0/com.coloros.gallery3d/files/watermark_master/phocus";
    public static final String CAMERA_ASSET_DIR = "/data/user/0/com.oplus.camera/files/jiege/phocus";

    private static final String FONT_ROBOTO_FLEX = "fonts/roboto_flex.ttf";
    private static final String FONT_FUSION_PIXEL = "fonts/fusion_pixel_8px.otf";

    private static final int COLOR_FILM_DARK = 0xFF141414;
    private static final int COLOR_FILM_MID = 0xFF202020;
    private static final int COLOR_FILM_LIGHT = 0xFF2C2C2C;
    private static final int COLOR_FILM_DATE = 0xFF9F795A;

    private static final ConcurrentHashMap<String, Typeface> FONT_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Bitmap> OVERLAY_CACHE = new ConcurrentHashMap<>();

    private PhocusEngine() {}

    /** Computed canvas dimensions and truncated padding matching {@code p099k6.m.b/e}. */
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

        public Metrics(
                int imgW,
                int imgH,
                double scale,
                int padLeft,
                int padTop,
                int padRight,
                int padBottom,
                int canvasW,
                int canvasH,
                boolean isLandscape,
                boolean hasBorder) {
            this.imgW = imgW;
            this.imgH = imgH;
            this.scale = scale;
            this.padLeft = padLeft;
            this.padTop = padTop;
            this.padRight = padRight;
            this.padBottom = padBottom;
            this.canvasW = canvasW;
            this.canvasH = canvasH;
            this.isLandscape = isLandscape;
            this.hasBorder = hasBorder;
        }

        public static int alignEven(int value) {
            return (value + 1) & ~1;
        }

        /** Returns a copy with {@code canvasW} and {@code canvasH} rounded up to even pixels if required by YUV/HW buffers. */
        public Metrics alignedEven() {
            int evenW = alignEven(canvasW);
            int evenH = alignEven(canvasH);
            if (evenW == canvasW && evenH == canvasH) return this;
            return new Metrics(
                imgW, imgH, scale,
                padLeft, padTop, padRight + (evenW - canvasW), padBottom + (evenH - canvasH),
                evenW, evenH, isLandscape, hasBorder);
        }
    }

    /**
     * Separated SDR rendering layers matching {@code renderer.py _build_layers}.
     *
     * <p>{@code excludedBitmap} may be {@code null} when the style has no {@code tag="exclude"} elements.
     */
    public static final class RenderLayers {
        public final StyleSpec spec;
        public final Metrics metrics;
        public final int borderBgColor;
        public final Bitmap excludedBitmap;
        public final Bitmap overlayMainBitmap;

        public RenderLayers(
                StyleSpec spec,
                Metrics metrics,
                int borderBgColor,
                Bitmap excludedBitmap,
                Bitmap overlayMainBitmap) {
            this.spec = spec;
            this.metrics = metrics;
            this.borderBgColor = borderBgColor;
            this.excludedBitmap = excludedBitmap;
            this.overlayMainBitmap = overlayMainBitmap;
        }

        public boolean hasExcluded() {
            return excludedBitmap != null;
        }

        public void recycle() {
            if (excludedBitmap != null && !excludedBitmap.isRecycled()) {
                excludedBitmap.recycle();
            }
            if (overlayMainBitmap != null && !overlayMainBitmap.isRecycled()) {
                overlayMainBitmap.recycle();
            }
        }
    }

    private static final class TextSegment {
        final String text;
        final int color;
        final int marginLeft;

        TextSegment(String text, int color, int marginLeft) {
            this.text = text;
            this.color = color;
            this.marginLeft = marginLeft;
        }
    }

    public static int roundToInt(double x) {
        return (int) Math.floor(x + 0.5);
    }

    /** Computes scale and truncated border padding matching {@code renderer.py compute_scale/compute_padding}. */
    public static Metrics computeMetrics(StyleSpec spec, int imgW, int imgH) {
        if (spec == null) throw new IllegalArgumentException("spec must not be null");
        if (imgW <= 0 || imgH <= 0) {
            throw new IllegalArgumentException("Invalid image size: " + imgW + "x" + imgH);
        }
        boolean isLandscape = imgW > imgH;
        double scale;
        int padL, padT, padR, padB;
        if (isLandscape) {
            scale = (double) imgW / (878.0 - spec.padLandLeft - spec.padLandRight);
            padL = (int) (spec.padLandLeft * scale);
            padT = (int) (spec.padLandTop * scale);
            padR = (int) (spec.padLandRight * scale);
            padB = (int) (spec.padLandBottom * scale);
        } else {
            scale = (double) imgW / (462.0 - spec.padPortLeft - spec.padPortRight);
            padL = (int) (spec.padPortLeft * scale);
            padT = (int) (spec.padPortTop * scale);
            padR = (int) (spec.padPortRight * scale);
            padB = (int) (spec.padPortBottom * scale);
        }
        boolean hasBorder = padL > 0 || padT > 0 || padR > 0 || padB > 0;
        return new Metrics(
            imgW, imgH, scale,
            padL, padT, padR, padB,
            imgW + padL + padR, imgH + padT + padB,
            isLandscape, hasBorder);
    }

    /** Renders Phocus SDR watermark layers using a deterministic seed derived from image dimensions and date. */
    public static RenderLayers render(
            StyleSpec spec,
            PhotoMeta meta,
            String assetDir,
            int imgW,
            int imgH,
            boolean isPreview) {
        long seed = deriveSeed(imgW, imgH, meta);
        return render(spec, meta, assetDir, imgW, imgH, isPreview, seed);
    }

    /** Renders Phocus SDR watermark layers with an explicit RNG seed for Style 15 film grain. */
    public static RenderLayers render(
            StyleSpec spec,
            PhotoMeta meta,
            String assetDir,
            int imgW,
            int imgH,
            boolean isPreview,
            long seed) {
        Metrics metrics = computeMetrics(spec, imgW, imgH);
        return render(spec, metrics, meta, assetDir, isPreview, seed);
    }

    /** Renders Phocus SDR watermark layers into the provided {@link Metrics} canvas geometry. */
    public static RenderLayers render(
            StyleSpec spec,
            Metrics metrics,
            PhotoMeta meta,
            String assetDir,
            boolean isPreview,
            long seed) {
        if (spec == null || metrics == null) {
            throw new IllegalArgumentException("spec and metrics must not be null");
        }
        PhotoMeta m = meta != null ? meta : new PhotoMeta(null, null, null, null, null, null);
        int cw = metrics.canvasW;
        int ch = metrics.canvasH;
        int imgW = metrics.imgW;
        int imgH = metrics.imgH;
        double scale = metrics.scale;
        boolean isLandscape = metrics.isLandscape;
        int sid = spec.styleId;

        Bitmap overlayMain = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(overlayMain);

        boolean needsExcluded = sid == 1 || sid == 4 || sid == 13 || sid == 14 || sid == 15;
        Bitmap excluded = needsExcluded ? Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888) : null;
        Canvas canvasExcl = excluded != null ? new Canvas(excluded) : null;

        Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        if (sid == 1) {
            int logoSz = Math.max(1, roundToInt(48.0 * scale));
            int logoTop = roundToInt(32.0 * scale + imgH);
            int logoX = (cw - logoSz) / 2;
            drawVector(canvas, PhocusStyles.VEC_HBLACK_LOGO, logoX, logoTop, logoSz, logoSz);

            double mainTopM = isLandscape ? 6.0 : 4.0;
            double mainSp = isLandscape ? 16.0 : 14.0;
            double subTopM = isLandscape ? 2.0 : 0.0;
            double subSp = isLandscape ? 14.0 : 12.0;
            double subH = isLandscape ? 24.0 : 20.0;

            int mainTop = logoTop + logoSz + roundToInt(mainTopM * scale);
            int mainH = roundToInt(24.0 * scale);
            Paint pMain = createTextPaint(assetDir, FONT_ROBOTO_FLEX, mainSp * scale, 0xFF000000, Paint.Align.CENTER);
            drawTextCenteredY(canvas, cw * 0.5f, mainTop, mainH, m.fullBodyName, pMain);

            int subTop = mainTop + mainH + roundToInt(subTopM * scale);
            int subBoxH = roundToInt(subH * scale);
            Paint pSub = createTextPaint(assetDir, FONT_ROBOTO_FLEX, subSp * scale, 0x80000000, Paint.Align.CENTER);
            drawTextCenteredY(canvasExcl, cw * 0.5f, subTop, subBoxH, m.lensInfoText, pSub);
        } else if (sid == 2 || sid == 3 || sid == 12) {
            int wordW = Math.max(1, roundToInt(182.0 * scale));
            int wordH = Math.max(1, roundToInt((182.0 * scale) / 10.111111));
            double topDp = (sid == 3) ? (isLandscape ? 36.0 : 34.0) : (isLandscape ? 64.0 : 56.0);
            int topY = roundToInt(topDp * scale + imgH);
            int x0 = (cw - wordW) / 2;
            VectorSpec vec = (sid == 12) ? PhocusStyles.VEC_HASSELBLAD_LOGO_ORANGE : PhocusStyles.VEC_HASSELBLAD_LOGO;
            drawVector(canvas, vec, x0, topY, wordW, wordH);
        } else if (sid == 4) {
            int logoSz = Math.max(1, roundToInt(48.0 * scale));
            int logoRightM = roundToInt(24.0 * scale);
            int logoBotM = roundToInt(21.0 * scale);
            int logoX = cw - logoRightM - logoSz;
            int logoY = ch - logoBotM - logoSz;
            drawVector(canvas, PhocusStyles.VEC_HBLACK_LOGO, logoX, logoY, logoSz, logoSz);

            int textX = roundToInt(24.0 * scale);
            int mainH = roundToInt(24.0 * scale);
            Paint pMain = createTextPaint(assetDir, FONT_ROBOTO_FLEX, 16.0 * scale, 0xFF000000, Paint.Align.LEFT);
            drawTextCenteredY(canvas, textX, logoY, mainH, m.fullBodyName, pMain);

            int subTop = logoY + mainH + roundToInt(2.0 * scale);
            int subH = roundToInt(24.0 * scale);
            Paint pSub = createTextPaint(assetDir, FONT_ROBOTO_FLEX, 14.0 * scale, 0x80000000, Paint.Align.LEFT);
            drawTextCenteredY(canvasExcl, textX, subTop, subH, m.lensInfoText, pSub);
        } else if (sid == 6) {
            int logoSz = Math.max(1, roundToInt(32.0 * scale));
            int logoBotM = roundToInt(44.0 * scale);
            int logoX = (cw - logoSz) / 2;
            int logoY = ch - logoBotM - logoSz;
            drawVector(canvas, PhocusStyles.VEC_HWHITE_LOGO, logoX, logoY, logoSz, logoSz);

            int rowH = roundToInt(20.0 * scale);
            int rowBotM = roundToInt(16.0 * scale);
            int rowTop = ch - rowBotM - rowH;
            int gapM = roundToInt(20.0 * scale);
            drawExifRow(canvas, assetDir, cw, rowTop, rowH, 12.0 * scale, gapM,
                0xCCFFFFFF, 0x99FFFFFF, 0xF2FFFFFF, "  |  ", m);
        } else if (sid == 7) {
            int wordW = Math.max(1, roundToInt(182.0 * scale));
            int wordH = Math.max(1, roundToInt((182.0 * scale) / 10.111111));
            int wordTop = roundToInt(48.0 * scale);
            int wordX = (cw - wordW) / 2;
            drawVector(canvas, PhocusStyles.VEC_HASSELBLAD_LOGO, wordX, wordTop, wordW, wordH);

            int rowTop = roundToInt(144.0 * scale + imgH);
            int rowH = roundToInt(20.0 * scale);
            int gapM = roundToInt(24.0 * scale);
            drawExifRow(canvas, assetDir, cw, rowTop, rowH, 12.0 * scale, gapM,
                0x80000000, 0xF2000000, 0xF2000000, "   ", m);
        } else if (sid == 8) {
            double wDp = isLandscape ? 138.0 : 106.0;
            double botDp = isLandscape ? 24.0 : 16.0;
            int logoW = Math.max(1, roundToInt(wDp * scale));
            int logoH = Math.max(1, roundToInt((wDp * scale) / 1.9032258));
            int botM = roundToInt(botDp * scale);
            drawScaledOverlay(canvas, assetDir, "earth_explorer_logo.png",
                (cw - logoW) / 2, ch - botM - logoH, logoW, logoH, bitmapPaint);
        } else if (sid == 9) {
            double wDp = isLandscape ? 140.0 : 118.0;
            String assetName = isLandscape
                ? "earth_explorer_logo_black_horizontal.png"
                : "earth_explorer_logo_black_vertical.png";
            int logoW = Math.max(1, roundToInt(wDp * scale));
            int logoH = Math.max(1, roundToInt((wDp * scale) / 1.9032258));
            int topY = roundToInt(36.0 * scale + imgH);
            drawScaledOverlay(canvas, assetDir, assetName,
                (cw - logoW) / 2, topY, logoW, logoH, bitmapPaint);
        } else if (sid == 13 || sid == 14) {
            double hDp;
            String backName;
            String logoName;
            if (sid == 13) {
                hDp = isLandscape ? 157.0 : 126.71429;
                backName = isLandscape ? "new_year_logo_2026_horizontal_back.webp" : "new_year_logo_2026_vertical_back.webp";
                logoName = isLandscape ? "new_year_logo_2026_horizontal_logo.webp" : "new_year_logo_2026_vertical_logo.webp";
            } else {
                hDp = isLandscape ? 160.01744 : 110.00001;
                backName = isLandscape ? "new_year_horse_2026_horizontal_back.webp" : "new_year_horse_2026_vertical_back.webp";
                logoName = isLandscape ? "new_year_horse_2026_horizontal_logo.webp" : "new_year_horse_2026_vertical_logo.webp";
            }
            int bannerH = Math.max(1, roundToInt(hDp * scale));
            int yPos = ch - bannerH;
            drawScaledOverlay(canvasExcl, assetDir, backName, 0, yPos, cw, bannerH, bitmapPaint);
            drawScaledOverlay(canvas, assetDir, logoName, 0, yPos, cw, bannerH, bitmapPaint);
        } else if (sid == 15) {
            int bandPx = Math.max(1, roundToInt(56.0 * scale));
            Random rng = new Random(seed);
            if (isLandscape) {
                renderFilmBand(canvasExcl, assetDir, 0, 0, cw, bandPx, "top", scale, rng, isPreview, bitmapPaint);
                renderFilmBand(canvasExcl, assetDir, 0, ch - bandPx, cw, bandPx, "bottom", scale, rng, isPreview, bitmapPaint);

                int hStrip = Math.max(1, roundToInt(32.0 * scale));
                int ulW = Math.max(1, roundToInt(260.0 * scale));
                int urW = Math.max(1, roundToInt(140.0 * scale));
                int blW = Math.max(1, roundToInt(140.0 * scale));
                int bcW = Math.max(1, roundToInt(140.0 * scale));
                int brW = Math.max(1, roundToInt(230.0 * scale));

                drawScaledOverlay(canvas, assetDir, "film_frame_logo_horizontal_up_left.webp", 0, 0, ulW, hStrip, bitmapPaint);
                drawScaledOverlay(canvas, assetDir, "film_frame_logo_horizontal_up_right.webp", cw - urW, 0, urW, hStrip, bitmapPaint);
                drawScaledOverlay(canvas, assetDir, "film_frame_logo_horizontal_bottom_left.webp", 0, ch - hStrip, blW, hStrip, bitmapPaint);
                drawScaledOverlay(canvas, assetDir, "film_frame_logo_horizontal.webp", (cw - bcW) / 2, ch - hStrip, bcW, hStrip, bitmapPaint);
                drawScaledOverlay(canvas, assetDir, "film_frame_logo_horizontal_bottom_right.webp", cw - brW, ch - hStrip, brW, hStrip, bitmapPaint);

                if (!m.createTime.isEmpty()) {
                    Paint pDate = createTextPaint(assetDir, FONT_FUSION_PIXEL, 13.0 * scale, COLOR_FILM_DATE, Paint.Align.CENTER);
                    drawTextCenteredY(canvas, cw * 0.5f, 0f, hStrip, m.createTime, pDate);
                }
            } else {
                renderFilmBand(canvasExcl, assetDir, 0, 0, bandPx, ch, "left", scale, rng, isPreview, bitmapPaint);
                renderFilmBand(canvasExcl, assetDir, cw - bandPx, 0, bandPx, ch, "right", scale, rng, isPreview, bitmapPaint);

                int wStrip = Math.max(1, roundToInt(32.0 * scale));
                int hIcon = Math.max(1, roundToInt(140.0 * scale));
                drawScaledOverlay(canvas, assetDir, "film_frame_logo_vertical_up_left.webp", 0, 0, wStrip, hIcon, bitmapPaint);
                drawScaledOverlay(canvas, assetDir, "film_frame_logo_vertical_up_right.webp", cw - wStrip, 0, wStrip, hIcon, bitmapPaint);
                drawScaledOverlay(canvas, assetDir, "film_frame_logo_vertical.webp", 0, (ch - hIcon) / 2, wStrip, hIcon, bitmapPaint);
                drawScaledOverlay(canvas, assetDir, "film_frame_logo_vertical_bottom_left.webp", 0, ch - hIcon, wStrip, hIcon, bitmapPaint);
                drawScaledOverlay(canvas, assetDir, "film_frame_logo_vertical_bottom_right.webp", cw - wStrip, ch - hIcon, wStrip, hIcon, bitmapPaint);

                if (!m.createTime.isEmpty()) {
                    Paint pDate = createTextPaint(assetDir, FONT_FUSION_PIXEL, 13.0 * scale, COLOR_FILM_DATE, Paint.Align.CENTER);
                    Paint.FontMetrics fm = pDate.getFontMetrics();
                    float baselineOffset = -(fm.ascent + fm.descent) * 0.5f;
                    canvas.save();
                    canvas.translate(cw - wStrip * 0.5f, ch * 0.5f);
                    canvas.rotate(90.0f);
                    canvas.drawText(m.createTime, 0f, baselineOffset, pDate);
                    canvas.restore();
                }
            }
        }

        int borderBgColor = metrics.hasBorder ? spec.maskColor : 0;
        return new RenderLayers(spec, metrics, borderBgColor, excluded, overlayMain);
    }

    /** Composites full SDR photo + border + excluded + overlayMain onto a new ARGB_8888 Bitmap preserving wide-gamut ColorSpace. */
    public static Bitmap compositeSdr(RenderLayers layers, Bitmap photoBitmap) {
        Metrics m = layers.metrics;
        Bitmap out = createSdrBitmap(m.canvasW, m.canvasH, photoBitmap);
        Canvas canvas = new Canvas(out);
        if (m.hasBorder && layers.borderBgColor != 0) {
            drawBorderRects(canvas, m, layers.borderBgColor);
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
            if (needRecycle) {
                src.recycle();
            }
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

    /**
     * Composites only the watermark border strips + excluded + overlayMain onto a transparent ARGB_8888 Bitmap,
     * leaving the inner photo rectangle transparent.
     */
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

    private static void drawBorderRects(Canvas canvas, Metrics m, int color) {
        Paint p = new Paint();
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        if (m.padTop > 0) canvas.drawRect(0, 0, m.canvasW, m.padTop, p);
        if (m.padBottom > 0) canvas.drawRect(0, m.padTop + m.imgH, m.canvasW, m.canvasH, p);
        if (m.padLeft > 0) canvas.drawRect(0, 0, m.padLeft, m.canvasH, p);
        if (m.padRight > 0) canvas.drawRect(m.padLeft + m.imgW, 0, m.canvasW, m.canvasH, p);
    }

    private static void drawExifRow(
            Canvas canvas,
            String assetDir,
            int canvasW,
            int rowTop,
            int rowH,
            double fontSizePx,
            int gapMargin,
            int colorLabel,
            int colorPipe,
            int colorValue,
            String separator,
            PhotoMeta meta) {
        Paint paint = createTextPaint(assetDir, FONT_ROBOTO_FLEX, fontSizePx, colorLabel, Paint.Align.LEFT);
        TextSegment[] segments = {
            new TextSegment("Aperture", colorLabel, 0),
            new TextSegment(separator, colorPipe, 0),
            new TextSegment(meta.aperture, colorValue, 0),
            new TextSegment("Shutter", colorLabel, gapMargin),
            new TextSegment(separator, colorPipe, 0),
            new TextSegment(meta.shutter, colorValue, 0),
            new TextSegment("ISO", colorLabel, gapMargin),
            new TextSegment(separator, colorPipe, 0),
            new TextSegment(meta.iso, colorValue, 0),
        };
        float totalW = 0f;
        for (TextSegment seg : segments) {
            totalW += paint.measureText(seg.text) + seg.marginLeft;
        }
        float curX = (canvasW - totalW) * 0.5f;
        for (TextSegment seg : segments) {
            curX += seg.marginLeft;
            paint.setColor(seg.color);
            curX += drawTextCenteredY(canvas, curX, rowTop, rowH, seg.text, paint);
        }
    }

    private static void renderFilmBand(
            Canvas canvas,
            String assetDir,
            int ox,
            int oy,
            int bandW,
            int bandH,
            String direction,
            double scale,
            Random rng,
            boolean isPreview,
            Paint bitmapPaint) {
        boolean verticalGrad = "top".equals(direction) || "bottom".equals(direction);
        boolean lightStart = "top".equals(direction) || "left".equals(direction);
        int startColor = lightStart ? COLOR_FILM_LIGHT : COLOR_FILM_DARK;
        int endColor = lightStart ? COLOR_FILM_DARK : COLOR_FILM_LIGHT;

        canvas.save();
        canvas.translate(ox, oy);
        canvas.clipRect(0, 0, bandW, bandH);

        Paint gradPaint = new Paint();
        gradPaint.setStyle(Paint.Style.FILL);
        Shader shader = verticalGrad
            ? new LinearGradient(0f, 0f, 0f, Math.max(1, bandH - 1),
                new int[] {startColor, COLOR_FILM_MID, endColor}, new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP)
            : new LinearGradient(0f, 0f, Math.max(1, bandW - 1), 0f,
                new int[] {startColor, COLOR_FILM_MID, endColor}, new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        gradPaint.setShader(shader);
        canvas.drawRect(0f, 0f, bandW, bandH, gradPaint);

        // Sprocket holes
        if (verticalGrad) {
            double holeW = 18.0 * scale;
            double holeH = 16.0 * scale;
            double padStart = 8.0 * scale;
            double padEnd = 6.0 * scale;
            double yHole = "top".equals(direction) ? 32.0 * scale : padStart;
            double avail = bandW - padEnd - holeW - padStart;
            int count = Math.max(1, roundToInt(avail / (holeW + holeH)));
            double step = avail / count;
            int dstW = Math.max(1, roundToInt(holeW));
            int dstH = Math.max(1, roundToInt(holeH));
            int y0 = roundToInt(yHole);
            for (int idx = 0; idx <= count; idx++) {
                int x0 = roundToInt(idx * step + padStart);
                drawScaledOverlay(canvas, assetDir, "film_frame_square_horizontal.webp", x0, y0, dstW, dstH, bitmapPaint);
            }
        } else {
            double holeW = 16.0 * scale;
            double holeH = 18.0 * scale;
            double padStart = 4.0 * scale;
            double padEnd = 6.0 * scale;
            double xHole = "left".equals(direction) ? 32.0 * scale : 8.0 * scale;
            double avail = bandH - padEnd - holeH - padStart;
            int count = Math.max(1, roundToInt(avail / (holeH + holeW)));
            double step = avail / count;
            int dstW = Math.max(1, roundToInt(holeW));
            int dstH = Math.max(1, roundToInt(holeH));
            int x0 = roundToInt(xHole);
            for (int idx = 0; idx <= count; idx++) {
                int y0 = roundToInt(idx * step + padStart);
                drawScaledOverlay(canvas, assetDir, "film_frame_square_vertical.webp", x0, y0, dstW, dstH, bitmapPaint);
            }
        }

        // Deterministic film grain particles
        if (bandW > 0 && bandH > 0) {
            double fMax = Math.max((double) bandW, (double) bandH) / 4000.0;
            double sMin = 1.0 * fMax;
            double sMax = 2.0 * fMax;
            double avg = (sMin + sMax) * 0.5;
            int numDots = (int) ((bandW * (double) bandH * 0.04) / Math.max(1e-6, avg * avg));
            if (isPreview) {
                numDots = Math.max(1, (int) (numDots / 10.0));
            }
            int alphaByte = (int) (255.0 * 0.4);
            Paint grainPaint = new Paint();
            grainPaint.setStyle(Paint.Style.FILL);
            grainPaint.setColor(Color.argb(alphaByte, 0, 0, 0));
            for (int i = 0; i < numDots; i++) {
                float cx = rng.nextFloat() * bandW;
                float cy = rng.nextFloat() * bandH;
                float half = (float) ((sMin + (sMax - sMin) * rng.nextDouble()) * 0.5);
                canvas.drawRect(cx - half, cy - half, cx + half, cy + half, grainPaint);
            }
        }

        canvas.restore();
    }

    private static void drawVector(Canvas canvas, VectorSpec vec, int x, int y, int w, int h) {
        if (canvas == null || vec == null || w <= 0 || h <= 0) return;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(vec.fillColor);
        canvas.save();
        canvas.translate(x, y);
        canvas.scale(w / vec.viewportWidth, h / vec.viewportHeight);
        canvas.drawPath(vec.getPath(), paint);
        canvas.restore();
    }

    private static void drawScaledOverlay(
            Canvas canvas,
            String assetDir,
            String filename,
            int x,
            int y,
            int w,
            int h,
            Paint paint) {
        if (canvas == null || w <= 0 || h <= 0) return;
        Bitmap src = loadOverlay(assetDir, filename);
        if (src == null) return;
        Rect dst = new Rect(x, y, x + w, y + h);
        canvas.drawBitmap(src, null, dst, paint);
    }

    private static float drawTextCenteredY(Canvas canvas, float x, float boxTop, float boxH, String text, Paint paint) {
        if (canvas == null || text == null || text.isEmpty()) return 0f;
        Paint.FontMetrics fm = paint.getFontMetrics();
        float cy = boxTop + boxH * 0.5f;
        float baseline = cy - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(text, x, baseline, paint);
        return paint.measureText(text);
    }

    private static Paint createTextPaint(
            String assetDir,
            String relFontPath,
            double sizePx,
            int color,
            Paint.Align align) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTypeface(loadFont(assetDir, relFontPath));
        paint.setTextSize((float) Math.max(1.0, sizePx));
        paint.setColor(color);
        paint.setTextAlign(align);
        return paint;
    }

    public static Typeface loadFont(String assetDir, String relPath) {
        if (assetDir == null || assetDir.isEmpty()) return Typeface.DEFAULT;
        String key = assetDir + "|" + relPath;
        Typeface cached = FONT_CACHE.get(key);
        if (cached != null) return cached;
        File file = new File(assetDir, relPath);
        if (!file.isFile()) {
            Log.e(TAG, "font missing: " + file.getAbsolutePath(), new IllegalStateException("missing font"));
            return Typeface.DEFAULT;
        }
        try {
            Typeface tf = Typeface.createFromFile(file);
            if (tf != null) {
                FONT_CACHE.putIfAbsent(key, tf);
                return tf;
            }
        } catch (Throwable error) {
            Log.e(TAG, "font load failed: " + file.getAbsolutePath(), error);
        }
        return Typeface.DEFAULT;
    }

    public static Bitmap loadOverlay(String assetDir, String filename) {
        if (assetDir == null || assetDir.isEmpty()) return null;
        String key = assetDir + "|" + filename;
        Bitmap cached = OVERLAY_CACHE.get(key);
        if (cached != null && !cached.isRecycled()) return cached;
        File file = new File(new File(assetDir, "overlays"), filename);
        if (!file.isFile()) {
            Log.e(TAG, "overlay missing: " + file.getAbsolutePath(), new IllegalStateException("missing overlay"));
            return null;
        }
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (bmp != null) {
                Bitmap prev = OVERLAY_CACHE.putIfAbsent(key, bmp);
                if (prev != null && !prev.isRecycled()) {
                    bmp.recycle();
                    return prev;
                }
                return bmp;
            }
            Log.e(TAG, "overlay decode returned null: " + file.getAbsolutePath(), new IllegalStateException("decode failed"));
        } catch (Throwable error) {
            Log.e(TAG, "overlay decode failed: " + file.getAbsolutePath(), error);
        }
        return null;
    }

    private static long deriveSeed(int imgW, int imgH, PhotoMeta meta) {
        long h = 0x9E3779B97F4A7C15L;
        h ^= (long) imgW * 31L + imgH;
        h *= 0xBF58476D1CE4E5B9L;
        if (meta != null && meta.createTime != null) {
            h ^= meta.createTime.hashCode();
        }
        return h ^ (h >>> 32);
    }

}
