package local.jiege.hook.watermark;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.lang.reflect.Field;
import local.jiege.hook.common.Log;

/**
 * The instant-film frame ("personalize_more_" styles, POP's Polaroid frame) in the watermark
 * renderer, which the gallery and the camera both carry: the paper texture is picked by the shape of
 * the finished picture, and the inner-shadow board is laid over the photo so it looks sunk into the
 * paper. The renderer draws the background, then the photo, then the elements; the board is one more
 * layer after the elements, and the style's own board element is left out.
 *
 * The camera's copy of the renderer is minified, so nothing here reads the renderer's own objects by
 * name. What it needs is taken from the renderer's native entry points, whose names stay: the size of
 * the picture from the buffer allocation, the place of the photo from the copy of the photo into it.
 * The style is recognised by its id among the string fields of the object the renderer is given.
 *
 * The files (background_*.jpg, board_inner.png) are read from the directory the module staged for the
 * process.
 */
public final class FilmFrame {
    public static final String STYLE_PREFIX = "personalize_more_";
    private static final String TAG = "FilmFrame";
    private static final String RENDERER = "com.oplus.ocs.ipu.watermark.masterwatermark.MasterWatermarkAssembler";
    private static final String COMPOSITION = "com.oplus.ocs.ipu.watermark.masterwatermark.CompositionParams";
    // The style's margins in design units (personalize_more_1.json): left / right, top and bottom together.
    private static final double SIDE = 19.7;
    private static final double TOP_AND_BOTTOM = 39.8 + 123.2;

    /** One composition of one picture, on the thread that runs it. */
    private static final class Composition {
        boolean ours;
        int width;
        int height;
        long buffer;
        long photoSource;
        Bitmap bitmap;
        int photoX = -1;
        int photoY = -1;
        boolean photoDrawn;
        boolean boardDrawn;
    }

    private static final ThreadLocal<Composition> CURRENT = new ThreadLocal<>();
    private static File directory;
    private static Bitmap board;

    private FilmFrame() {}

    public static boolean ours(Object styleId) {
        return "personalize_more_1".equals(styleId);
    }

    public static void install(ClassLoader classLoader, String resourceDirectory) {
        directory = new File(resourceDirectory);
        Class<?> renderer = XposedHelpers.findClass(RENDERER, classLoader);
        Class<?> composition = XposedHelpers.findClass(COMPOSITION, classLoader);

        // The start and end of a composition; the first argument is the renderer's description of the style.
        XposedBridge.hookAllMethods(renderer, "assemble", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Composition state = new Composition();
                state.ours = param.args.length > 0 && inStyle(param.args[0]);
                CURRENT.set(state);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                CURRENT.remove();
            }
        });
        XposedBridge.hookAllMethods(renderer, "assembleForGallery", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Composition state = new Composition();
                state.ours = inStyle(param.args[0]);
                if (param.args[2] instanceof Bitmap) {
                    state.bitmap = (Bitmap) param.args[2];
                    state.width = state.bitmap.getWidth();
                    state.height = state.bitmap.getHeight();
                }
                CURRENT.set(state);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                Composition state = CURRENT.get();
                CURRENT.remove();
                if (state == null || !state.ours || state.bitmap == null || !(param.args[1] instanceof Bitmap)) return;
                try {
                    Bitmap photo = (Bitmap) param.args[1];
                    Bitmap shadow = board();
                    if (shadow == null || state.photoX < 0 || !state.bitmap.isMutable()) return;
                    new Canvas(state.bitmap).drawBitmap(shadow, null,
                        new Rect(state.photoX, state.photoY, state.photoX + photo.getWidth(), state.photoY + photo.getHeight()),
                        new Paint(Paint.FILTER_BITMAP_FLAG));
                } catch (Throwable error) {
                    Log.e(TAG, "board over the picture failed", error);
                }
            }
        });

        // The picture's buffer and size, and where the photo is put into it.
        XposedBridge.hookAllMethods(renderer, "hardwareBufferAllocate", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Composition state = CURRENT.get();
                if (state == null || !state.ours) return;
                state.width = (Integer) param.args[0];
                state.height = (Integer) param.args[1];
                state.buffer = (Long) param.getResult();
            }
        });
        XposedBridge.hookAllMethods(renderer, "copyBufferToCoord", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Composition state = CURRENT.get();
                // copyBufferToCoord(source, destination, x, y); the background goes in at the corner.
                if (state == null || !state.ours || state.buffer == 0 || (Long) param.args[1] != state.buffer) return;
                int x = (Integer) param.args[2];
                int y = (Integer) param.args[3];
                if (x == 0 && y == 0) return;
                state.photoX = x;
                state.photoY = y;
                state.photoSource = (Long) param.args[0];
                state.photoDrawn = true;
            }
        });
        XposedBridge.hookAllMethods(renderer, "copyBitmapBufferToCoord", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Composition state = CURRENT.get();
                if (state == null || !state.ours || (param.args[0] != state.bitmap && param.args[1] != state.bitmap)) return;
                int x = (Integer) param.args[2];
                int y = (Integer) param.args[3];
                if (x == 0 && y == 0) return;
                state.photoX = x;
                state.photoY = y;
            }
        });

        // Paper texture by picture shape.
        XposedBridge.hookAllMethods(renderer, "readTextureBackgroundBitmap", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Composition state = CURRENT.get();
                if (state == null || !state.ours) return;
                File file = textureFor(state.width, state.height);
                if (file != null) param.args[0] = file.getAbsolutePath();
            }
        });

        // The style's own board element is left out; the board is laid over the photo after the layers.
        XposedBridge.hookAllMethods(renderer, "drawBitmaps", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Composition state = CURRENT.get();
                if (state == null || !state.ours) return;
                for (int i = 0; i < param.args.length; i++) {
                    if (param.args[i] instanceof Bitmap[]) param.args[i] = new Bitmap[((Bitmap[]) param.args[i]).length];
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                Composition state = CURRENT.get();
                if (state == null || !state.ours || !state.photoDrawn || state.boardDrawn || !(param.args[0] instanceof Long)) return;
                state.boardDrawn = true;
                try {
                    // The board is as large as the photo: read the size from the photo's buffer; the margins
                    // alone give it only to a pixel or two.
                    int photoWidth = state.width - 2 * state.photoX;
                    int photoHeight = (int) Math.round(state.height - TOP_AND_BOTTOM * (state.photoX / SIDE));
                    try {
                        Bitmap probe = (Bitmap) XposedHelpers.callStaticMethod(renderer, "hardwareBufferToBitmap", state.photoSource);
                        if (probe != null) {
                            photoWidth = probe.getWidth();
                            photoHeight = probe.getHeight();
                            probe.recycle();
                        }
                    } catch (Throwable error) {
                        Log.e(TAG, "photo size from the margins", error);
                    }
                    Bitmap shadow = board();
                    if (shadow == null || photoWidth <= 0 || photoHeight <= 0) return;
                    Bitmap scaled = Bitmap.createScaledBitmap(shadow, photoWidth, photoHeight, true);
                    Object params = XposedHelpers.newInstance(composition);
                    XposedHelpers.setIntField(params, "mSrcImageWidth", state.width);
                    XposedHelpers.setIntField(params, "mSrcImageHeight", state.height);
                    XposedHelpers.setIntField(params, "mDisplayMetricsWidth", Resources.getSystem().getDisplayMetrics().widthPixels);
                    XposedHelpers.setIntField(params, "mDisplayMetricsHeight", Resources.getSystem().getDisplayMetrics().heightPixels);
                    XposedHelpers.setIntField(params, "mOrientation", 0);
                    XposedHelpers.setIntField(params, "mBorderX", state.photoX);
                    XposedHelpers.setIntField(params, "mBorderY", state.photoY);
                    XposedHelpers.setIntField(params, "mRefWidth", photoWidth);
                    XposedHelpers.callStaticMethod(renderer, "watermarkCompositeRGBA", (Long) param.args[0], scaled, params);
                    scaled.recycle();
                } catch (Throwable error) {
                    Log.e(TAG, "board over the picture failed", error);
                }
            }
        });
        Log.i(TAG, "instant film frame installed, files " + directory);
    }

    /** The renderer's description of the style carries the style id in one of its string fields. */
    private static boolean inStyle(Object description) {
        if (description == null) return false;
        try {
            for (Class<?> type = description.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (field.getType() != String.class) continue;
                    field.setAccessible(true);
                    if (ours(field.get(description))) return true;
                }
            }
        } catch (Throwable error) {
            Log.e(TAG, "style of the picture unknown", error);
        }
        return false;
    }

    private static synchronized Bitmap board() {
        if (board == null) board = BitmapFactory.decodeFile(new File(directory, "board_inner.png").getAbsolutePath());
        return board;
    }

    /** The paper texture whose shape is closest to the finished picture's. */
    private static File textureFor(int width, int height) {
        File[] files = directory.listFiles((dir, name) -> name.startsWith("background_") && name.endsWith(".jpg") && !name.endsWith("_p3.jpg"));
        if (files == null || height == 0) return null;
        double wanted = Math.log((double) width / height);
        File best = null;
        double bestGap = Double.MAX_VALUE;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        for (File file : files) {
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outHeight == 0) continue;
            double gap = Math.abs(Math.log((double) bounds.outWidth / bounds.outHeight) - wanted);
            if (gap < bestGap) {
                bestGap = gap;
                best = file;
            }
        }
        return best;
    }
}
