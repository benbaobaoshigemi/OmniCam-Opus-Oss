package local.jiege.hook.watermark;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.io.FileInputStream;
import local.jiege.hook.common.DataStore;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;
import org.json.JSONObject;

/** GR style resources, editor presentation and rendering adapters. */
final class GrWatermarkRenderer {
    private static final String TAG = "Watermark";
    private static final String MODE_LIMIT_DIR = "/data/user/0/com.oplus.camera/files/mode_limit_watermark";
    private static final String GALLERY_STYLE_DIR = "/data/user/0/com.coloros.gallery3d/files/ricoh_gr_styles";
    private static final String[] FONT_DIRS = {
        "/data/user/0/com.oplus.camera/files/video_watermark", "/data/user/0/com.oplus.camera/files/jiege/fonts",
    };
    private static final String STYLE_ACTION = "com.oplus.camera.ai.master.watermark.resource";
    private static final String DEFAULT_STYLE = "gr_style_1";
    private static volatile boolean styleReceiverRegistered;

    private GrWatermarkRenderer() {}

    static void install(ClassLoader loader) {
        Symbols symbols = Symbols.get();
        hookEditorLaunch(symbols);
        hookStyleRendering(symbols, loader);
        hookStyleReceiver(symbols, loader);
    }

    static Object loadStyle(Object stock, Symbols symbols, ClassLoader loader) throws Exception {
        String style = styleFor(stock, symbols, loader);
        File json = new File(MODE_LIMIT_DIR + "/" + style + "/" + style + ".json");
        if (!json.isFile()) json = new File(MODE_LIMIT_DIR + "/" + style + ".json");
        if (!json.isFile()) return stock;
        JSONObject parsed = new JSONObject(readUtf8(json));
        localizeFonts(parsed);
        return parsed;
    }

    /** Opening the gallery editor from GR marks the request as a GR watermark edit. */
    private static void hookEditorLaunch(Symbols symbols) {
        try {
            XposedBridge.hookMethod(symbols.method("Util.openEditor"), new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (WatermarkHooks.isGrMode()) param.args[1] = "gr";
                    }
                });
        } catch (Throwable error) {
            Log.e(TAG, "gallery editor mode hook failed", error);
        }
        XposedBridge.hookAllMethods(Activity.class, "startActivityForResult", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length == 0 || !(param.args[0] instanceof Intent) || !WatermarkHooks.isGrMode()) return;
                Intent intent = (Intent) param.args[0];
                if (intent.getComponent() == null
                        || !"com.oplus.gallery.pictureeditorpage.PhotoEditorActivity".equals(intent.getComponent().getClassName())) return;
                intent.putExtra("ai_master_watermark_mode_name", "gr");
                intent.putExtra("is_camera_gr_supported", true);
            }
        });
    }

    private static String styleFor(Object stockResult, Symbols symbols, ClassLoader classLoader) {
        if (stockResult instanceof JSONObject && ((JSONObject) stockResult).optString("styleId").startsWith("gr_")) {
            return ((JSONObject) stockResult).optString("styleId");
        }
        String style = WatermarkHooks.grStyle();
        try {
            Object stored = DataStore.get(symbols, classLoader,
                DataStore.key(symbols, "Keys.styleId", "pref_ai_master_watermark_mode_limit_style_id"), style);
            if (stored instanceof String && ((String) stored).startsWith("gr_")) style = (String) stored;
        } catch (Throwable ignored) {
        }
        return style;
    }

    /**
     * The GR styles name their fonts by URL (fontType 2, e.g. FZLTZCHK.zip for the model name). The
     * camera's MasterWatermarkElementProcessor.setTextPaint only resolves fontType 1 to a file and
     * bt.d.a feeds the string to Typeface.Builder, so a URL font silently falls back to the system
     * font. When the font is on the device (the camera's files/video_watermark, or the gallery's
     * watermark fonts staged in files/jiege/fonts), point the element at that file (fontType 0 = file path).
     */
    private static void localizeFonts(Object node) throws Exception {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            if (object.optInt("fontType", -1) == 2 && object.has("fontName")) {
                File font = localFont(object.optString("fontName"));
                if (font != null) {
                    object.put("font", font.getAbsolutePath());
                    object.put("fontType", 0);
                }
            }
            for (java.util.Iterator<String> keys = object.keys(); keys.hasNext();) localizeFonts(object.opt(keys.next()));
        } else if (node instanceof org.json.JSONArray) {
            org.json.JSONArray array = (org.json.JSONArray) node;
            for (int i = 0; i < array.length(); i++) localizeFonts(array.opt(i));
        }
    }

    private static File localFont(String fontName) {
        String base = fontName.endsWith(".zip") ? fontName.substring(0, fontName.length() - 4) : fontName;
        for (String directory : FONT_DIRS) {
            for (String extension : new String[] {".TTF", ".ttf", ".OTF", ".otf"}) {
                File candidate = new File(directory, base + extension);
                if (candidate.isFile()) return candidate;
            }
        }
        return null;
    }

    private static String readUtf8(File file) throws Exception {
        byte[] bytes = new byte[(int) file.length()];
        int read = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            while (read < bytes.length) {
                int count = input.read(bytes, read, bytes.length - read);
                if (count < 0) break;
                read += count;
            }
        }
        return new String(bytes, 0, read, "UTF-8");
    }

    /** The IPU master-watermark renderer: GR styles live in mode_limit_watermark and draw the GR logo. */
    private static void hookStyleRendering(Symbols symbols, ClassLoader classLoader) {
        try {
            XposedBridge.hookMethod(symbols.method("WatermarkModel.styleNormal"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (WatermarkHooks.isGrMode()) param.setResult(Boolean.TRUE);
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "watermark style check hook failed", error);
        }
        String reader = "com.oplus.ocs.ipu.watermark.masterwatermark.MasterWatermarkJsonReader";
        String processor = "com.oplus.ocs.ipu.watermark.masterwatermark.MasterWatermarkElementProcessor";
        String customParams = "com.oplus.ocs.ipu.watermark.masterwatermark.MasterWatermarkCustomParams";
        try {
            XposedHelpers.findAndHookMethod(reader, classLoader, "getTargetDir", XposedHelpers.findClass(customParams, classLoader),
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Object params = param.args[0];
                        String style = params == null ? null : (String) XposedHelpers.callMethod(params, "getStyleId");
                        if (style != null && style.contains("gr")) param.setResult("/mode_limit_watermark");
                    }
                });
        } catch (Throwable error) {
            Log.e(TAG, "getTargetDir hook failed", error);
        }
        try {
            XposedHelpers.findAndHookMethod(processor, classLoader, "isNotNeedDrawGrElement",
                XposedHelpers.findClass(customParams, classLoader), XC_MethodReplacement.returnConstant(Boolean.FALSE));
        } catch (Throwable error) {
            Log.e(TAG, "isNotNeedDrawGrElement hook failed", error);
        }
        try {
            Class<?> processorClass = XposedHelpers.findClass(processor, classLoader);
            XposedBridge.hookAllMethods(processorClass, "processImage", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length < 4 || !(param.args[3] instanceof ViewGroup) || !(param.args[1] instanceof JSONObject)) return;
                        JSONObject content = ((JSONObject) param.args[1]).optJSONObject("content");
                        String bitmap = content == null ? null : content.optString("bitmap");
                        if (bitmap == null || (!bitmap.startsWith("realme_gr") && !bitmap.startsWith("sketch"))) return;
                        ViewGroup group = (ViewGroup) param.args[3];
                        for (int i = 0; i < group.getChildCount(); i++) {
                            View child = group.getChildAt(i);
                            if (!(child instanceof ImageView) || ((ImageView) child).getDrawable() != null) continue;
                            Bitmap logo = decodeLogo(bitmap);
                            if (logo != null) ((ImageView) child).setImageBitmap(logo);
                        }
                    } catch (Throwable error) {
                        Log.e(TAG, "GR watermark logo failed", error);
                    }
                }
            });
            XposedBridge.hookAllMethods(processorClass, "readImageFromProvider", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 2 || !(param.args[1] instanceof String)) return;
                    String name = (String) param.args[1];
                    if (!name.contains("realme_gr") && !name.contains("sketch")) return;
                    if (name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
                    Bitmap logo = decodeLogo(name);
                    if (logo != null) param.setResult(logo);
                }
            });
        } catch (Throwable error) {
            Log.e(TAG, "GR watermark renderer hooks failed", error);
        }
    }

    private static Bitmap decodeLogo(String name) {
        String file = name.endsWith(".webp") ? name : name + ".webp";
        for (String directory : new String[] {MODE_LIMIT_DIR, MODE_LIMIT_DIR + "/" + DEFAULT_STYLE, GALLERY_STYLE_DIR}) {
            File candidate = new File(directory, file);
            if (candidate.isFile()) return BitmapFactory.decodeFile(candidate.getAbsolutePath());
        }
        return null;
    }

    /** The gallery editor broadcasts the chosen GR style id back to the camera. */
    private static void hookStyleReceiver(final Symbols symbols, final ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Application application = (Application) param.thisObject;
                if (styleReceiverRegistered || !"com.oplus.camera".equals(application.getPackageName())) return;
                application.registerReceiver(new BroadcastReceiver() {
                    @Override public void onReceive(Context context, Intent intent) {
                        String style = intent.getStringExtra("camera_watermark_rm_gr_photo_style_id");
                        if (style == null) style = intent.getStringExtra("styleId");
                        if (style == null || style.isEmpty()) return;
                        try {
                            WatermarkHooks.receiveGrStyle(style);
                        } catch (Throwable error) {
                            Log.e(TAG, "GR watermark style store failed", error);
                        }
                    }
                }, new IntentFilter(STYLE_ACTION), Context.RECEIVER_EXPORTED);
                styleReceiverRegistered = true;
            }
        });
    }
}
