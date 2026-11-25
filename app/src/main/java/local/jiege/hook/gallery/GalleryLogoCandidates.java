package local.jiege.hook.gallery;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.common.SyntheticResources;

/**
 * Gallery photo editor, personalised watermark: the "image" tab of the classic-camera and brand
 * series lists the logos the series offers. The list is read from the series' resource arrays:
 * drawable ids for the logos and string ids for the names under them, in the same order. The tab
 * names a logo with its resource entry name, and the editor puts that name into the style's logo
 * element, which the renderer resolves back to a drawable.
 *
 * Stock logos use the base name (white) on dark backgrounds and {@code <name>_dark} (black) on
 * light backgrounds, plus {@code <name>_60dark} on absorbed backgrounds; {@code WatermarkStyleOperatorImpl}
 * rewrites {@code bitmapResName} by adding or stripping those suffixes when the frame background
 * changes. Registering {@code earth_explorer} (white), {@code earth_explorer_dark} (black), and
 * {@code earth_explorer_60dark} (60% black) and putting each in its matching array keeps the logo
 * aligned with both the picker card and background inversions.
 */
public final class GalleryLogoCandidates {
    private static final String TAG = "GalleryLogoCandidates";
    private static final File LOGO_DIR = new File("/data/user/0/com.coloros.gallery3d/files/earth_explorer");
    private static final File DARK_FILE = new File(LOGO_DIR, "earth_explorer.png");
    private static final File LIGHT_FILE = new File(LOGO_DIR, "earth_explorer_light.png");
    private static final File SIXTY_DARK_FILE = new File(LOGO_DIR, "earth_explorer_60dark.png");
    private static final String LIGHT_NAME = "earth_explorer";
    private static final String DARK_NAME = "earth_explorer_dark";
    private static final String SIXTY_DARK_NAME = "earth_explorer_60dark";
    private static final String LOGO_TITLE = "大地探索家";
    private static final String[] LIGHT_ARRAYS = {
        "picture3d_editor_array_retro_camera_series_logo",
        "picture3d_editor_array_brand_series_logo_first",
        "picture3d_editor_array_brand_series_logo_last",
    };
    private static final String[] DARK_ARRAYS = {
        "picture3d_editor_array_retro_camera_series_logo_dark",
        "picture3d_editor_array_brand_series_logo_dark_first",
        "picture3d_editor_array_brand_series_logo_dark_last",
    };
    private static final String[] TITLE_ARRAYS = {
        "picture3d_editor_array_retro_camera_series_logo_text",
        "picture3d_editor_array_brand_series_logo_text_first",
        "picture3d_editor_array_brand_series_logo_text_last",
    };

    private static Integer lightId;
    private static Integer darkId;
    private static int titleId;

    private GalleryLogoCandidates() {}

    public static void installGallery() {
        Symbols symbols = Symbols.get();
        Log.guard(TAG, "series logo arrays", () -> hookSeriesArrays(symbols));
    }

    private static synchronized void resolveIds() {
        if (lightId != null && darkId != null) return;
        if (!DARK_FILE.isFile()) return;
        ensureVariant(DARK_FILE, LIGHT_FILE, Color.WHITE, 255);
        ensureVariant(DARK_FILE, SIXTY_DARK_FILE, Color.BLACK, 153);
        lightId = SyntheticResources.drawable(LIGHT_NAME, LIGHT_FILE);
        darkId = SyntheticResources.drawable(DARK_NAME, DARK_FILE);
        SyntheticResources.drawable(SIXTY_DARK_NAME, SIXTY_DARK_FILE);
        if (lightId != null && darkId != null) titleId = SyntheticResources.string(LOGO_TITLE);
    }

    private static void ensureVariant(File src, File dst, int color, int alpha) {
        if (dst.isFile()) return;
        Bitmap source = BitmapFactory.decodeFile(src.getAbsolutePath());
        if (source == null) return;
        Bitmap out = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        paint.setAlpha(alpha);
        new Canvas(out).drawBitmap(source, 0f, 0f, paint);
        source.recycle();
        File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
        try (FileOutputStream stream = new FileOutputStream(tmp)) {
            if (out.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                tmp.renameTo(dst);
            }
        } catch (IOException error) {
            Log.e(TAG, "failed to derive " + dst.getName(), error);
        } finally {
            out.recycle();
            tmp.delete();
        }
    }

    private static void hookSeriesArrays(Symbols symbols) {
        Set<String> lightLogos = new HashSet<>(Arrays.asList(LIGHT_ARRAYS));
        Set<String> darkLogos = new HashSet<>(Arrays.asList(DARK_ARRAYS));
        Set<String> titles = new HashSet<>(Arrays.asList(TITLE_ARRAYS));
        XposedBridge.hookMethod(symbols.method("ResourceUtils.drawableArray"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    int[] ids = (int[]) param.getResult();
                    resolveIds();
                    if (ids == null || lightId == null || darkId == null) return;
                    String name = ((Context) param.args[0]).getResources().getResourceEntryName((Integer) param.args[1]);
                    int added = lightLogos.contains(name) ? lightId
                        : darkLogos.contains(name) ? darkId
                        : titles.contains(name) ? titleId : 0;
                    if (added == 0 || contains(ids, added)) return;
                    int[] extended = Arrays.copyOf(ids, ids.length + 1);
                    extended[ids.length] = added;
                    param.setResult(extended);
                } catch (Throwable error) {
                    Log.e(TAG, "series logo array failed", error);
                }
            }
        });
    }

    private static boolean contains(int[] ids, int id) {
        for (int value : ids) if (value == id) return true;
        return false;
    }
}
