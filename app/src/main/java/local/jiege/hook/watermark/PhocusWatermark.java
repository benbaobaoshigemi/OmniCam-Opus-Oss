package local.jiege.hook.watermark;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.util.Size;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * Xposed hooks integrating the 12 non-signature Phocus watermark styles across
 * Gallery ({@code com.coloros.gallery3d}) and Camera ({@code com.oplus.camera}).
 */
public final class PhocusWatermark {

    private static final String TAG = "PhocusWatermark";

    private static final String GALLERY_MASTER_STYLE =
            "com.oplus.gallery.framework.abilities.watermark.masterstyle.WatermarkMasterStyle";
    private static final String GALLERY_IPU_AI_EDITOR =
            "com.oplus.tbluniformeditor.plugins.watermark.engine.IPUAiMasterWatermarkEditor";
    private static final String GALLERY_BASE_IPU_EDITOR =
            "com.oplus.tbluniformeditor.plugins.watermark.engine.BaseIPUWatermarkEditor";
    private static final String GALLERY_SIZE_INFO =
            "com.oplus.tbluniformeditor.plugins.watermark.data.WatermarkSizeInfo";
    private static final String GALLERY_EDIT_RESULT =
            "com.oplus.tbluniformeditor.plugins.watermark.data.WatermarkEditResult";

    private static final String CAMERA_CREATOR =
            "com.oplus.ocs.ipu.watermark.masterwatermark.MasterWatermarkCreator";
    private static final String CAMERA_ASSEMBLER =
            "com.oplus.ocs.ipu.watermark.masterwatermark.MasterWatermarkAssembler";

    private static final int STYLE_TYPE_PERSONALIZE = 2;
    private static final int WATERMARK_TYPE_AI_MASTER = 9;

    // Camera hold state keyed weakly by WatermarkHold instance to avoid memory leaks.
    private static final Map<Object, HoldState> CAMERA_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<AssemblyCtx> CAMERA_ASSEMBLY_CTX = new ThreadLocal<>();
    private static final ThreadLocal<Map<?, ?>> CAMERA_DEFER_MAP = new ThreadLocal<>();
    private static final ThreadLocal<List<HoldState>> CAMERA_CAPTURE_STATES = new ThreadLocal<>();

    private static final CustomWatermarkSupport.HoldFields HOLD_FIELDS =
            CustomWatermarkSupport.SHARED_HOLD_FIELDS;

    private static volatile Bitmap dummy1x1;

    private PhocusWatermark() {}

    public static boolean ours(Object styleId) {
        return styleId instanceof String && PhocusStyles.isPhocusStyle((String) styleId);
    }

    // =========================================================================
    // Gallery installation & hooks
    // =========================================================================

    public static void installGallery(ClassLoader classLoader) {
        hookGalleryMasterStyle(classLoader);
        hookGalleryIpuEditor(classLoader);
        hookGalleryEditEntry();
    }

    private static void hookGalleryMasterStyle(ClassLoader classLoader) {
        Class<?> companion = XposedHelpers.findClass(GALLERY_MASTER_STYLE + "$Companion", classLoader);
        XposedBridge.hookAllMethods(companion, "isOnlyFrameStyle", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object id = param.args[0];
                if (ours(id)) {
                    param.setResult(PhocusStyles.isFramedStyle((String) id));
                }
            }
        });
        XposedBridge.hookAllMethods(companion, "isOnlyOverlayStyle", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object id = param.args[0];
                if (ours(id)) {
                    param.setResult(PhocusStyles.isOverlayStyle((String) id));
                }
            }
        });
        XposedBridge.hookAllMethods(companion, "getStyleType", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (ours(param.args[0])) {
                    param.setResult(STYLE_TYPE_PERSONALIZE);
                }
            }
        });
    }

    /**
     * The styles have nothing to edit (the texts and logos come from the photo and the style), so
     * their cards in the style list get no edit icon and no way into the edit page.
     */
    private static void hookGalleryEditEntry() {
        Symbols symbols = Symbols.get();
        XposedBridge.hookMethod(symbols.method("StyleAdapter.editEntry"), new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isPhocusCard(param.args[2])) return;
                android.widget.ImageView icon = (android.widget.ImageView) param.args[0];
                icon.setOnClickListener(null);
                icon.setClickable(false);
                param.setResult(null);
            }
        });
        XposedBridge.hookMethod(symbols.method("StyleAdapter.bind"), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!isPhocusCard(param.args[2])) return;
                android.view.View card = (android.view.View) XposedHelpers.getObjectField(param.args[0], "itemView");
                int id = card.getResources().getIdentifier("style_item_edit", "id", card.getContext().getPackageName());
                android.view.View icon = card.findViewById(id);
                if (icon == null) return;
                icon.setOnClickListener(null);
                icon.setClickable(false);
                icon.setVisibility(android.view.View.GONE);
            }
        });
    }

    /** The style list's item carries its style id as one of its string fields. */
    private static boolean isPhocusCard(Object item) {
        if (item == null) return false;
        for (Field field : item.getClass().getDeclaredFields()) {
            if (field.getType() != String.class) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(item);
                if (value instanceof String && ((String) value).startsWith(PhocusStyles.STYLE_PREFIX)) return true;
            } catch (IllegalAccessException ignored) {
                return false;
            }
        }
        return false;
    }

    private static void hookGalleryIpuEditor(ClassLoader classLoader) {
        Class<?> ipuEditor = XposedHelpers.findClass(GALLERY_IPU_AI_EDITOR, classLoader);
        Class<?> baseEditor = XposedHelpers.findClass(GALLERY_BASE_IPU_EDITOR, classLoader);
        Class<?> sizeInfoCls = XposedHelpers.findClass(GALLERY_SIZE_INFO, classLoader);
        Class<?> editResultCls = XposedHelpers.findClass(GALLERY_EDIT_RESULT, classLoader);

        XposedBridge.hookAllMethods(ipuEditor, "isOnlyOverlayWatermark", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String id = styleIdFromJson(param.args[0]);
                if (ours(id)) {
                    param.setResult(PhocusStyles.isOverlayStyle(id));
                }
            }
        });
        XposedBridge.hookAllMethods(ipuEditor, "isOnlyFrameWatermark", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String id = styleIdFromJson(param.args[0]);
                if (ours(id)) {
                    param.setResult(PhocusStyles.isFramedStyle(id));
                }
            }
        });
        XposedBridge.hookAllMethods(ipuEditor, "isFrameAndOverlayWatermark", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (ours(styleIdFromJson(param.args[0]))) {
                    param.setResult(Boolean.FALSE);
                }
            }
        });

        XposedHelpers.findAndHookMethod(
                ipuEditor,
                "getWatermarkFrameSize",
                JSONObject.class,
                Size.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String id = styleIdFromJson(param.args[0]);
                        if (!ours(id)) return;
                        Size encodedSize = (Size) param.args[1];
                        if (encodedSize == null || encodedSize.getWidth() <= 0 || encodedSize.getHeight() <= 0) {
                            return;
                        }
                        int rot = normalizeRotation((Integer) param.args[2]);
                        boolean swap = (rot == 90 || rot == 270);
                        int uprightW = swap ? encodedSize.getHeight() : encodedSize.getWidth();
                        int uprightH = swap ? encodedSize.getWidth() : encodedSize.getHeight();

                        PhocusStyles.StyleSpec spec = PhocusStyles.getStyle(id);
                        PhocusEngine.Metrics m = PhocusEngine.computeMetrics(spec, uprightW, uprightH);
                        int[] encPads = mapPadsForRotation(m.padLeft, m.padTop, m.padRight, m.padBottom, rot);
                        int outW = encodedSize.getWidth() + encPads[0] + encPads[2];
                        int outH = encodedSize.getHeight() + encPads[1] + encPads[3];
                        Rect displayRect = new Rect(
                                encPads[0],
                                encPads[1],
                                encPads[0] + encodedSize.getWidth(),
                                encPads[1] + encodedSize.getHeight());
                        Object sizeInfo = XposedHelpers.newInstance(
                                sizeInfoCls,
                                encodedSize,
                                new Size(outW, outH),
                                displayRect);
                        param.setResult(sizeInfo);
                    }
                });

        XposedBridge.hookAllMethods(baseEditor, "addWatermark", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object info = param.args[2];
                String id = styleIdFromGalleryInfo(info);
                if (!ours(id)) return;

                Bitmap src = (Bitmap) param.args[0];
                Bitmap dst = (Bitmap) param.args[1];
                if (src == null) return;

                int rotation = readGalleryRotation(info);
                PhocusStyles.StyleSpec spec = PhocusStyles.getStyle(id);
                PhocusStyles.PhotoMeta meta = extractGalleryMeta(info);

                Bitmap uprightSrc = null;
                Bitmap uprightSdr = null;
                Bitmap rotatedSdr = null;
                Bitmap uprightTrans = null;
                Bitmap rotatedTrans = null;
                Bitmap handedResult = null;
                Bitmap handedTrans = null;
                PhocusEngine.RenderLayers layers = null;

                try {
                    uprightSrc = rotateBitmapToSoftware(src, (360 - rotation) % 360);
                    int uprightW = uprightSrc.getWidth();
                    int uprightH = uprightSrc.getHeight();
                    PhocusEngine.Metrics m = PhocusEngine.computeMetrics(spec, uprightW, uprightH);
                    layers = PhocusEngine.render(
                            spec,
                            m,
                            meta,
                            PhocusEngine.GALLERY_ASSET_DIR,
                            false,
                            deriveSeed(uprightW, uprightH, meta));
                    uprightSdr = PhocusEngine.compositeSdr(layers, uprightSrc);
                    rotatedSdr = rotateBitmapToSoftware(uprightSdr, rotation);
                    Bitmap resultBitmap = writeIntoDstOrKeep(dst, rotatedSdr);
                    if (resultBitmap != rotatedSdr) {
                        rotatedSdr.recycle();
                        rotatedSdr = null;
                    }

                    RectF displayRectF = null;
                    RectF imageDisplayRectF = null;
                    Bitmap transparentBgBitmap = null;
                    if (spec.isOverlay()) {
                        uprightTrans = PhocusEngine.compositeTransparentOverlay(layers);
                        rotatedTrans = rotateBitmapToSoftware(uprightTrans, rotation);
                        transparentBgBitmap = rotatedTrans;
                        rotatedTrans = null;
                        displayRectF = new RectF(0f, 0f, uprightW, uprightH);
                    } else {
                        int[] rotPads = mapPadsForRotation(
                                m.padLeft, m.padTop, m.padRight, m.padBottom, rotation);
                        int photoX = rotPads[0];
                        int photoY = rotPads[1];
                        imageDisplayRectF = new RectF(
                                photoX,
                                photoY,
                                photoX + src.getWidth(),
                                photoY + src.getHeight());
                        if (rotation == 0 && (src.getWidth() != uprightW || src.getHeight() != uprightH)) {
                            imageDisplayRectF = new RectF(
                                    m.padLeft,
                                    m.padTop,
                                    m.padLeft + uprightW,
                                    m.padTop + uprightH);
                        }
                    }

                    Object editResult = XposedHelpers.newInstance(
                            editResultCls,
                            Boolean.TRUE,
                            null,
                            null,
                            displayRectF,
                            resultBitmap,
                            transparentBgBitmap,
                            imageDisplayRectF);

                    handedResult = resultBitmap;
                    handedTrans = transparentBgBitmap;
                    param.setResult(editResult);
                } finally {
                    // 交给编辑器的两张图不能回收；旋转为 0 时中间图与它们是同一个对象
                    Bitmap[] temps = {uprightSrc, uprightSdr, rotatedSdr, uprightTrans, rotatedTrans};
                    for (int i = 0; i < temps.length; i++) {
                        Bitmap t = temps[i];
                        if (t == null || t == src || t == dst
                                || t == handedResult || t == handedTrans) continue;
                        boolean dup = false;
                        for (int j = 0; j < i; j++) if (temps[j] == t) dup = true;
                        if (!dup) t.recycle();
                    }
                    if (layers != null) {
                        layers.recycle();
                    }
                }
            }
        });
    }

    private static String styleIdFromGalleryInfo(Object info) {
        if (info == null) return null;
        Object aiParams = XposedHelpers.callMethod(info, "getAiMasterWatermarkInfo");
        if (aiParams == null) return null;
        Object id = XposedHelpers.callMethod(aiParams, "getStyleId");
        return id instanceof String ? (String) id : null;
    }

    private static int readGalleryRotation(Object info) {
        Object params = XposedHelpers.callMethod(info, "getParams");
        if (params == null) return 0;
        Object rot = XposedHelpers.callMethod(params, "getRotation");
        return rot instanceof Integer ? normalizeRotation((Integer) rot) : 0;
    }

    private static PhocusStyles.PhotoMeta extractGalleryMeta(Object info) {
        Object content = XposedHelpers.callMethod(info, "getContent");
        Object shooting = content != null ? XposedHelpers.callMethod(content, "getShootingInfo") : null;
        Object device = XposedHelpers.callMethod(info, "getDevice");

        String rawAperture = shooting != null ? asString(XposedHelpers.callMethod(shooting, "getAperture")) : null;
        String rawShutter = shooting != null ? asString(XposedHelpers.callMethod(shooting, "getExposureTime")) : null;
        String rawIso = shooting != null ? asString(XposedHelpers.callMethod(shooting, "getIsoSpeedRatings")) : null;
        String rawFocal35 = shooting != null ? asString(XposedHelpers.callMethod(shooting, "getFocalLengthIn35MM")) : null;

        String body = content != null ? asString(XposedHelpers.callMethod(content, "getDeviceName")) : null;
        if (isEmpty(body) && device != null) {
            body = asString(XposedHelpers.callMethod(device, "getDeviceName"));
        }
        if (isEmpty(body)) {
            body = Build.MODEL;
        }
        String time = content != null ? asString(XposedHelpers.callMethod(content, "getTime")) : null;
        return PhocusStyles.formatMetaFromStrings(
                rawAperture, rawShutter, rawIso, null, rawFocal35, body, time);
    }

    // =========================================================================
    // Camera installation & hooks
    // =========================================================================

    public static void installCamera(ClassLoader classLoader, String assetDir) {
        Symbols symbols = Symbols.get();
        if (symbols == null) {
            Log.e(TAG, "camera symbols unavailable", new IllegalStateException("Symbols.get() returned null"));
            return;
        }
        String resolvedDir = (assetDir != null && !assetDir.isEmpty())
                ? assetDir
                : PhocusEngine.CAMERA_ASSET_DIR;

        hookCameraCreator(symbols, classLoader, resolvedDir);
        hookCameraDownscalers(symbols);
        hookCameraAssembler(symbols, classLoader);
        hookCameraGainmap(symbols, classLoader);
        hookCameraCaptureLifecycle(symbols);
        CustomWatermarkSupport.installSharedCameraHooks(symbols);
    }

    private static void hookCameraCreator(Symbols symbols, ClassLoader classLoader, String assetDir) {
        Class<?> creatorCls = XposedHelpers.findClass(CAMERA_CREATOR, classLoader);
        XposedHelpers.findAndHookMethod(
                creatorCls,
                "calculateFinalSize",
                Size.class,
                JSONObject.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String id = styleIdFromJson(param.args[1]);
                        if (!ours(id)) return;
                        Size picSize = (Size) param.args[0];
                        if (picSize == null || picSize.getWidth() <= 0 || picSize.getHeight() <= 0) {
                            return;
                        }
                        PhocusStyles.StyleSpec spec = PhocusStyles.getStyle(id);
                        PhocusEngine.Metrics m = computeCameraMetrics(spec, picSize.getWidth(), picSize.getHeight());
                        param.setResult(new Size(m.canvasW, m.canvasH));
                    }
                });

        XposedBridge.hookMethod(symbols.method("WatermarkCreator.create"), new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object yuv = param.args[1];
                String id = styleIdFromCameraYuv(symbols, yuv);
                if (!ours(id)) return;

                Size picSize = (Size) symbols.get("WatermarkYuv.mPictureSize", yuv);
                if (picSize == null || picSize.getWidth() <= 0 || picSize.getHeight() <= 0) {
                    return;
                }
                PhocusStyles.StyleSpec spec = PhocusStyles.getStyle(id);
                PhocusStyles.PhotoMeta meta = extractCameraMeta(symbols, yuv);
                PhocusEngine.Metrics m = computeCameraMetrics(spec, picSize.getWidth(), picSize.getHeight());
                boolean isVisual = Boolean.TRUE.equals(symbols.get("WatermarkYuv.isFromVisual", yuv));

                Object hold = buildCameraHold(symbols, spec, m, yuv, meta, assetDir, isVisual);
                param.setResult(hold);
            }
        });
    }

    private static void hookCameraDownscalers(Symbols symbols) {
        // WatermarkAppHelper.downscale(hold, tag, size, bitmap, z) -> arg[2] is target Size
        XposedBridge.hookMethod(symbols.method("WatermarkAppHelper.downscale"),
                new DownscaleHook(symbols, 2));
        // WatermarkHd.scaleHold(hold, size) -> arg[1] is target Size
        XposedBridge.hookMethod(symbols.method("WatermarkHd.scaleHold"),
                new DownscaleHook(symbols, 1));
        // ThumbnailProcessor.scaleHold(hold, size, str) -> arg[1] is target Size
        XposedBridge.hookMethod(symbols.method("ThumbnailProcessor.scaleHold"),
                new DownscaleHook(symbols, 1));
    }

    private static final class DownscaleHook extends XC_MethodHook {
        private final Symbols symbols;
        private final int sizeArgIndex;

        DownscaleHook(Symbols symbols, int sizeArgIndex) {
            this.symbols = symbols;
            this.sizeArgIndex = sizeArgIndex;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            Object origHold = param.args[0];
            HoldState origState = origHold != null ? CAMERA_STATES.get(origHold) : null;
            if (origState == null) return;

            Size targetSize = (Size) param.args[sizeArgIndex];
            if (targetSize == null || targetSize.getWidth() <= 0 || targetSize.getHeight() <= 0) {
                return;
            }
            PhocusEngine.Metrics eff = origState.syncFromHold(symbols, origHold);
            // Align targetSize orientation with original upright photo size.
            boolean origLandscape = eff.imgW > eff.imgH;
            boolean targetLandscape = targetSize.getWidth() > targetSize.getHeight();
            Size normalized = (origLandscape != targetLandscape)
                    ? new Size(targetSize.getHeight(), targetSize.getWidth())
                    : targetSize;

            Object origYuv = symbols.get("WatermarkHold.mYuvInfo", origHold);
            Object clonedYuv = XposedHelpers.callMethod(origYuv, "clone");
            symbols.set("WatermarkYuv.mPictureSize", clonedYuv, normalized);
            float ratio = (float) normalized.getWidth() / (float) Math.max(1, eff.imgW);
            Object oldScaleObj = symbols.get("WatermarkYuv.scaleRatio", origYuv);
            if (oldScaleObj instanceof Float) {
                symbols.set("WatermarkYuv.scaleRatio", clonedYuv, ((Float) oldScaleObj) * ratio);
            }

            PhocusEngine.Metrics m = (normalized.getWidth() == eff.imgW && normalized.getHeight() == eff.imgH)
                    ? eff
                    : computeCameraMetrics(origState.spec, normalized.getWidth(), normalized.getHeight());
            Object scaledHold = buildCameraHold(
                    symbols,
                    origState.spec,
                    m,
                    clonedYuv,
                    origState.meta,
                    origState.assetDir,
                    origState.isPreview);
            param.setResult(scaledHold);
        }
    }

    private static void hookCameraAssembler(Symbols symbols, ClassLoader classLoader) {
        Class<?> assemblerCls = XposedHelpers.findClass(CAMERA_ASSEMBLER, classLoader);

        XposedBridge.hookMethod(symbols.method("WatermarkAssembler.assemble"), new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object hold = param.args[0];
                HoldState state = hold != null ? CAMERA_STATES.get(hold) : null;
                if (state == null) return;

                Object src = param.args[2];
                boolean isPreview = Boolean.TRUE.equals(param.args[3]);
                if (isPreview || src instanceof Bitmap) {
                    if (!(src instanceof Bitmap)) return;
                    Bitmap photo = (Bitmap) src;
                    PhocusEngine.RenderLayers layers = state.getOrRenderForPhoto(
                            photo.getWidth(), photo.getHeight());
                    try {
                        Bitmap out = PhocusEngine.compositeSdr(layers, photo);
                        symbols.set("WatermarkHold.finalBitmap", hold, out);
                        symbols.set("WatermarkHold.finalBuffer", hold, 0L);
                        param.setResult(null);
                    } finally {
                        state.releaseIfTemporary(layers);
                        state.recycleCached();
                    }
                    return;
                }

                trackCaptureState(state);
                PhocusEngine.Metrics eff = state.syncFromHold(symbols, hold);
                Object yuv = symbols.get("WatermarkHold.mYuvInfo", hold);
                Size origScreen = yuv != null ? (Size) symbols.get("WatermarkYuv.mScreenSize", yuv) : null;
                if (yuv != null) {
                    // Match mDisplayMetricsWidth/Height to canvas size so native composite scale == 1.0.
                    symbols.set(
                            "WatermarkYuv.mScreenSize",
                            yuv,
                            new Size(eff.canvasW, eff.canvasH));
                }
                CAMERA_ASSEMBLY_CTX.set(new AssemblyCtx(hold, state, yuv, origScreen));
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                AssemblyCtx ctx = CAMERA_ASSEMBLY_CTX.get();
                CAMERA_ASSEMBLY_CTX.remove();
                if (ctx != null && ctx.yuv != null && ctx.origScreenSize != null) {
                    try {
                        symbols.set("WatermarkYuv.mScreenSize", ctx.yuv, ctx.origScreenSize);
                    } catch (Throwable error) {
                        Log.e(TAG, "restore mScreenSize failed", error);
                    }
                }
            }
        });

        XposedBridge.hookMethod(symbols.method("WatermarkAssembler.drawBg"), new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                AssemblyCtx ctx = CAMERA_ASSEMBLY_CTX.get();
                if (ctx == null || param.args[2] != ctx.hold) return;
                long dstPtr = (Long) param.args[0];
                if (ctx.state.effectiveMetrics().hasBorder) {
                    PhocusEngine.RenderLayers layers = ctx.state.getOrRender();
                    XposedHelpers.callStaticMethod(
                            assemblerCls, "fillBufferWithColor", dstPtr, hexRgb(layers.borderBgColor));
                }
                param.setResult(null);
            }
        });

        XposedBridge.hookMethod(symbols.method("WatermarkAssembler.drawOrigin"), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                AssemblyCtx ctx = CAMERA_ASSEMBLY_CTX.get();
                if (ctx != null && param.args[2] == ctx.hold) {
                    ctx.originDrawn = true;
                }
            }
        });

        XposedBridge.hookMethod(symbols.method("WatermarkAssembler.drawBitmaps"), new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                AssemblyCtx ctx = CAMERA_ASSEMBLY_CTX.get();
                if (ctx == null || param.args[2] != ctx.hold) return;

                PhocusEngine.RenderLayers layers = ctx.state.getOrRender();
                if (!ctx.originDrawn) {
                    if (layers.excludedBitmap != null) {
                        param.args[1] = layerSlots(ctx.state.spec, layers.excludedBitmap);
                    } else {
                        param.setResult(null);
                    }
                } else {
                    param.args[1] = layerSlots(ctx.state.spec, layers.overlayMainBitmap);
                }
            }
        });

        XposedBridge.hookAllMethods(assemblerCls, "assembleForGallery", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object hold = param.args[0];
                HoldState state = hold != null ? CAMERA_STATES.get(hold) : null;
                if (state == null) return;
                Bitmap photo = (Bitmap) param.args[1];
                if (photo == null) return;

                PhocusEngine.RenderLayers layers = state.getOrRenderForPhoto(
                        photo.getWidth(), photo.getHeight());
                try {
                    Bitmap out = PhocusEngine.compositeSdr(layers, photo);
                    symbols.set("WatermarkHold.finalBitmap", hold, out);
                    param.setResult(null);
                } finally {
                    state.releaseIfTemporary(layers);
                    state.recycleCached();
                }
            }
        });
    }

    private static void hookCameraGainmap(Symbols symbols, ClassLoader classLoader) {
        Class<?> assemblerCls = XposedHelpers.findClass(CAMERA_ASSEMBLER, classLoader);

        XposedBridge.hookMethod(symbols.method("WatermarkAppHelper.gainmap"), new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object hold = param.args[0];
                HoldState state = hold != null ? CAMERA_STATES.get(hold) : null;
                if (state == null) return;
                Map<?, ?> map = param.args[1] instanceof Map ? (Map<?, ?>) param.args[1] : null;
                if (map == null || map.get("gain_map") == null) return;

                try {
                    PhocusEngine.Metrics m = state.syncFromHold(symbols, hold);
                    CustomWatermarkSupport.GainmapOutput out = CustomWatermarkSupport.renderCameraGainmapBuffer(
                            assemblerCls, map, m.imgW, m.imgH, m.canvasW, m.canvasH, m.padLeft, m.padTop, m.hasBorder);
                    if (out == null || out.ptr == 0L) return;

                    Object gmHold = symbols.cls("WatermarkHold").getDeclaredConstructor().newInstance();
                    symbols.set("WatermarkHold.finalBuffer", gmHold, out.ptr);
                    setMakeupSize(symbols, gmHold, out.w, out.h);
                    param.setResult(gmHold);
                } finally {
                    state.recycleCached();
                }
            }
        });

        XposedBridge.hookMethod(symbols.method("WatermarkDefer.generate"), new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Map<?, ?> map = param.args[2] instanceof Map ? (Map<?, ?>) param.args[2] : null;
                CAMERA_DEFER_MAP.set(map);
                beginCaptureScope();
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                CAMERA_DEFER_MAP.remove();
                endCaptureScope();
            }
        });

        XposedBridge.hookMethod(symbols.method("WatermarkDefer.addParam"), new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object hold = param.args[1];
                HoldState state = hold != null ? CAMERA_STATES.get(hold) : null;
                if (state == null) return;
                try {
                    Map<?, ?> map = CAMERA_DEFER_MAP.get();
                    if (map == null || map.get("gain_map") == null) return;

                    PhocusEngine.Metrics m = state.syncFromHold(symbols, hold);
                    CustomWatermarkSupport.GainmapOutput out = CustomWatermarkSupport.renderCameraGainmapBuffer(
                            assemblerCls, map, m.imgW, m.imgH, m.canvasW, m.canvasH, m.padLeft, m.padTop, m.hasBorder);
                    if (out == null || out.ptr == 0L) return;

                    Object prevObj = symbols.get("WatermarkHold.gainMapBuffer", hold);
                    long prevPtr = prevObj instanceof Long ? (Long) prevObj : 0L;
                    if (prevPtr != 0L) {
                        XposedHelpers.callStaticMethod(assemblerCls, "releaseHardwareBuffer", prevPtr);
                    }
                    symbols.set("WatermarkHold.gainMapBuffer", hold, out.ptr);
                } finally {
                    state.recycleCached();
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object hold = param.args[1];
                HoldState state = hold != null ? CAMERA_STATES.get(hold) : null;
                if (state != null) {
                    state.recycleCached();
                }
            }
        });
    }

    private static void hookCameraCaptureLifecycle(Symbols symbols) {
        XposedBridge.hookMethod(symbols.method("WatermarkCapture.generate"), new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                beginCaptureScope();
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                endCaptureScope();
            }
        });
    }

    private static void beginCaptureScope() {
        if (CAMERA_CAPTURE_STATES.get() == null) {
            CAMERA_CAPTURE_STATES.set(new ArrayList<>());
        }
    }

    private static void trackCaptureState(HoldState state) {
        List<HoldState> list = CAMERA_CAPTURE_STATES.get();
        if (list != null && !list.contains(state)) {
            list.add(state);
        }
    }

    private static void endCaptureScope() {
        List<HoldState> list = CAMERA_CAPTURE_STATES.get();
        CAMERA_CAPTURE_STATES.remove();
        if (list != null) {
            for (HoldState state : list) {
                state.recycleCached();
            }
        }
    }

    // =========================================================================
    // Camera WatermarkHold & metadata helpers
    // =========================================================================

    private static Object buildCameraHold(
            Symbols symbols,
            PhocusStyles.StyleSpec spec,
            PhocusEngine.Metrics m,
            Object yuv,
            PhocusStyles.PhotoMeta meta,
            String assetDir,
            boolean isPreview)
            throws Throwable {
        Object hold = symbols.cls("WatermarkHold").getDeclaredConstructor().newInstance();
        HOLD_FIELDS.ensure(symbols, hold);

        setMakeupSize(symbols, hold, m.canvasW, m.canvasH);
        symbols.set("WatermarkHold.size", hold, new Size(m.canvasW, m.canvasH));
        HOLD_FIELDS.makeupStartX.setInt(hold, m.padLeft);
        HOLD_FIELDS.makeupStartY.setInt(hold, m.padTop);

        symbols.set("WatermarkHold.mYuvInfo", hold, yuv);
        symbols.set("WatermarkHold.watermarkType", hold, WATERMARK_TYPE_AI_MASTER);
        // The photo's watermark parameters carry the style id; the gallery finds the style by it.
        symbols.set("WatermarkHold.styleId", hold, spec.styleKey());
        symbols.set("WatermarkHold.bgType", hold, 0);
        symbols.set("WatermarkHold.bgColor", hold, hexRgb(spec.maskColor));
        symbols.set("WatermarkHold.bgPath", hold, "");
        symbols.set("WatermarkHold.yuvBytes", hold, new byte[6][]);

        int refWidth = referenceWidth(m);
        int[] refs = new int[]{refWidth, refWidth, refWidth, refWidth, refWidth, refWidth};
        int slot = layerSlot(spec);
        int[] widths = new int[6];
        int[] heights = new int[6];
        widths[slot] = m.canvasW;
        heights[slot] = m.canvasH;
        symbols.set("WatermarkHold.refArray", hold, refs);
        HOLD_FIELDS.bitmapWidthArray.set(hold, widths);
        HOLD_FIELDS.bitmapHeightArray.set(hold, heights);
        HOLD_FIELDS.borderXArray.set(hold, new int[6]);
        HOLD_FIELDS.borderYArray.set(hold, new int[6]);

        Bitmap placeholder = getDummy1x1();
        symbols.set("WatermarkHold.mBitmapList", hold, layerSlots(spec, placeholder));
        Bitmap[] probeA = new Bitmap[6];
        Bitmap[] probeB = new Bitmap[6];
        HOLD_FIELDS.upperDownA.set(hold, probeA);
        HOLD_FIELDS.upperDownB.set(hold, probeB);

        HoldState state = new HoldState(spec, meta, assetDir, m, isPreview);
        if (isPreview) {
            PhocusEngine.RenderLayers previewLayers = PhocusEngine.render(
                    spec, m, meta, assetDir, true, deriveSeed(m.imgW, m.imgH, meta));
            try {
                Bitmap copy = previewLayers.overlayMainBitmap.copy(Bitmap.Config.ARGB_8888, false);
                symbols.set("WatermarkHold.mBitmap", hold, copy != null ? copy : placeholder);
            } finally {
                previewLayers.recycle();
            }
        } else {
            symbols.set("WatermarkHold.mBitmap", hold, placeholder);
        }

        CAMERA_STATES.put(hold, state);
        CustomWatermarkSupport.registerCameraHold(hold, probeA, probeB, new CustomWatermarkSupport.HoldAdapter() {
            @Override
            public int[] computeSubVideoPads(int uprightSubW, int uprightSubH) {
                PhocusEngine.Metrics sub = computeCameraMetrics(spec, uprightSubW, uprightSubH);
                return new int[] {sub.padLeft, sub.padTop};
            }

            @Override
            public CustomWatermarkSupport.LiveGlLayers renderLiveGlLayers() {
                PhocusEngine.RenderLayers layers = state.getOrRenderLiveGl();
                return new CustomWatermarkSupport.LiveGlLayers(
                        layerSlot(spec), layers.excludedBitmap, layers.overlayMainBitmap);
            }
        });
        return hold;
    }

    /**
     * The hold keeps its layers in six slots by direction; the overlay watermarks use the fifth
     * (direction 4, as in their style files), the frames the first. The gallery reads a photo's
     * watermark by these slots.
     */
    /**
     * The native composite scales a layer by the reference width over W^2 / min(W, H) (measured on
     * landscape captures: the scale is proportional to the reference width, and a landscape layer
     * with the reference width W shrinks by H/W towards the bottom left). The reference width of a
     * canvas-sized layer is therefore W^2 / min(W, H): W for a portrait canvas, W^2 / H for a
     * landscape one.
     */
    private static int referenceWidth(PhocusEngine.Metrics m) {
        return CustomWatermarkSupport.referenceWidth(m.canvasW, m.canvasH);
    }

    private static int layerSlot(PhocusStyles.StyleSpec spec) {
        return spec.isOverlay() ? 4 : 0;
    }

    private static Bitmap[] layerSlots(PhocusStyles.StyleSpec spec, Bitmap layer) {
        Bitmap[] slots = new Bitmap[6];
        slots[layerSlot(spec)] = layer;
        return slots;
    }

    private static void setMakeupSize(Symbols symbols, Object hold, int width, int height) throws Throwable {
        HOLD_FIELDS.ensure(symbols, hold);
        HOLD_FIELDS.makeupWidth.setInt(hold, width);
        HOLD_FIELDS.makeupHeight.setInt(hold, height);
    }

    private static PhocusStyles.PhotoMeta extractCameraMeta(Symbols symbols, Object yuv)
            throws IllegalAccessException {
        Object config = symbols.get("WatermarkYuv.mWatermarkUserConfigure", yuv);
        Object shot = config != null ? symbols.get("WatermarkConfig.mShotInfo", config) : null;

        String rawAperture = shot != null ? asString(symbols.get("WatermarkShot.mAperture", shot)) : null;
        String rawShutter = shot != null ? asString(symbols.get("WatermarkShot.mExposureTime", shot)) : null;
        String rawIso = shot != null ? asString(symbols.get("WatermarkShot.mIso", shot)) : null;
        String rawFocal35 = shot != null ? asString(symbols.get("WatermarkShot.mFocalLength", shot)) : null;

        String body = asString(symbols.get("WatermarkYuv.marketName", yuv));
        if (isEmpty(body)) {
            body = asString(symbols.get("WatermarkYuv.enMarketName", yuv));
        }
        if (isEmpty(body)) {
            body = Build.MODEL;
        }

        String time = config != null ? asString(symbols.get("WatermarkConfig.mWatermarkTime", config)) : null;
        if (isEmpty(time)) {
            time = PhocusStyles.formatTimestampMsLocal(System.currentTimeMillis());
        }

        return PhocusStyles.formatMetaFromStrings(
                rawAperture, rawShutter, rawIso, null, rawFocal35, body, time);
    }

    private static String styleIdFromCameraYuv(Symbols symbols, Object yuv)
            throws IllegalAccessException {
        if (yuv == null) return null;
        Object json = symbols.get("WatermarkYuv.jsonObject", yuv);
        return styleIdFromJson(json);
    }

    private static PhocusEngine.Metrics computeCameraMetrics(
            PhocusStyles.StyleSpec spec, int picW, int picH) {
        PhocusEngine.Metrics m = PhocusEngine.computeMetrics(spec, picW, picH);
        if (!m.hasBorder) return m;
        // The picture is copied into the frame buffer at (padLeft, padTop); the buffers are YUV with
        // chroma pairs, so an odd offset swaps the chroma (inverted colours). The stock offsets are even.
        int padLeft = m.padLeft & ~1;
        int padTop = m.padTop & ~1;
        return new PhocusEngine.Metrics(
                m.imgW, m.imgH, m.scale,
                padLeft, padTop, m.padRight, m.padBottom,
                padLeft + m.imgW + m.padRight, padTop + m.imgH + m.padBottom,
                m.isLandscape, true).alignedEven();
    }

    // =========================================================================
    // Shared bitmap, geometry & parsing helpers
    // =========================================================================

    private static String styleIdFromJson(Object jsonObj) {
        if (!(jsonObj instanceof JSONObject)) return null;
        String id = ((JSONObject) jsonObj).optString("styleId", "");
        return id.isEmpty() ? null : id;
    }

    private static int normalizeRotation(int rotation) {
        int r = ((rotation % 360) + 360) % 360;
        if (r == 90 || r == 180 || r == 270) return r;
        return 0;
    }

    private static int[] mapPadsForRotation(int l, int t, int r, int b, int rotation) {
        switch (normalizeRotation(rotation)) {
            case 90:
                return new int[]{b, l, t, r};
            case 180:
                return new int[]{r, b, l, t};
            case 270:
                return new int[]{t, r, b, l};
            default:
                return new int[]{l, t, r, b};
        }
    }

    private static Bitmap rotateBitmapToSoftware(Bitmap src, int degrees) {
        Bitmap work = src;
        if (src.getConfig() == Bitmap.Config.HARDWARE) {
            Bitmap copy = src.copy(Bitmap.Config.ARGB_8888, false);
            if (copy != null) {
                work = copy;
            }
        }
        int deg = normalizeRotation(degrees);
        if (deg == 0) {
            return work;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(deg);
        Bitmap rotated = Bitmap.createBitmap(work, 0, 0, work.getWidth(), work.getHeight(), matrix, true);
        if (work != src && work != rotated) {
            work.recycle();
        }
        return rotated;
    }

    private static Bitmap writeIntoDstOrKeep(Bitmap dst, Bitmap rendered) {
        if (dst == null
                || !dst.isMutable()
                || dst.getWidth() != rendered.getWidth()
                || dst.getHeight() != rendered.getHeight()) {
            return rendered;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ColorSpace cs = rendered.getColorSpace();
            if (cs != null && !cs.equals(dst.getColorSpace())) {
                try {
                    dst.setColorSpace(cs);
                } catch (IllegalArgumentException error) {
                    Log.e(TAG, "dst ColorSpace incompatible with wide-gamut render; returning rendered bitmap", error);
                    return rendered;
                }
            }
        }
        Canvas canvas = new Canvas(dst);
        Paint paint = new Paint();
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        canvas.drawBitmap(rendered, 0f, 0f, paint);
        return dst;
    }

    private static long deriveSeed(int imgW, int imgH, PhocusStyles.PhotoMeta meta) {
        long h = ((long) imgW * 31L) + imgH;
        if (meta != null && meta.createTime != null) {
            h ^= meta.createTime.hashCode();
        }
        return h;
    }

    private static String hexRgb(int color) {
        return String.format(Locale.US, "#%06X", color & 0xFFFFFF);
    }

    private static String asString(Object obj) {
        return obj instanceof String ? (String) obj : null;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    private static Bitmap getDummy1x1() {
        Bitmap b = dummy1x1;
        if (b == null || b.isRecycled()) {
            b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            dummy1x1 = b;
        }
        return b;
    }

    private static final class HoldState {
        final PhocusStyles.StyleSpec spec;
        final PhocusStyles.PhotoMeta meta;
        final String assetDir;
        final PhocusEngine.Metrics initialMetrics;
        private PhocusEngine.Metrics effectiveMetrics;
        final boolean isPreview;
        private PhocusEngine.RenderLayers cachedLayers;
        private PhocusEngine.RenderLayers liveGlLayers;

        HoldState(
                PhocusStyles.StyleSpec spec,
                PhocusStyles.PhotoMeta meta,
                String assetDir,
                PhocusEngine.Metrics metrics,
                boolean isPreview) {
            this.spec = spec;
            this.meta = meta;
            this.assetDir = assetDir;
            this.initialMetrics = metrics;
            this.effectiveMetrics = metrics;
            this.isPreview = isPreview;
        }

        synchronized PhocusEngine.Metrics effectiveMetrics() {
            return effectiveMetrics;
        }

        synchronized PhocusEngine.Metrics syncFromHold(Symbols symbols, Object hold) throws Throwable {
            CustomWatermarkSupport.HoldGeometry g = CustomWatermarkSupport.syncHoldGeometry(
                    symbols,
                    HOLD_FIELDS,
                    hold,
                    layerSlot(spec),
                    initialMetrics.imgW,
                    initialMetrics.imgH,
                    initialMetrics.hasBorder);
            PhocusEngine.Metrics cur = effectiveMetrics;
            if (g.imgW != cur.imgW
                    || g.imgH != cur.imgH
                    || g.canvasW != cur.canvasW
                    || g.canvasH != cur.canvasH
                    || g.padLeft != cur.padLeft
                    || g.padTop != cur.padTop
                    || g.hasBorder != cur.hasBorder) {
                PhocusEngine.Metrics base = PhocusEngine.computeMetrics(spec, g.imgW, g.imgH);
                effectiveMetrics = new PhocusEngine.Metrics(
                        g.imgW,
                        g.imgH,
                        base.scale,
                        g.padLeft,
                        g.padTop,
                        g.padRight,
                        g.padBottom,
                        g.canvasW,
                        g.canvasH,
                        base.isLandscape,
                        g.hasBorder);
                recycleCached();
            }
            return effectiveMetrics;
        }

        synchronized PhocusEngine.RenderLayers getOrRender() {
            if (cachedLayers == null) {
                PhocusEngine.Metrics m = effectiveMetrics;
                cachedLayers = PhocusEngine.render(
                        spec,
                        m,
                        meta,
                        assetDir,
                        isPreview,
                        deriveSeed(m.imgW, m.imgH, meta));
            }
            return cachedLayers;
        }

        synchronized PhocusEngine.RenderLayers getOrRenderLiveGl() {
            if (liveGlLayers == null) {
                PhocusEngine.Metrics m = effectiveMetrics;
                liveGlLayers = PhocusEngine.render(
                        spec,
                        m,
                        meta,
                        assetDir,
                        isPreview,
                        deriveSeed(m.imgW, m.imgH, meta));
            }
            return liveGlLayers;
        }

        synchronized PhocusEngine.RenderLayers getOrRenderForPhoto(int photoW, int photoH) {
            PhocusEngine.Metrics m = effectiveMetrics;
            if (photoW == m.imgW && photoH == m.imgH) {
                return getOrRender();
            }
            PhocusEngine.Metrics pm = computeCameraMetrics(spec, photoW, photoH);
            return PhocusEngine.render(
                    spec, pm, meta, assetDir, isPreview, deriveSeed(photoW, photoH, meta));
        }

        synchronized void releaseIfTemporary(PhocusEngine.RenderLayers layers) {
            if (layers != null && layers != cachedLayers) {
                layers.recycle();
            }
        }

        synchronized void recycleCached() {
            if (cachedLayers != null) {
                cachedLayers.recycle();
                cachedLayers = null;
            }
        }
    }

    private static final class AssemblyCtx {
        final Object hold;
        final HoldState state;
        final Object yuv;
        final Size origScreenSize;
        boolean originDrawn;

        AssemblyCtx(Object hold, HoldState state, Object yuv, Size origScreenSize) {
            this.hold = hold;
            this.state = state;
            this.yuv = yuv;
            this.origScreenSize = origScreenSize;
        }
    }
}
