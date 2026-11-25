package local.jiege.hook.watermark;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.os.Build;
import android.util.Size;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Shared helpers for integrating custom watermark engines ({@link PhocusWatermark}, {@link LeicaWatermark})
 * into OPPO Gallery's IPU watermark editor and Camera's MasterWatermark / Live Photo pipelines.
 */
final class CustomWatermarkSupport {
    private static final String TAG = "CustomWatermarkSupport";
    static final int STYLE_TYPE_PERSONALIZE = 2;
    static final int WATERMARK_TYPE_AI_MASTER = 9;
    static final int DEFAULT_GAINMAP_FORMAT = 3;
    private static final int MAX_LIVE_GL_RETAINED = 4;
    private static final String EXTRA_ORIG_JSON = "custom.wm.origJson";
    private static final String EXTRA_JSON_SAVED = "custom.wm.jsonSaved";
    private static final String EXTRA_PREV_SUB_CTX = "custom.wm.prevSubCtx";
    private static final String EXTRA_PREV_OFFSET_FLAG = "custom.wm.prevOffsetFlag";
    private static final String EXTRA_PREV_LIVE_UPDATE = "custom.wm.prevLiveUpdate";
    private static final String EXTRA_PREV_RENDER_CTX = "custom.wm.prevRenderCtx";

    static final HoldFields SHARED_HOLD_FIELDS = new HoldFields();

    private static final Object HOOK_LOCK = new Object();
    private static volatile boolean sharedHooksInstalled;
    private static volatile Field deferParamJsonField;
    private static volatile boolean deferParamJsonProbed;

    private static final Map<Object, HoldEntry> HOLD_ENTRIES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Bitmap[], HoldEntry> ARRAY_ENTRIES =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final ArrayDeque<LiveGlLayers> RETAINED_LIVE_GL = new ArrayDeque<>();

    private static final ThreadLocal<Boolean> IN_LIVE_PHOTO_UPDATE = new ThreadLocal<>();
    private static final ThreadLocal<RenderLayersCtx> RENDER_CTX = new ThreadLocal<>();
    private static final ThreadLocal<SubRectCtx> SUB_RECT_CTX = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> ENSURE_JSON_OFFSET = new ThreadLocal<>();

    private CustomWatermarkSupport() {}

    interface HoldAdapter {
        int[] computeSubVideoPads(int uprightSubW, int uprightSubH);

        LiveGlLayers renderLiveGlLayers();
    }

    static final class LiveGlLayers {
        final int slot;
        final Bitmap excludedBitmap;
        final Bitmap overlayMainBitmap;

        LiveGlLayers(int slot, Bitmap excludedBitmap, Bitmap overlayMainBitmap) {
            this.slot = slot;
            this.excludedBitmap = excludedBitmap;
            this.overlayMainBitmap = overlayMainBitmap;
        }
    }

    private static final class HoldEntry {
        final Object hold;
        final Bitmap[] probeA;
        final Bitmap[] probeB;
        final HoldAdapter adapter;
        volatile LiveGlLayers cachedGlLayers;

        HoldEntry(Object hold, Bitmap[] probeA, Bitmap[] probeB, HoldAdapter adapter) {
            this.hold = hold;
            this.probeA = probeA;
            this.probeB = probeB;
            this.adapter = adapter;
        }

        LiveGlLayers getOrRenderLiveGlLayers() {
            LiveGlLayers layers = cachedGlLayers;
            if (layers == null) {
                layers = adapter.renderLiveGlLayers();
                cachedGlLayers = layers;
                retainLiveGlLayers(layers);
            }
            return layers;
        }
    }

    private static final class RenderLayersCtx {
        final RenderLayersCtx previous;
        int uploadCallCount;
        HoldEntry entry;
        LiveGlLayers layers;

        RenderLayersCtx(RenderLayersCtx previous) {
            this.previous = previous;
        }
    }

    private static final class SubRectCtx {
        final SubRectCtx previous;
        final HoldAdapter adapter;
        int axis;
        int[] cachedPads;

        SubRectCtx(SubRectCtx previous, HoldAdapter adapter) {
            this.previous = previous;
            this.adapter = adapter;
        }
    }

    static boolean isLivePhotoUpdate() {
        return Boolean.TRUE.equals(IN_LIVE_PHOTO_UPDATE.get());
    }

    static void registerCameraHold(
            Object hold, Bitmap[] probeA, Bitmap[] probeB, HoldAdapter adapter) {
        if (hold == null || adapter == null) return;
        HoldEntry entry = new HoldEntry(hold, probeA, probeB, adapter);
        HOLD_ENTRIES.put(hold, entry);
        synchronized (ARRAY_ENTRIES) {
            trimArrayEntriesLocked();
            if (probeA != null) ARRAY_ENTRIES.put(probeA, entry);
            if (probeB != null) ARRAY_ENTRIES.put(probeB, entry);
        }
    }

    private static void registerLayerArray(Bitmap[] array, HoldEntry entry) {
        if (array == null || entry == null) return;
        synchronized (ARRAY_ENTRIES) {
            trimArrayEntriesLocked();
            ARRAY_ENTRIES.put(array, entry);
        }
    }

    private static void trimArrayEntriesLocked() {
        if (ARRAY_ENTRIES.size() <= 64) return;
        Iterator<HoldEntry> it = ARRAY_ENTRIES.values().iterator();
        while (it.hasNext() && ARRAY_ENTRIES.size() > 32) {
            HoldEntry e = it.next();
            if (!HOLD_ENTRIES.containsKey(e.hold)) {
                it.remove();
            }
        }
    }

    private static void retainLiveGlLayers(LiveGlLayers layers) {
        if (layers == null) return;
        synchronized (RETAINED_LIVE_GL) {
            RETAINED_LIVE_GL.remove(layers);
            RETAINED_LIVE_GL.addLast(layers);
            while (RETAINED_LIVE_GL.size() > MAX_LIVE_GL_RETAINED) {
                RETAINED_LIVE_GL.removeFirst();
            }
        }
    }

    static Bitmap[] layerSlots(int slot, Bitmap layer) {
        Bitmap[] slots = new Bitmap[6];
        if (layer != null && slot >= 0 && slot < slots.length) {
            slots[slot] = layer;
        }
        return slots;
    }

    /**
     * Installs process-wide hooks shared across custom watermark engines:
     * Live Photo GL layer upload, exact sub-video crop rect computation via {@code dp2Pixel},
     * and {@code LivePhotoWatermark.update} scoping.
     */
    static void installSharedCameraHooks(Symbols symbols) {
        if (sharedHooksInstalled || symbols == null) return;
        synchronized (HOOK_LOCK) {
            if (sharedHooksInstalled) return;
            hookLivePhotoGlPipeline(symbols);
            hookLivePhotoVideoDisplayRect(symbols);
            sharedHooksInstalled = true;
        }
    }

    private static void hookLivePhotoGlPipeline(Symbols symbols) {
        if (symbols.has("LivePhotoWatermark.update")) {
            XposedBridge.hookMethod(symbols.method("LivePhotoWatermark.update"), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setObjectExtra(EXTRA_PREV_LIVE_UPDATE, IN_LIVE_PHOTO_UPDATE.get());
                    IN_LIVE_PHOTO_UPDATE.set(Boolean.TRUE);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object prev = param.getObjectExtra(EXTRA_PREV_LIVE_UPDATE);
                    if (prev instanceof Boolean) IN_LIVE_PHOTO_UPDATE.set((Boolean) prev);
                    else IN_LIVE_PHOTO_UPDATE.remove();
                }
            });
        }

        if (symbols.has("LivePhotoWatermark.renderLayers", "LivePhotoWatermark.uploadBitmaps")) {
            XposedBridge.hookMethod(symbols.method("LivePhotoWatermark.renderLayers"), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    RenderLayersCtx prev = RENDER_CTX.get();
                    param.setObjectExtra(EXTRA_PREV_RENDER_CTX, prev);
                    RENDER_CTX.set(new RenderLayersCtx(prev));
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    RenderLayersCtx prev = (RenderLayersCtx) param.getObjectExtra(EXTRA_PREV_RENDER_CTX);
                    if (prev != null) RENDER_CTX.set(prev);
                    else RENDER_CTX.remove();
                }
            });

            XposedBridge.hookMethod(symbols.method("LivePhotoWatermark.uploadBitmaps"), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    RenderLayersCtx ctx = RENDER_CTX.get();
                    if (ctx == null) return;
                    int phase = ctx.uploadCallCount++;
                    Bitmap[] argArr = (param.args.length > 0 && param.args[0] instanceof Bitmap[])
                            ? (Bitmap[]) param.args[0]
                            : null;
                    HoldEntry entry;
                    synchronized (ARRAY_ENTRIES) {
                        entry = argArr != null ? ARRAY_ENTRIES.get(argArr) : null;
                    }
                    if (entry == null && phase == 1) {
                        entry = ctx.entry;
                    }
                    if (entry == null) return;
                    ctx.entry = entry;

                    try {
                        SHARED_HOLD_FIELDS.ensure(symbols, entry.hold);
                        if (phase == 0 && argArr != null) {
                            SHARED_HOLD_FIELDS.disambiguateUpperDown(argArr, entry.probeA, entry.probeB);
                        }
                        LiveGlLayers layers = ctx.layers;
                        if (layers == null) {
                            layers = entry.getOrRenderLiveGlLayers();
                            ctx.layers = layers;
                        }
                        if (phase == 0) {
                            Bitmap[] downSlots = layerSlots(layers.slot, layers.excludedBitmap);
                            registerLayerArray(downSlots, entry);
                            param.args[0] = downSlots;
                            if (SHARED_HOLD_FIELDS.downBitmapList != null) {
                                SHARED_HOLD_FIELDS.downBitmapList.set(entry.hold, downSlots);
                            }
                        } else {
                            Bitmap[] upSlots = layerSlots(layers.slot, layers.overlayMainBitmap);
                            registerLayerArray(upSlots, entry);
                            param.args[0] = upSlots;
                            if (SHARED_HOLD_FIELDS.upperBitmapList != null) {
                                SHARED_HOLD_FIELDS.upperBitmapList.set(entry.hold, upSlots);
                            }
                        }
                    } catch (Throwable error) {
                        Log.e(TAG, "LivePhoto GL bitmap upload hook failed", error);
                    }
                }
            });
        }
    }

    private static void hookLivePhotoVideoDisplayRect(Symbols symbols) {
        if (symbols.has("WatermarkElem.dp2Pixel")) {
            XposedBridge.hookMethod(symbols.method("WatermarkElem.dp2Pixel"), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    SubRectCtx ctx = SUB_RECT_CTX.get();
                    if (ctx == null || param.args.length == 0 || param.args[0] == null) return;
                    try {
                        Size uprightSubSize = (Size) symbols.get("WatermarkYuv.mPictureSize", param.args[0]);
                        if (uprightSubSize == null
                                || uprightSubSize.getWidth() <= 0
                                || uprightSubSize.getHeight() <= 0) {
                            return;
                        }
                        int axis = ctx.axis++;
                        if (axis == 0 || ctx.cachedPads == null) {
                            ctx.cachedPads = ctx.adapter.computeSubVideoPads(
                                    uprightSubSize.getWidth(), uprightSubSize.getHeight());
                        }
                        if (ctx.cachedPads != null && axis < 2) {
                            param.setResult(ctx.cachedPads[axis]);
                        }
                    } catch (Throwable error) {
                        Log.e(TAG, "dp2Pixel sub-video pad override failed", error);
                    }
                }
            });
        }

        if (symbols.has("WatermarkDefer.addParam")) {
            XposedBridge.hookMethod(symbols.method("WatermarkDefer.addParam"), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object hold = param.args.length > 1 ? param.args[1] : null;
                    HoldEntry entry = hold != null ? HOLD_ENTRIES.get(hold) : null;
                    if (entry == null) return;

                    SubRectCtx prev = SUB_RECT_CTX.get();
                    param.setObjectExtra(EXTRA_PREV_SUB_CTX, prev);
                    SUB_RECT_CTX.set(new SubRectCtx(prev, entry.adapter));

                    Object reqParam = param.args[0];
                    if (reqParam != null) {
                        Field jf = resolveJsonField(reqParam.getClass());
                        if (jf != null) {
                            try {
                                JSONObject orig = (JSONObject) jf.get(reqParam);
                                param.setObjectExtra(EXTRA_ORIG_JSON, orig);
                                param.setObjectExtra(EXTRA_JSON_SAVED, Boolean.TRUE);
                                jf.set(reqParam, ensureImageOffsetJson(orig));
                            } catch (Throwable error) {
                                Log.e(TAG, "prepare defer sub-video json failed", error);
                            }
                        }
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(param.getObjectExtra(EXTRA_JSON_SAVED))) {
                        Object reqParam = param.args[0];
                        Field jf = reqParam != null ? resolveJsonField(reqParam.getClass()) : null;
                        if (jf != null) {
                            try {
                                jf.set(reqParam, param.getObjectExtra(EXTRA_ORIG_JSON));
                            } catch (Throwable error) {
                                Log.e(TAG, "restore defer sub-video json failed", error);
                            }
                        }
                    }
                    if (param.getObjectExtra(EXTRA_PREV_SUB_CTX) != null
                            || param.getObjectExtra(EXTRA_JSON_SAVED) != null) {
                        SubRectCtx prev = (SubRectCtx) param.getObjectExtra(EXTRA_PREV_SUB_CTX);
                        if (prev != null) SUB_RECT_CTX.set(prev);
                        else SUB_RECT_CTX.remove();
                    } else {
                        SubRectCtx cur = SUB_RECT_CTX.get();
                        if (cur != null) {
                            if (cur.previous != null) SUB_RECT_CTX.set(cur.previous);
                            else SUB_RECT_CTX.remove();
                        }
                    }
                }
            });
        }

        if (symbols.has("WatermarkBase.addParam")) {
            XposedBridge.hookMethod(symbols.method("WatermarkBase.addParam"), new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    HoldEntry entry = findRegisteredHoldArg(param.args);
                    if (entry == null) return;
                    SubRectCtx prevCtx = SUB_RECT_CTX.get();
                    param.setObjectExtra(EXTRA_PREV_SUB_CTX, prevCtx);
                    SUB_RECT_CTX.set(new SubRectCtx(prevCtx, entry.adapter));

                    param.setObjectExtra(EXTRA_PREV_OFFSET_FLAG, ENSURE_JSON_OFFSET.get());
                    ENSURE_JSON_OFFSET.set(Boolean.TRUE);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (findRegisteredHoldArg(param.args) == null
                            && param.getObjectExtra(EXTRA_PREV_SUB_CTX) == null
                            && param.getObjectExtra(EXTRA_PREV_OFFSET_FLAG) == null) {
                        return;
                    }
                    SubRectCtx prevCtx = (SubRectCtx) param.getObjectExtra(EXTRA_PREV_SUB_CTX);
                    if (prevCtx != null) SUB_RECT_CTX.set(prevCtx);
                    else SUB_RECT_CTX.remove();

                    Object prevFlag = param.getObjectExtra(EXTRA_PREV_OFFSET_FLAG);
                    if (prevFlag instanceof Boolean) ENSURE_JSON_OFFSET.set((Boolean) prevFlag);
                    else ENSURE_JSON_OFFSET.remove();
                }
            });
        }

        if (symbols.has("WatermarkStyles.load")) {
            XposedBridge.hookMethod(symbols.method("WatermarkStyles.load"), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!Boolean.TRUE.equals(ENSURE_JSON_OFFSET.get())) return;
                    Object res = param.getResult();
                    if (!(res instanceof JSONObject)) return;
                    JSONObject json = (JSONObject) res;
                    if (!json.has("imageOffset")) {
                        param.setResult(ensureImageOffsetJson(json));
                    }
                }
            });
        }
    }

    private static HoldEntry findRegisteredHoldArg(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg == null) continue;
            HoldEntry entry = HOLD_ENTRIES.get(arg);
            if (entry != null) return entry;
        }
        return null;
    }

    private static Field resolveJsonField(Class<?> cls) {
        if (deferParamJsonProbed) return deferParamJsonField;
        synchronized (HOOK_LOCK) {
            if (deferParamJsonProbed) return deferParamJsonField;
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers()) && f.getType() == JSONObject.class) {
                        f.setAccessible(true);
                        deferParamJsonField = f;
                        deferParamJsonProbed = true;
                        return f;
                    }
                }
            }
            deferParamJsonProbed = true;
            return null;
        }
    }

    private static JSONObject ensureImageOffsetJson(JSONObject src) {
        JSONObject copy;
        try {
            copy = src != null ? new JSONObject(src.toString()) : new JSONObject();
            copy.put("baseImageSize", 1080);
            JSONObject offset = new JSONObject();
            offset.put("startX", 0);
            offset.put("startY", 0);
            copy.put("imageOffset", offset);
        } catch (JSONException ignored) {
            copy = new JSONObject();
        }
        return copy;
    }

    static final class HoldFields {
        private final Object lock = new Object();
        private volatile boolean resolved;
        Field makeupWidth;
        Field makeupHeight;
        Field makeupStartX;
        Field makeupStartY;
        Field bitmapWidthArray;
        Field bitmapHeightArray;
        Field borderXArray;
        Field borderYArray;
        Field upperDownA;
        Field upperDownB;
        volatile Field downBitmapList;
        volatile Field upperBitmapList;

        void ensure(Symbols symbols, Object sampleHold) throws Throwable {
            if (resolved) return;
            synchronized (lock) {
                if (resolved) return;
                Object probe = sampleHold.getClass().getDeclaredConstructor().newInstance();

                List<Field> size = symbols.all("WatermarkHold.makeupSize");
                size.get(0).setInt(probe, 7077);
                size.get(1).setInt(probe, 8088);

                List<Field> startXY = symbols.all("WatermarkHold.makeupStartXY");
                startXY.get(0).setInt(probe, 1011);
                startXY.get(1).setInt(probe, 2022);

                List<Field> dimArrays = symbols.all("WatermarkHold.bitmapDimArrays");
                dimArrays.get(0).set(probe, new int[] {3033});
                dimArrays.get(1).set(probe, new int[] {4044});

                List<Field> borderXY = symbols.all("WatermarkHold.borderXYArrays");
                borderXY.get(0).set(probe, new int[] {5055});
                borderXY.get(1).set(probe, new int[] {6066});

                String dump = probe.toString();
                if (dump.contains("mMakeupImageWidth: 7077")) {
                    makeupWidth = size.get(0);
                    makeupHeight = size.get(1);
                } else {
                    makeupWidth = size.get(1);
                    makeupHeight = size.get(0);
                }

                if (dump.contains("mMakeupImageStartX: 1011")) {
                    makeupStartX = startXY.get(0);
                    makeupStartY = startXY.get(1);
                } else {
                    makeupStartX = startXY.get(1);
                    makeupStartY = startXY.get(0);
                }

                if (dump.contains("bitmapWidthArray: [3033]")) {
                    bitmapWidthArray = dimArrays.get(0);
                    bitmapHeightArray = dimArrays.get(1);
                } else {
                    bitmapWidthArray = dimArrays.get(1);
                    bitmapHeightArray = dimArrays.get(0);
                }

                if (dump.contains("borderXArray: [5055]")) {
                    borderXArray = borderXY.get(0);
                    borderYArray = borderXY.get(1);
                } else {
                    borderXArray = borderXY.get(1);
                    borderYArray = borderXY.get(0);
                }

                List<Field> upperDown = symbols.all("WatermarkHold.upperDownBitmapLists");
                upperDownA = upperDown.get(0);
                upperDownB = upperDown.get(1);
                resolved = true;
            }
        }

        void disambiguateUpperDown(Bitmap[] firstRenderArg, Bitmap[] probeA, Bitmap[] probeB) {
            if (downBitmapList != null || firstRenderArg == null) return;
            synchronized (lock) {
                if (downBitmapList != null) return;
                if (firstRenderArg == probeA) {
                    downBitmapList = upperDownA;
                    upperBitmapList = upperDownB;
                } else if (firstRenderArg == probeB) {
                    downBitmapList = upperDownB;
                    upperBitmapList = upperDownA;
                }
            }
        }
    }

    static final class HoldGeometry {
        final int imgW;
        final int imgH;
        final int canvasW;
        final int canvasH;
        final int padLeft;
        final int padTop;
        final int padRight;
        final int padBottom;
        final boolean hasBorder;

        HoldGeometry(
                int imgW,
                int imgH,
                int canvasW,
                int canvasH,
                int padLeft,
                int padTop,
                int padRight,
                int padBottom,
                boolean hasBorder) {
            this.imgW = imgW;
            this.imgH = imgH;
            this.canvasW = canvasW;
            this.canvasH = canvasH;
            this.padLeft = padLeft;
            this.padTop = padTop;
            this.padRight = padRight;
            this.padBottom = padBottom;
            this.hasBorder = hasBorder;
        }
    }

    static int referenceWidth(int canvasW, int canvasH) {
        return Math.round((float) canvasW * canvasW / Math.max(1, Math.min(canvasW, canvasH)));
    }

    /**
     * Synchronizes live-crop mutations made to a {@code WatermarkHold} back to our geometry,
     * aligning start coordinates to even pixel boundaries so native YUV copy does not misalign chroma.
     */
    static HoldGeometry syncHoldGeometry(
            Symbols symbols,
            HoldFields fields,
            Object hold,
            int slot,
            int fallbackImgW,
            int fallbackImgH,
            boolean defaultHasBorder) throws Throwable {
        fields.ensure(symbols, hold);
        int rawCanvasW = fields.makeupWidth.getInt(hold);
        int rawCanvasH = fields.makeupHeight.getInt(hold);
        int canvasW = rawCanvasW > 0 ? rawCanvasW : fallbackImgW;
        int canvasH = rawCanvasH > 0 ? rawCanvasH : fallbackImgH;

        int rawStartX = fields.makeupStartX.getInt(hold);
        int rawStartY = fields.makeupStartY.getInt(hold);
        int startX = Math.max(0, rawStartX & ~1);
        int startY = Math.max(0, rawStartY & ~1);
        if (startX != rawStartX) {
            fields.makeupStartX.setInt(hold, startX);
        }
        if (startY != rawStartY) {
            fields.makeupStartY.setInt(hold, startY);
        }

        Object yuv = symbols.get("WatermarkHold.mYuvInfo", hold);
        Size picSize = yuv != null ? (Size) symbols.get("WatermarkYuv.mPictureSize", yuv) : null;
        int imgW = (picSize != null && picSize.getWidth() > 0) ? picSize.getWidth() : fallbackImgW;
        int imgH = (picSize != null && picSize.getHeight() > 0) ? picSize.getHeight() : fallbackImgH;
        imgW = Math.max(2, Math.min(imgW, canvasW - startX));
        imgH = Math.max(2, Math.min(imgH, canvasH - startY));

        Size curSize = (Size) symbols.get("WatermarkHold.size", hold);
        if (curSize == null || curSize.getWidth() != canvasW || curSize.getHeight() != canvasH) {
            symbols.set("WatermarkHold.size", hold, new Size(canvasW, canvasH));
        }
        int ref = referenceWidth(canvasW, canvasH);
        symbols.set("WatermarkHold.refArray", hold, new int[] {ref, ref, ref, ref, ref, ref});
        int[] widths = new int[6];
        int[] heights = new int[6];
        widths[slot] = canvasW;
        heights[slot] = canvasH;
        fields.bitmapWidthArray.set(hold, widths);
        fields.bitmapHeightArray.set(hold, heights);

        int padRight = Math.max(0, canvasW - startX - imgW);
        int padBottom = Math.max(0, canvasH - startY - imgH);
        boolean hasBorder = defaultHasBorder || startX > 0 || startY > 0 || padRight > 0 || padBottom > 0;
        return new HoldGeometry(imgW, imgH, canvasW, canvasH, startX, startY, padRight, padBottom, hasBorder);
    }

    static Bitmap fitBitmapToCanvas(Bitmap src, int targetW, int targetH, int dx, int dy) {
        if (src == null) return null;
        if (src.getWidth() == targetW && src.getHeight() == targetH && dx == 0 && dy == 0) {
            return src;
        }
        Bitmap adapted = Bitmap.createBitmap(
                Math.max(1, targetW), Math.max(1, targetH), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(adapted);
        canvas.drawBitmap(src, dx, dy, null);
        if (!src.isRecycled()) {
            src.recycle();
        }
        return adapted;
    }

    static final class GainmapOutput {
        final long ptr;
        final int w;
        final int h;

        GainmapOutput(long ptr, int w, int h) {
            this.ptr = ptr;
            this.w = w;
            this.h = h;
        }
    }

    static boolean isYuv420Format(int format) {
        return format == 0x11 || format == 0x23 || format == 0x36 || format == 0x7FA30C0A;
    }

    static GainmapOutput renderCameraGainmapBuffer(
            Class<?> assemblerCls,
            Map<?, ?> map,
            int imgW,
            int imgH,
            int canvasW,
            int canvasH,
            int padLeft,
            int padTop,
            boolean hasBorder) {
        long srcGmPtr = parseBufferPtr(map.get("gain_map"));
        int gmInW = parsePositiveInt(map.get("gain_map_width"));
        int gmInH = parsePositiveInt(map.get("gain_map_height"));
        int gmFormat = parseIntOrDefault(map.get("gain_map_format"), DEFAULT_GAINMAP_FORMAT);
        if (srcGmPtr == 0L || gmInW <= 0 || gmInH <= 0) {
            return null;
        }
        boolean isYuv420 = isYuv420Format(gmFormat);
        boolean bordered = hasBorder || canvasW != imgW || canvasH != imgH || padLeft > 0 || padTop > 0;
        float gmScaleX = (float) gmInW / (float) Math.max(1, imgW);
        float gmScaleY = (float) gmInH / (float) Math.max(1, imgH);
        int gmPadL = bordered ? Math.max(0, Math.round(padLeft * gmScaleX)) : 0;
        int gmPadT = bordered ? Math.max(0, Math.round(padTop * gmScaleY)) : 0;
        if (isYuv420) {
            gmPadL &= ~1;
            gmPadT &= ~1;
        }
        int outGmW = bordered ? Math.max(gmPadL + gmInW, Math.round(canvasW * gmScaleX)) : gmInW;
        int outGmH = bordered ? Math.max(gmPadT + gmInH, Math.round(canvasH * gmScaleY)) : gmInH;
        if (isYuv420) {
            outGmW = alignEven(outGmW);
            outGmH = alignEven(outGmH);
        }

        int allocW = (outGmW + 3) & ~3;
        int allocH = (outGmH + 3) & ~3;
        // For YUV 4:2:0, keep (alloc - out) / 2 even so center-crop never shifts chroma by 1 px.
        if (isYuv420 && (((allocW - outGmW) >> 1) & 1) != 0) {
            outGmW = allocW;
        }
        if (isYuv420 && (((allocH - outGmH) >> 1) & 1) != 0) {
            outGmH = allocH;
        }
        int copyX = ((allocW - outGmW) >> 1) + gmPadL;
        int copyY = ((allocH - outGmH) >> 1) + gmPadT;
        if (isYuv420) {
            copyX &= ~1;
            copyY &= ~1;
        }

        long allocPtr = (Long) XposedHelpers.callStaticMethod(
                assemblerCls, "hardwareBufferAllocate", allocW, allocH, gmFormat);
        if (allocPtr == 0L) {
            return null;
        }
        XposedHelpers.callStaticMethod(assemblerCls, "fillBufferWithColor", allocPtr, "#000000");
        Object rcObj = XposedHelpers.callStaticMethod(
                assemblerCls, "copyBufferToCoord", srcGmPtr, allocPtr, copyX, copyY);
        if (rcObj instanceof Integer && ((Integer) rcObj) != 0) {
            String msg = "copyBufferToCoord gainmap failed rc=" + rcObj
                    + " src=" + gmInW + "x" + gmInH
                    + " dst=" + allocW + "x" + allocH
                    + " pad=" + copyX + "," + copyY;
            Log.e(TAG, msg, new IllegalStateException(msg));
        }

        long finalPtr = allocPtr;
        int finalW = allocW;
        int finalH = allocH;
        if (allocW != outGmW || allocH != outGmH) {
            long croppedPtr = (Long) XposedHelpers.callStaticMethod(
                    assemblerCls, "cropOriginBuffer", allocPtr, outGmW, outGmH);
            if (croppedPtr != 0L) {
                XposedHelpers.callStaticMethod(assemblerCls, "releaseHardwareBuffer", allocPtr);
                finalPtr = croppedPtr;
                finalW = outGmW;
                finalH = outGmH;
            }
        }
        return new GainmapOutput(finalPtr, finalW, finalH);
    }

    static int normalizeRotation(int rotation) {
        int r = ((rotation % 360) + 360) % 360;
        return (r == 90 || r == 180 || r == 270) ? r : 0;
    }

    static int[] mapPadsForRotation(int l, int t, int r, int b, int rotation) {
        switch (normalizeRotation(rotation)) {
            case 90: return new int[] {b, l, t, r};
            case 180: return new int[] {r, b, l, t};
            case 270: return new int[] {t, r, b, l};
            default: return new int[] {l, t, r, b};
        }
    }

    static Bitmap rotateBitmapToSoftware(Bitmap src, int degrees) {
        Bitmap work = src;
        if (src.getConfig() == Bitmap.Config.HARDWARE) {
            Bitmap copy = src.copy(Bitmap.Config.ARGB_8888, false);
            if (copy != null) work = copy;
        }
        int deg = normalizeRotation(degrees);
        if (deg == 0) return work;
        Matrix matrix = new Matrix();
        matrix.postRotate(deg);
        Bitmap rotated = Bitmap.createBitmap(work, 0, 0, work.getWidth(), work.getHeight(), matrix, true);
        if (work != src && work != rotated) work.recycle();
        return rotated;
    }

    static Bitmap writeIntoDstOrKeep(Bitmap dst, Bitmap rendered) {
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
                    Log.e(TAG, "dst ColorSpace incompatible with wide-gamut render; returning rendered", error);
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

    static int alignEven(int v) {
        return Math.max(2, (v + 1) & ~1);
    }

    static String hexRgb(int color) {
        return String.format(Locale.US, "#%06X", color & 0xFFFFFF);
    }

    static long parseBufferPtr(Object value) {
        if (value instanceof Long) return (Long) value;
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try { return Long.parseLong((String) value); } catch (NumberFormatException ignored) { return 0L; }
        }
        return 0L;
    }

    static int parsePositiveInt(Object value) {
        return Math.max(0, parseIntOrDefault(value, 0));
    }

    static int parseIntOrDefault(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try { return Integer.parseInt((String) value); } catch (NumberFormatException ignored) { return fallback; }
        }
        return fallback;
    }
}
