package local.jiege.hook.aicomposition;

import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import dalvik.system.InMemoryDexClassLoader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The cloud channel of AI 灵感构图: the system AI unit service "cloud_ai_shot_suggestions" (the
 * gallery's own cloud ability) gives the composition suggestion for a viewfinder frame, converted
 * to the JSON the camera expects.
 *
 * The AIUnit SDK in the camera takes its detector and connector as subclasses, so two of them are
 * loaded from assets/aiunit_bridge.dex (tools/aibridge) by an in-memory class loader whose parent
 * is the camera's. The SDK's obfuscated members are found by shape.
 */
final class AiCompositionCloud {
    private static final String TAG = "AiComposition";
    private static final String UNIT = "cloud_ai_shot_suggestions";
    private static final String CONNECTOR_TAG = "CloudShot";
    private static final String BRIDGE_ASSET = "assets/aiunit_bridge.dex";
    /** The cloud's canvas; its centre is in these pixels. */
    private static final double CANVAS_W = 768.0;
    private static final double CANVAS_H = 1024.0;
    /** Requests per round: the engine's two boxes. */
    private static final int REQUESTS = 2;
    /** The engine's dualBoxIouFilter threshold. */
    private static final double OVERLAP = 0.75;
    /** The camera drops crops below this zoom. */
    private static final double MIN_ZOOM = 1.2;

    private static final AtomicBoolean copyFailLogged = new AtomicBoolean(false);

    private static ClassLoader camera;
    private static boolean tried;
    /** One detector and connector per concurrent request. */
    private static Object[] detectors;
    private static Object[] connectors;
    private static Method runAction;
    private static Method createInput;
    private static Method createOutput;
    private static Method setFrame;
    private static Method process;
    private static Class<?> actionInterface;
    private static Field inputJson;
    private static Field outputJson;

    private AiCompositionCloud() {}

    static void setClassLoader(ClassLoader loader) {
        camera = loader;
    }

    /**
     * The cloud's suggestions for the frame as camera JSON; null when none came back. The native engine
     * writes up to two boxes in one run, the cloud answers one per request, so two requests go out at
     * once, each on its own detector and connector (one pair shared by both returns the same answer
     * twice), and merge the way the engine does.
     */
    static String suggest(Context context, HardwareBuffer buffer) {
        if (context == null || buffer == null || buffer.isClosed() || !ensure(context)) return null;
        Bitmap bitmap = toBitmap(buffer);
        if (bitmap == null) return null;
        Bitmap[] frames = new Bitmap[REQUESTS];
        String[] answers = new String[REQUESTS];
        CountDownLatch done = new CountDownLatch(REQUESTS);
        for (int i = 0; i < REQUESTS; i++) {
            frames[i] = i == 0 ? bitmap : bitmap.copy(Bitmap.Config.ARGB_8888, false);
            int index = i;
            Thread worker = new Thread(() -> {
                try {
                    long start = System.currentTimeMillis();
                    answers[index] = frames[index] == null ? null : invoke(index, request(1.0f), frames[index]);
                    Log.i(TAG, "cloud request " + index + " took " + (System.currentTimeMillis() - start) + " ms");
                } catch (Throwable error) {
                    Log.e(TAG, "cloud call failed", error);
                } finally {
                    done.countDown();
                }
            }, "AiCompositionCloud" + i);
            worker.setDaemon(true);
            worker.start();
        }
        boolean finished = false;
        try {
            finished = done.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (finished) {
            for (Bitmap frame : frames) if (frame != null) frame.recycle();
        }
        List<Box> boxes = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            if (answers[i] == null) continue;
            Log.i(TAG, "cloud " + i + ": " + answers[i]);
            Box box = Box.parse(answers[i]);
            if (box != null) boxes.add(box);
        }
        return toCameraJson(merge(boxes), buffer.getWidth(), buffer.getHeight());
    }

    /** The engine's dualBoxIouFilter: two boxes overlapping by IoU >= 0.75 collapse to the one with the larger zoom. */
    private static List<Box> merge(List<Box> boxes) {
        if (boxes.size() < 2) return boxes;
        Box a = boxes.get(0);
        Box b = boxes.get(1);
        double iou = a.iou(b);
        if (iou < OVERLAP) return boxes;
        Log.i(TAG, "boxes overlap iou=" + iou + ", keeping the larger zoom");
        List<Box> kept = new ArrayList<>();
        kept.add(a.zoom < b.zoom ? b : a);
        return kept;
    }

    private static boolean ensure(Context context) {
        synchronized (AiCompositionCloud.class) {
            if (tried) return detectors != null;
            tried = true;
            try {
                byte[] dex = readBridge();
                ClassLoader bridge = new InMemoryDexClassLoader(ByteBuffer.wrap(dex), camera);
                Class<?> detectorClass = Class.forName("local.jiege.aiunit.CloudDetector", true, bridge);
                Class<?> connectorClass = Class.forName("local.jiege.aiunit.CloudConnector", true, bridge);
                Class<?> detectorBase = detectorClass.getSuperclass();
                Class<?> connectorBase = connectorClass.getSuperclass();
                Object[] newDetectors = new Object[REQUESTS];
                Object[] newConnectors = new Object[REQUESTS];
                for (int i = 0; i < REQUESTS; i++) {
                    newDetectors[i] = detectorClass.getConstructor(Context.class, String.class).newInstance(context, UNIT);
                    newConnectors[i] = connectorClass.getConstructor(Context.class, detectorBase, String.class)
                        .newInstance(context, newDetectors[i], CONNECTOR_TAG + i);
                }
                Object detector = newDetectors[0];
                createInput = detectorBase.getMethod("createInputSlot");
                createOutput = detectorBase.getMethod("createOutputSlot");
                for (Class<?> inner : connectorBase.getDeclaredClasses()) {
                    if (inner.isInterface()) {
                        actionInterface = inner;
                        break;
                    }
                }
                if (actionInterface == null) throw new IllegalStateException("connector action interface not found");
                runAction = oneArgument(connectorBase, actionInterface);
                Object input = createInput.invoke(detector);
                Object output = createOutput.invoke(detector);
                setFrame = frameSetter(input.getClass());
                inputJson = stringField(input.getClass());
                outputJson = stringField(output.getClass());
                process = processor(detectorBase, input.getClass());
                if (runAction == null || setFrame == null || inputJson == null || outputJson == null || process == null) {
                    throw new IllegalStateException("AIUnit SDK members not found");
                }
                detectors = newDetectors;
                connectors = newConnectors;
                Log.i(TAG, "cloud channel ready");
            } catch (Throwable error) {
                Log.e(TAG, "cloud channel unavailable", error);
                detectors = null;
            }
            return detectors != null;
        }
    }

    private static String invoke(int channel, String request, Bitmap bitmap) throws Throwable {
        Object detector = detectors[channel];
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "run":
                    try {
                        Object input = createInput.invoke(detector);
                        Object output = createOutput.invoke(detector);
                        Object code = setFrame.invoke(input, bitmap, Boolean.TRUE);
                        if (!(code instanceof Integer) || (Integer) code != 0) return null;
                        inputJson.set(input, request);
                        process.invoke(detector, input, output);
                        return outputJson.get(output);
                    } catch (Throwable error) {
                        return null;
                    }
                case "toString":
                    return CONNECTOR_TAG;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == (args != null && args.length > 0 ? args[0] : null);
                default:
                    return null;
            }
        };
        Object action = Proxy.newProxyInstance(camera, new Class<?>[]{actionInterface}, handler);
        Object out = runAction.invoke(connectors[channel], action);
        return out instanceof String ? (String) out : null;
    }

    /** The connector's method taking the action interface. */
    private static Method oneArgument(Class<?> connectorBase, Class<?> action) {
        for (Method method : connectorBase.getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 1 && types[0] == action && method.getReturnType() == Object.class) return method;
        }
        return null;
    }

    private static Method frameSetter(Class<?> slot) {
        for (Method method : slot.getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 2 && types[0] == Bitmap.class && types[1] == Boolean.class && method.getReturnType() == int.class) {
                return method;
            }
        }
        return null;
    }

    private static Field stringField(Class<?> cls) {
        for (Field field : cls.getFields()) {
            if (field.getType() == String.class) return field;
        }
        for (Field field : cls.getDeclaredFields()) {
            if (field.getType() == String.class) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    private static Method processor(Class<?> detectorBase, Class<?> slot) {
        for (Method method : detectorBase.getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 2 && types[0].isAssignableFrom(slot) && method.getReturnType() != void.class) return method;
        }
        return null;
    }

    /** The composition buffer is not a GPU-samplable texture, so the camera's own copy routine reads it. */
    private static Bitmap toBitmap(HardwareBuffer buffer) {
        try {
            Method copy = Symbols.get().method("HardwareBufferUtils.copyToBitmap");
            Bitmap target = Bitmap.createBitmap(buffer.getWidth(), buffer.getHeight(), Bitmap.Config.ARGB_8888);
            copy.invoke(null, buffer, target);
            return target;
        } catch (Throwable error) {
            if (copyFailLogged.compareAndSet(false, true)) Log.e(TAG, "buffer copy failed", error);
        }
        try {
            Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, null);
            return hardware == null ? null : hardware.copy(Bitmap.Config.ARGB_8888, false);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String request(float zoom) {
        return "{\"aiCapData\":{\"zoom\":" + zoom + ",\"maxZoom\":6.0,\"sensor\":[0,0,0],\"lang\":\""
            + Locale.getDefault().toLanguageTag() + "\"}}";
    }

    /** One suggested framing on the cloud's canvas. */
    private static final class Box {
        final double zoom;
        final double left;
        final double top;
        final double right;
        final double bottom;
        final String reason;

        private Box(double zoom, double cx, double cy, String reason) {
            this.zoom = zoom;
            this.left = Math.max(0, cx - CANVAS_W / (2 * zoom));
            this.top = Math.max(0, cy - CANVAS_H / (2 * zoom));
            this.right = Math.min(CANVAS_W, cx + CANVAS_W / (2 * zoom));
            this.bottom = Math.min(CANVAS_H, cy + CANVAS_H / (2 * zoom));
            this.reason = reason;
        }

        /**
         * The cloud answers {data:{zoom,center[2],reason}} with the centre on a 768x1024 canvas. The camera drops
         * crops below zoom 1.2, so the zoom is held to that.
         */
        static Box parse(String cloud) {
            try {
                JSONObject data = new JSONObject(cloud).optJSONObject("data");
                if (data == null) return null;
                JSONArray center = data.optJSONArray("center");
                if (center == null || center.length() < 2) return null;
                double zoom = Math.max(data.optDouble("zoom", MIN_ZOOM), MIN_ZOOM);
                Box box = new Box(zoom, center.getInt(0), center.getInt(1), data.optString("reason", ""));
                return box.right > box.left && box.bottom > box.top ? box : null;
            } catch (Throwable error) {
                Log.e(TAG, "cloud result unusable", error);
                return null;
            }
        }

        double iou(Box other) {
            double w = Math.min(right, other.right) - Math.max(left, other.left);
            double h = Math.min(bottom, other.bottom) - Math.max(top, other.top);
            if (w <= 0 || h <= 0) return 0;
            double inter = w * h;
            return inter / ((right - left) * (bottom - top) + (other.right - other.left) * (other.bottom - other.top) - inter);
        }
    }

    /** The camera wants {outputNum,crops:[{zoom,srcCrop[4],...}]} in buffer pixels. */
    private static String toCameraJson(List<Box> boxes, int width, int height) {
        if (boxes.isEmpty()) return null;
        try {
            JSONArray crops = new JSONArray();
            for (Box box : boxes) {
                JSONObject description = new JSONObject()
                    .put("title", "推荐构图")
                    .put("desc1", box.reason)
                    .put("desc2", "")
                    .put("desc3", "");
                crops.put(new JSONObject()
                    .put("zoom", box.zoom)
                    .put("srcCrop", new JSONArray()
                        .put(clamp((int) Math.round(box.left * width / CANVAS_W), width))
                        .put(clamp((int) Math.round(box.top * height / CANVAS_H), height))
                        .put(clamp((int) Math.round(box.right * width / CANVAS_W), width))
                        .put(clamp((int) Math.round(box.bottom * height / CANVAS_H), height)))
                    .put("version", "cloud")
                    .put("confidenceLevel", 100)
                    .put("compositionDesc", description));
            }
            return new JSONObject().put("outputNum", crops.length()).put("crops", crops).toString();
        } catch (Throwable error) {
            Log.e(TAG, "camera json failed", error);
            return null;
        }
    }

    private static int clamp(int value, int max) {
        return value < 0 ? 0 : Math.min(value, max);
    }

    /** The bridge dex from the module APK (the camera process has no access to the module's assets). */
    private static byte[] readBridge() throws Exception {
        String apk = Symbols.modulePath();
        if (apk == null) throw new IllegalStateException("module path unknown");
        try (ZipFile zip = new ZipFile(new File(apk))) {
            ZipEntry entry = zip.getEntry(BRIDGE_ASSET);
            if (entry == null) throw new IllegalStateException(BRIDGE_ASSET + " missing in module");
            try (InputStream in = zip.getInputStream(entry)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) > 0) out.write(buffer, 0, count);
                return out.toByteArray();
            }
        }
    }
}
