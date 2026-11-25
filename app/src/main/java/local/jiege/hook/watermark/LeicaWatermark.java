package local.jiege.hook.watermark;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.RectF;
import android.location.Location;
import android.os.Build;
import android.util.Size;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Reflection;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.settings.ModuleSettings;
import org.json.JSONObject;

/**
 * Xposed hooks integrating the 19 Xiaomi HyperOS Leica watermark styles across
 * Gallery ({@code com.coloros.gallery3d}) and Camera ({@code com.oplus.camera}).
 */
public final class LeicaWatermark {
    private static final String TAG = "LeicaWatermark";

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

    private static final Map<Object, HoldState> CAMERA_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<AssemblyCtx> CAMERA_ASSEMBLY_CTX = new ThreadLocal<>();
    private static final ThreadLocal<Map<?, ?>> CAMERA_DEFER_MAP = new ThreadLocal<>();
    private static final ThreadLocal<List<HoldState>> CAMERA_CAPTURE_STATES = new ThreadLocal<>();

    private static final CustomWatermarkSupport.HoldFields HOLD_FIELDS =
            CustomWatermarkSupport.SHARED_HOLD_FIELDS;
    private static volatile Bitmap dummy1x1;

    private LeicaWatermark() {}

    public static boolean ours(Object styleId) {
        return styleId instanceof String && LeicaStyles.isLeicaStyle((String) styleId);
    }

    private static boolean useHasselbladBadge() {
        try {
            return Settings.get(ModuleSettings.LEICA_WATERMARK_HASSELBLAD_BADGE);
        } catch (Throwable ignored) {
            return false;
        }
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
            protected void beforeHookedMethod(MethodHookParam param) {
                Object id = param.args.length > 0 ? param.args[0] : null;
                if (!ours(id)) return;
                param.setResult(!LeicaStyles.isOverlayStyle((String) id));
            }
        });
        XposedBridge.hookAllMethods(companion, "isOnlyOverlayStyle", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object id = param.args.length > 0 ? param.args[0] : null;
                if (!ours(id)) return;
                param.setResult(LeicaStyles.isOverlayStyle((String) id));
            }
        });
        XposedBridge.hookAllMethods(companion, "getStyleType", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object id = param.args.length > 0 ? param.args[0] : null;
                if (!ours(id)) return;
                param.setResult(CustomWatermarkSupport.STYLE_TYPE_PERSONALIZE);
            }
        });
    }

    private static void hookGalleryEditEntry() {
        Symbols symbols = Symbols.get();
        if (symbols == null) return;
        XposedBridge.hookMethod(symbols.method("StyleAdapter.editEntry"), new XC_MethodHook() {
            // Styles without editable items get no edit entry at all: the stock wiring (which also opens the
            // edit page when the selected card is tapped again) is skipped.
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object item = param.args.length > 2 ? param.args[2] : null;
                String id = cardStyleId(item);
                if (!ours(id) || LeicaStyles.supportsBgEdit(id) || !(param.args[0] instanceof View)) return;
                hideFixedEditIcon((View) param.args[0], item);
                param.setResult(null);
            }
        });
        XposedBridge.hookMethod(symbols.method("StyleAdapter.bind"), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object holder = param.args.length > 0 ? param.args[0] : null;
                Object item = param.args.length > 2 ? param.args[2] : null;
                if (holder == null || item == null) return;
                View root = (View) XposedHelpers.getObjectField(holder, "itemView");
                if (root == null) return;
                int editId = root.getResources().getIdentifier("style_item_edit", "id", root.getContext().getPackageName());
                View edit = editId != 0 ? root.findViewById(editId) : null;
                if (edit != null) hideFixedEditIcon(edit, item);
            }
        });
    }

    private static void hideFixedEditIcon(View edit, Object item) {
        String id = cardStyleId(item);
        if (ours(id) && !LeicaStyles.supportsBgEdit(id)) {
            edit.setOnClickListener(null);
            edit.setClickable(false);
            edit.setVisibility(View.GONE);
        }
    }

    /** The style list's item (an obfuscated class) carries its style id as one of its string fields. */
    private static String cardStyleId(Object item) {
        if (item == null) return null;
        for (java.lang.reflect.Field field : item.getClass().getDeclaredFields()) {
            if (field.getType() != String.class) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(item);
                if (value instanceof String && ours(value)) return (String) value;
            } catch (IllegalAccessException ignored) {
                return null;
            }
        }
        return null;
    }

    private static void hookGalleryIpuEditor(ClassLoader classLoader) {
        Class<?> ipuCls = XposedHelpers.findClass(GALLERY_IPU_AI_EDITOR, classLoader);
        Class<?> baseCls = XposedHelpers.findClass(GALLERY_BASE_IPU_EDITOR, classLoader);
        Class<?> sizeInfoCls = XposedHelpers.findClass(GALLERY_SIZE_INFO, classLoader);
        Class<?> editResultCls = XposedHelpers.findClass(GALLERY_EDIT_RESULT, classLoader);

        XposedBridge.hookAllMethods(ipuCls, "isOnlyOverlayWatermark", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String id = styleIdFromJson(param.args.length > 0 ? param.args[0] : null);
                if (!ours(id)) return;
                param.setResult(LeicaStyles.isOverlayStyle(id));
            }
        });
        XposedBridge.hookAllMethods(ipuCls, "isOnlyFrameWatermark", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String id = styleIdFromJson(param.args.length > 0 ? param.args[0] : null);
                if (!ours(id)) return;
                param.setResult(!LeicaStyles.isOverlayStyle(id));
            }
        });
        XposedBridge.hookAllMethods(ipuCls, "isFrameAndOverlayWatermark", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String id = styleIdFromJson(param.args.length > 0 ? param.args[0] : null);
                if (!ours(id)) return;
                param.setResult(Boolean.FALSE);
            }
        });

        XposedHelpers.findAndHookMethod(
                ipuCls,
                "getWatermarkFrameSize",
                JSONObject.class,
                Size.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        JSONObject json = param.args[0] instanceof JSONObject ? (JSONObject) param.args[0] : null;
                        String id = styleIdFromJson(json);
                        if (!ours(id)) return;
                        Size photoSize = (Size) param.args[1];
                        if (photoSize == null || photoSize.getWidth() <= 0 || photoSize.getHeight() <= 0) {
                            return;
                        }
                        int rotation = CustomWatermarkSupport.normalizeRotation((Integer) param.args[2]);
                        int uprightW = (rotation == 90 || rotation == 270) ? photoSize.getHeight() : photoSize.getWidth();
                        int uprightH = (rotation == 90 || rotation == 270) ? photoSize.getWidth() : photoSize.getHeight();

                        LeicaStyles.StylePreset preset = LeicaStyles.getPreset(id);
                        LeicaStyles.BgChoice bg = LeicaStyles.resolveBackgroundChoice(preset, json);
                        LeicaStyles.PhotoMeta dummy = LeicaStyles.buildMetaFromStrings(
                                Build.MODEL, "23", "1.8", "1/250", "100", null,
                                null, null, "", "Summilux", "Vivid", "");
                        LeicaEngine.Metrics m = LeicaEngine.computeMetrics(
                                preset, uprightW, uprightH, dummy,
                                LeicaEngine.GALLERY_ASSET_DIR, bg, useHasselbladBadge(), !preset.isOverlay);

                        int[] rotPads = CustomWatermarkSupport.mapPadsForRotation(
                                m.padLeft, m.padTop, m.padRight, m.padBottom, rotation);
                        int frameW = photoSize.getWidth() + rotPads[0] + rotPads[2];
                        int frameH = photoSize.getHeight() + rotPads[1] + rotPads[3];
                        Rect displayRect = new Rect(
                                rotPads[0],
                                rotPads[1],
                                rotPads[0] + photoSize.getWidth(),
                                rotPads[1] + photoSize.getHeight());
                        Object sizeInfo = XposedHelpers.newInstance(
                                sizeInfoCls,
                                photoSize,
                                new Size(frameW, frameH),
                                displayRect);
                        param.setResult(sizeInfo);
                    }
                });

        XposedBridge.hookAllMethods(baseCls, "addWatermark", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                // addWatermark(source, destination, watermark info, flag)
                Object info = param.args.length > 2 ? param.args[2] : null;
                String id = styleIdFromGalleryInfo(info);
                if (!ours(id)) return;

                Bitmap src = param.args[0] instanceof Bitmap ? (Bitmap) param.args[0] : null;
                if (src == null) return;
                Bitmap dst = param.args[1] instanceof Bitmap ? (Bitmap) param.args[1] : null;

                int rotation = readGalleryRotation(info);
                Object aiParams = XposedHelpers.callMethod(info, "getAiMasterWatermarkInfo");
                JSONObject styleJson = aiParams != null
                        ? (JSONObject) XposedHelpers.callMethod(aiParams, "getmStyleJsonObject")
                        : null;
                LeicaStyles.StylePreset preset = LeicaStyles.getPreset(id);
                LeicaStyles.BgChoice bg = LeicaStyles.resolveBackgroundChoice(preset, styleJson);
                boolean hasselBadge = useHasselbladBadge();
                LeicaStyles.PhotoMeta meta = extractGalleryMeta(info);

                Bitmap uprightSrc = null;
                Bitmap uprightSdr = null;
                Bitmap rotatedSdr = null;
                Bitmap uprightTrans = null;
                Bitmap rotatedTrans = null;
                Bitmap handedResult = null;
                Bitmap handedTrans = null;
                LeicaEngine.RenderLayers layers = null;
                try {
                    int invRot = (360 - rotation) % 360;
                    uprightSrc = CustomWatermarkSupport.rotateBitmapToSoftware(src, invRot);
                    int uprightW = uprightSrc.getWidth();
                    int uprightH = uprightSrc.getHeight();

                    layers = LeicaEngine.render(
                            preset, uprightW, uprightH, meta,
                            LeicaEngine.GALLERY_ASSET_DIR, uprightSrc, bg, hasselBadge, !preset.isOverlay);
                    uprightSdr = LeicaEngine.compositeSdr(layers, uprightSrc);
                    rotatedSdr = CustomWatermarkSupport.rotateBitmapToSoftware(uprightSdr, rotation);
                    Bitmap resultBitmap = CustomWatermarkSupport.writeIntoDstOrKeep(dst, rotatedSdr);

                    Bitmap transparentBgBitmap = null;
                    RectF displayRectF = null;
                    RectF imageDisplayRectF = null;
                    LeicaEngine.Metrics m = layers.metrics;
                    if (preset.isOverlay) {
                        uprightTrans = LeicaEngine.compositeTransparentOverlay(layers);
                        rotatedTrans = CustomWatermarkSupport.rotateBitmapToSoftware(uprightTrans, rotation);
                        transparentBgBitmap = rotatedTrans;
                        rotatedTrans = null;
                        displayRectF = new RectF(0f, 0f,
                                transparentBgBitmap.getWidth(), transparentBgBitmap.getHeight());
                    } else {
                        int[] rotPads = CustomWatermarkSupport.mapPadsForRotation(
                                m.padLeft, m.padTop, m.padRight, m.padBottom, rotation);
                        int outW = src.getWidth() + rotPads[0] + rotPads[2];
                        int outH = src.getHeight() + rotPads[1] + rotPads[3];
                        int photoX = rotPads[0];
                        int photoY = rotPads[1];
                        imageDisplayRectF = new RectF(
                                photoX, photoY, photoX + src.getWidth(), photoY + src.getHeight());
                        if (rotation == 0 && (outW != m.canvasW || outH != m.canvasH)) {
                            imageDisplayRectF = new RectF(
                                    m.padLeft, m.padTop, m.padLeft + uprightW, m.padTop + uprightH);
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
                    // 交给编辑器的 resultBitmap / transparentBgBitmap 不能回收；旋转为 0 时中间图与它们是同一个对象
                    Bitmap[] temps = {uprightSrc, uprightSdr, rotatedSdr, uprightTrans, rotatedTrans};
                    for (int i = 0; i < temps.length; i++) {
                        Bitmap t = temps[i];
                        if (t == null || t == src || t == dst
                                || t == handedResult || t == handedTrans) continue;
                        boolean dup = false;
                        for (int j = 0; j < i; j++) if (temps[j] == t) dup = true;
                        if (!dup) t.recycle();
                    }
                    if (layers != null) layers.recycle();
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
        return rot instanceof Integer ? CustomWatermarkSupport.normalizeRotation((Integer) rot) : 0;
    }

    private static LeicaStyles.PhotoMeta extractGalleryMeta(Object info) {
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
        if (isEmpty(body)) body = Build.MODEL;

        String time = content != null ? asString(XposedHelpers.callMethod(content, "getTime")) : null;
        String address = content != null ? asString(XposedHelpers.callMethod(content, "getLocation")) : "";

        Double lat = null;
        Double lon = null;
        if (content != null) {
            Object arrObj = XposedHelpers.callMethod(content, "getLocationArray");
            if (arrObj instanceof double[]) {
                double[] arr = (double[]) arrObj;
                if (arr.length >= 2 && Double.isFinite(arr[0]) && Double.isFinite(arr[1])) {
                    lat = arr[0];
                    lon = arr[1];
                }
            }
            if (lat == null || lon == null) {
                Object camAddr = XposedHelpers.callMethod(content, "getCameraAddress");
                if (camAddr != null) {
                    Location loc = findLocationField(camAddr);
                    if (loc != null) {
                        lat = loc.getLatitude();
                        lon = loc.getLongitude();
                    }
                }
            }
        }

        String filterName = "";
        try {
            Object realme = XposedHelpers.callMethod(info, "getRealmeWatermarkInfo");
            if (realme != null) {
                Object dirInfo = XposedHelpers.callMethod(realme, "getDirectorFilterInfo");
                if (dirInfo != null) {
                    filterName = asString(XposedHelpers.callMethod(dirInfo, "getDirectorFilter"));
                }
                if (isEmpty(filterName)) {
                    filterName = asString(XposedHelpers.callMethod(realme, "getFilter"));
                }
            }
        } catch (Throwable ignored) {
        }

        return LeicaStyles.buildMetaFromStrings(
                body, rawFocal35, rawAperture, rawShutter, rawIso, time,
                lat, lon, address, "Summilux", filterName != null ? filterName : "", "");
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
                : LeicaEngine.CAMERA_ASSET_DIR;

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
                        JSONObject json = param.args[1] instanceof JSONObject ? (JSONObject) param.args[1] : null;
                        String id = styleIdFromJson(json);
                        if (!ours(id)) return;
                        Size picSize = (Size) param.args[0];
                        if (picSize == null || picSize.getWidth() <= 0 || picSize.getHeight() <= 0) return;

                        LeicaStyles.StylePreset preset = LeicaStyles.getPreset(id);
                        LeicaStyles.BgChoice bg = LeicaStyles.resolveBackgroundChoice(preset, json);
                        LeicaStyles.PhotoMeta dummy = LeicaStyles.buildMetaFromStrings(
                                Build.MODEL, "23", "1.8", "1/250", "100", null,
                                null, null, "", "Summilux", "Vivid", "");
                        LeicaEngine.Metrics m = LeicaEngine.computeMetrics(
                                preset, picSize.getWidth(), picSize.getHeight(), dummy,
                                assetDir, bg, useHasselbladBadge(), true);
                        param.setResult(new Size(m.canvasW, m.canvasH));
                    }
                });

        XposedBridge.hookMethod(symbols.method("WatermarkCreator.create"), new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object yuv = param.args[1];
                JSONObject json = yuv != null ? (JSONObject) symbols.get("WatermarkYuv.jsonObject", yuv) : null;
                String id = styleIdFromJson(json);
                if (!ours(id)) return;

                Size picSize = (Size) symbols.get("WatermarkYuv.mPictureSize", yuv);
                if (picSize == null || picSize.getWidth() <= 0 || picSize.getHeight() <= 0) return;

                LeicaStyles.StylePreset preset = LeicaStyles.getPreset(id);
                LeicaStyles.BgChoice bg = LeicaStyles.resolveBackgroundChoice(preset, json);
                boolean hasselBadge = useHasselbladBadge();
                LeicaStyles.PhotoMeta meta = extractCameraMeta(symbols, yuv);
                LeicaEngine.Metrics m = LeicaEngine.computeMetrics(
                        preset, picSize.getWidth(), picSize.getHeight(), meta,
                        assetDir, bg, hasselBadge, true);
                boolean isVisual = Boolean.TRUE.equals(symbols.get("WatermarkYuv.isFromVisual", yuv));

                Object hold = buildCameraHold(
                        symbols, preset, m, yuv, meta, assetDir, bg, hasselBadge, isVisual);
                param.setResult(hold);
            }
        });
    }

    private static void hookCameraDownscalers(Symbols symbols) {
        XposedBridge.hookMethod(symbols.method("WatermarkAppHelper.downscale"),
                new DownscaleHook(symbols, 2));
        XposedBridge.hookMethod(symbols.method("WatermarkHd.scaleHold"),
                new DownscaleHook(symbols, 1));
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
            if (targetSize == null || targetSize.getWidth() <= 0 || targetSize.getHeight() <= 0) return;

            LeicaEngine.Metrics eff = origState.syncFromHold(symbols, origHold);
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

            LeicaEngine.Metrics m = (normalized.getWidth() == eff.imgW && normalized.getHeight() == eff.imgH)
                    ? eff
                    : LeicaEngine.computeMetrics(
                            origState.preset,
                            normalized.getWidth(),
                            normalized.getHeight(),
                            origState.meta,
                            origState.assetDir,
                            origState.bgChoice,
                            origState.hasselbladBadge,
                            true);
            Object scaledHold = buildCameraHold(
                    symbols,
                    origState.preset,
                    m,
                    clonedYuv,
                    origState.meta,
                    origState.assetDir,
                    origState.bgChoice,
                    origState.hasselbladBadge,
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
                    LeicaEngine.RenderLayers layers = state.getOrRenderForPhoto(
                            photo.getWidth(), photo.getHeight(), photo);
                    try {
                        Bitmap out = LeicaEngine.compositeSdr(layers, photo);
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
                LeicaEngine.Metrics eff = state.syncFromHold(symbols, hold);
                Object yuv = symbols.get("WatermarkHold.mYuvInfo", hold);
                Size origScreen = yuv != null ? (Size) symbols.get("WatermarkYuv.mScreenSize", yuv) : null;
                if (yuv != null) {
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
                long srcPtr = (Long) param.args[1];
                LeicaEngine.Metrics eff = ctx.state.effectiveMetrics();
                if (eff.hasBorder) {
                    if (eff.isBlur) {
                        XposedHelpers.callStaticMethod(assemblerCls, "gaussianBlur", dstPtr, srcPtr, 25, 10.0f);
                    } else {
                        LeicaEngine.RenderLayers layers = ctx.state.getOrRenderYuv();
                        XposedHelpers.callStaticMethod(
                                assemblerCls, "fillBufferWithColor", dstPtr,
                                CustomWatermarkSupport.hexRgb(layers.borderBgColor));
                    }
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

                LeicaEngine.RenderLayers layers = ctx.state.getOrRenderYuv();
                if (!ctx.originDrawn) {
                    if (layers.excludedBitmap != null) {
                        param.args[1] = layerSlots(ctx.state.preset, layers.excludedBitmap);
                    } else {
                        param.setResult(null);
                    }
                } else {
                    param.args[1] = layerSlots(ctx.state.preset, layers.overlayMainBitmap);
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

                LeicaEngine.RenderLayers layers = state.getOrRenderForPhoto(
                        photo.getWidth(), photo.getHeight(), photo);
                try {
                    Bitmap out = LeicaEngine.compositeSdr(layers, photo);
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
                    LeicaEngine.Metrics m = state.syncFromHold(symbols, hold);
                    CustomWatermarkSupport.GainmapOutput out = CustomWatermarkSupport.renderCameraGainmapBuffer(
                            assemblerCls, map, m.imgW, m.imgH, m.canvasW, m.canvasH, m.padLeft, m.padTop, m.hasBorder);
                    if (out == null || out.ptr == 0L) return;

                    Object gmHold = symbols.cls("WatermarkHold").getDeclaredConstructor().newInstance();
                    symbols.set("WatermarkHold.finalBuffer", gmHold, out.ptr);
                    HOLD_FIELDS.ensure(symbols, gmHold);
                    HOLD_FIELDS.makeupWidth.setInt(gmHold, out.w);
                    HOLD_FIELDS.makeupHeight.setInt(gmHold, out.h);
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
                    LeicaEngine.Metrics m = state.syncFromHold(symbols, hold);
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
                if (state != null) state.recycleCached();
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
        if (list != null && !list.contains(state)) list.add(state);
    }

    private static void endCaptureScope() {
        List<HoldState> list = CAMERA_CAPTURE_STATES.get();
        CAMERA_CAPTURE_STATES.remove();
        if (list != null) {
            for (HoldState state : list) state.recycleCached();
        }
    }

    // =========================================================================
    // Camera WatermarkHold & metadata helpers
    // =========================================================================

    private static Object buildCameraHold(
            Symbols symbols,
            LeicaStyles.StylePreset preset,
            LeicaEngine.Metrics m,
            Object yuv,
            LeicaStyles.PhotoMeta meta,
            String assetDir,
            LeicaStyles.BgChoice bgChoice,
            boolean hasselbladBadge,
            boolean isPreview)
            throws Throwable {
        Object hold = symbols.cls("WatermarkHold").getDeclaredConstructor().newInstance();
        HOLD_FIELDS.ensure(symbols, hold);

        HOLD_FIELDS.makeupWidth.setInt(hold, m.canvasW);
        HOLD_FIELDS.makeupHeight.setInt(hold, m.canvasH);
        symbols.set("WatermarkHold.size", hold, new Size(m.canvasW, m.canvasH));
        HOLD_FIELDS.makeupStartX.setInt(hold, m.padLeft);
        HOLD_FIELDS.makeupStartY.setInt(hold, m.padTop);

        symbols.set("WatermarkHold.mYuvInfo", hold, yuv);
        symbols.set("WatermarkHold.watermarkType", hold, CustomWatermarkSupport.WATERMARK_TYPE_AI_MASTER);
        symbols.set("WatermarkHold.styleId", hold, LeicaStyles.STYLE_PREFIX + preset.id);
        symbols.set("WatermarkHold.bgType", hold, m.isBlur ? 2 : 0);
        symbols.set("WatermarkHold.bgColor", hold, CustomWatermarkSupport.hexRgb(m.bgColor));
        symbols.set("WatermarkHold.bgPath", hold, "");
        symbols.set("WatermarkHold.yuvBytes", hold, new byte[6][]);

        int refWidth = referenceWidth(m);
        int[] refs = new int[] {refWidth, refWidth, refWidth, refWidth, refWidth, refWidth};
        int slot = layerSlot(preset);
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
        symbols.set("WatermarkHold.mBitmapList", hold, layerSlots(preset, placeholder));
        Bitmap[] probeA = new Bitmap[6];
        Bitmap[] probeB = new Bitmap[6];
        HOLD_FIELDS.upperDownA.set(hold, probeA);
        HOLD_FIELDS.upperDownB.set(hold, probeB);

        HoldState state = new HoldState(preset, meta, assetDir, bgChoice, hasselbladBadge, m, isPreview);
        if (isPreview) {
            LeicaEngine.RenderLayers previewLayers = LeicaEngine.render(
                    preset, m.imgW, m.imgH, meta, assetDir, null, bgChoice, hasselbladBadge, true);
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
                LeicaEngine.Metrics sub = LeicaEngine.computeMetrics(
                        preset, uprightSubW, uprightSubH, meta, assetDir, bgChoice, hasselbladBadge, true);
                return sub.hasBorder
                        ? new int[] {sub.padLeft & ~1, sub.padTop & ~1}
                        : new int[] {0, 0};
            }

            @Override
            public CustomWatermarkSupport.LiveGlLayers renderLiveGlLayers() {
                LeicaEngine.RenderLayers layers = state.getOrRenderLiveGl();
                return new CustomWatermarkSupport.LiveGlLayers(
                        layerSlot(preset), layers.excludedBitmap, layers.overlayMainBitmap);
            }
        });
        return hold;
    }

    private static int referenceWidth(LeicaEngine.Metrics m) {
        return CustomWatermarkSupport.referenceWidth(m.canvasW, m.canvasH);
    }

    private static int layerSlot(LeicaStyles.StylePreset preset) {
        return preset.isOverlay ? 4 : 0;
    }

    private static Bitmap[] layerSlots(LeicaStyles.StylePreset preset, Bitmap layer) {
        Bitmap[] slots = new Bitmap[6];
        slots[layerSlot(preset)] = layer;
        return slots;
    }

    private static LeicaStyles.PhotoMeta extractCameraMeta(Symbols symbols, Object yuv)
            throws IllegalAccessException {
        Object config = symbols.get("WatermarkYuv.mWatermarkUserConfigure", yuv);
        Object shot = config != null ? symbols.get("WatermarkConfig.mShotInfo", config) : null;

        String rawAperture = shot != null ? asString(symbols.get("WatermarkShot.mAperture", shot)) : null;
        String rawShutter = shot != null ? asString(symbols.get("WatermarkShot.mExposureTime", shot)) : null;
        String rawIso = shot != null ? asString(symbols.get("WatermarkShot.mIso", shot)) : null;
        String rawFocal35 = shot != null ? asString(symbols.get("WatermarkShot.mFocalLength", shot)) : null;

        String body = asString(symbols.get("WatermarkYuv.marketName", yuv));
        if (isEmpty(body)) body = asString(symbols.get("WatermarkYuv.enMarketName", yuv));
        if (isEmpty(body)) body = Build.MODEL;

        String time = config != null ? asString(symbols.get("WatermarkConfig.mWatermarkTime", config)) : null;
        Double lat = null;
        Double lon = null;
        if (config != null) {
            Object addrObj = symbols.get("WatermarkConfig.address", config);
            if (addrObj != null) {
                Location loc = findLocationField(addrObj);
                if (loc != null) {
                    lat = loc.getLatitude();
                    lon = loc.getLongitude();
                }
            }
        }

        return LeicaStyles.buildMetaFromStrings(
                body, rawFocal35, rawAperture, rawShutter, rawIso, time,
                lat, lon, "", "Summilux", "", "");
    }

    private static Location findLocationField(Object target) {
        if (target == null) return null;
        try {
            Object found = Reflection.findFieldByType(target, "android.location.Location");
            if (found instanceof Location) return (Location) found;
            for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) && f.getType() == Location.class) {
                        f.setAccessible(true);
                        Object v = f.get(target);
                        if (v instanceof Location) return (Location) v;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String styleIdFromJson(Object jsonObj) {
        if (!(jsonObj instanceof JSONObject)) return null;
        String id = ((JSONObject) jsonObj).optString("styleId", "");
        return id.isEmpty() ? null : id;
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
        final LeicaStyles.StylePreset preset;
        final LeicaStyles.PhotoMeta meta;
        final String assetDir;
        final LeicaStyles.BgChoice bgChoice;
        final boolean hasselbladBadge;
        final LeicaEngine.Metrics initialMetrics;
        private LeicaEngine.Metrics effectiveMetrics;
        final boolean isPreview;
        private LeicaEngine.RenderLayers cachedLayers;
        private LeicaEngine.RenderLayers liveGlLayers;

        HoldState(
                LeicaStyles.StylePreset preset,
                LeicaStyles.PhotoMeta meta,
                String assetDir,
                LeicaStyles.BgChoice bgChoice,
                boolean hasselbladBadge,
                LeicaEngine.Metrics metrics,
                boolean isPreview) {
            this.preset = preset;
            this.meta = meta;
            this.assetDir = assetDir;
            this.bgChoice = bgChoice;
            this.hasselbladBadge = hasselbladBadge;
            this.initialMetrics = metrics;
            this.effectiveMetrics = metrics;
            this.isPreview = isPreview;
        }

        synchronized LeicaEngine.Metrics effectiveMetrics() {
            return effectiveMetrics;
        }

        synchronized LeicaEngine.Metrics syncFromHold(Symbols symbols, Object hold) throws Throwable {
            CustomWatermarkSupport.HoldGeometry g = CustomWatermarkSupport.syncHoldGeometry(
                    symbols,
                    HOLD_FIELDS,
                    hold,
                    layerSlot(preset),
                    initialMetrics.imgW,
                    initialMetrics.imgH,
                    initialMetrics.hasBorder);
            LeicaEngine.Metrics cur = effectiveMetrics;
            if (g.imgW != cur.imgW
                    || g.imgH != cur.imgH
                    || g.canvasW != cur.canvasW
                    || g.canvasH != cur.canvasH
                    || g.padLeft != cur.padLeft
                    || g.padTop != cur.padTop
                    || g.hasBorder != cur.hasBorder) {
                effectiveMetrics = new LeicaEngine.Metrics(
                        g.imgW,
                        g.imgH,
                        Math.min(g.imgW, g.imgH) / 1080.0,
                        g.padLeft,
                        g.padTop,
                        g.padRight,
                        g.padBottom,
                        g.canvasW,
                        g.canvasH,
                        g.hasBorder,
                        initialMetrics.bgColor,
                        initialMetrics.isBlur,
                        initialMetrics.isLightBlur);
                recycleCached();
            }
            return effectiveMetrics;
        }

        synchronized LeicaEngine.RenderLayers getOrRenderYuv() {
            if (cachedLayers == null) {
                cachedLayers = renderYuvLayersLocked();
            }
            return cachedLayers;
        }

        synchronized LeicaEngine.RenderLayers getOrRenderLiveGl() {
            if (liveGlLayers == null) {
                liveGlLayers = renderYuvLayersLocked();
            }
            return liveGlLayers;
        }

        private LeicaEngine.RenderLayers renderYuvLayersLocked() {
            LeicaEngine.Metrics eff = effectiveMetrics;
            LeicaEngine.RenderLayers raw = LeicaEngine.render(
                    preset, eff.imgW, eff.imgH, meta, assetDir, null, bgChoice, hasselbladBadge, true);
            LeicaEngine.Metrics rm = raw.metrics;
            if (rm.canvasW != eff.canvasW
                    || rm.canvasH != eff.canvasH
                    || rm.padLeft != eff.padLeft
                    || rm.padTop != eff.padTop) {
                int dx = eff.padLeft - rm.padLeft;
                int dy = eff.padTop - rm.padTop;
                Bitmap adaptedOverlay = CustomWatermarkSupport.fitBitmapToCanvas(
                        raw.overlayMainBitmap, eff.canvasW, eff.canvasH, dx, dy);
                Bitmap adaptedExcl;
                if (eff.hasBorder && rm.isBlur) {
                    if (raw.excludedBitmap != null && !raw.excludedBitmap.isRecycled()) {
                        raw.excludedBitmap.recycle();
                    }
                    adaptedExcl = Bitmap.createBitmap(eff.canvasW, eff.canvasH, Bitmap.Config.ARGB_8888);
                    new android.graphics.Canvas(adaptedExcl).drawColor(
                            rm.isLightBlur ? 0x80FFFFFF : 0x80000000);
                } else {
                    adaptedExcl = CustomWatermarkSupport.fitBitmapToCanvas(
                            raw.excludedBitmap, eff.canvasW, eff.canvasH, dx, dy);
                }
                LeicaEngine.Metrics merged = new LeicaEngine.Metrics(
                        eff.imgW,
                        eff.imgH,
                        rm.scale,
                        eff.padLeft,
                        eff.padTop,
                        eff.padRight,
                        eff.padBottom,
                        eff.canvasW,
                        eff.canvasH,
                        eff.hasBorder,
                        rm.bgColor,
                        rm.isBlur,
                        rm.isLightBlur);
                effectiveMetrics = merged;
                return new LeicaEngine.RenderLayers(
                        merged, raw.borderBgColor, adaptedExcl, adaptedOverlay);
            }
            effectiveMetrics = rm;
            return raw;
        }

        synchronized LeicaEngine.RenderLayers getOrRenderForPhoto(int photoW, int photoH, Bitmap photo) {
            LeicaEngine.Metrics eff = effectiveMetrics;
            if (photo == null && photoW == eff.imgW && photoH == eff.imgH) {
                return getOrRenderYuv();
            }
            return LeicaEngine.render(
                    preset, photoW, photoH, meta, assetDir, photo, bgChoice, hasselbladBadge, true);
        }

        synchronized void releaseIfTemporary(LeicaEngine.RenderLayers layers) {
            if (layers != null && layers != cachedLayers) layers.recycle();
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
