package local.jiege.hook.common;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.util.TypedValue;
import de.robv.android.xposed.XC_MethodHook;
import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Resources an app does not have: drawables backed by a file and strings backed by text, under ids
 * outside the app's package id. The lookups that take an id (entry name, type name, value, drawable,
 * raw stream, decoded bitmap, string) and the one that takes a name answer for these ids.
 */
public final class SyntheticResources {
    private static final String TAG = "SyntheticResources";
    private static final Map<Integer, Entry> BY_ID = new HashMap<>();
    private static final Map<String, Integer> DRAWABLES = new HashMap<>();
    private static int nextDrawable = 0x7e010001;
    private static int nextString = 0x7e020001;
    private static boolean installed;

    private SyntheticResources() {}

    private static final class Entry {
        final String name;
        final File file;
        final CharSequence text;
        Bitmap bitmap;

        Entry(String name, File file, CharSequence text) {
            this.name = name;
            this.file = file;
            this.text = text;
        }

        Bitmap bitmap() {
            if (bitmap == null && file != null) bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
            return bitmap;
        }
    }

    /** A drawable that shows the image file; null while the file is missing. */
    public static synchronized Integer drawable(String name, File file) {
        install();
        if (!file.isFile()) return null;
        Integer known = DRAWABLES.get(name);
        if (known != null) return known;
        int id = nextDrawable++;
        BY_ID.put(id, new Entry(name, file, null));
        DRAWABLES.put(name, id);
        return id;
    }

    public static synchronized int string(String text) {
        install();
        int id = nextString++;
        BY_ID.put(id, new Entry(null, null, text));
        return id;
    }

    private static synchronized Entry entry(Object id) {
        return id instanceof Integer ? BY_ID.get(id) : null;
    }

    private static void install() {
        if (installed) return;
        installed = true;
        hook("getResourceEntryName", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Entry entry = entry(param.args[0]);
                if (entry != null && entry.name != null) param.setResult(entry.name);
            }
        }, Integer.TYPE);
        hook("getResourceTypeName", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Entry entry = entry(param.args[0]);
                if (entry != null) param.setResult(entry.name != null ? "drawable" : "string");
            }
        }, Integer.TYPE);
        hook("getIdentifier", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!(param.args[0] instanceof String) || (param.args[1] != null && !"drawable".equals(param.args[1]))) return;
                Integer id;
                synchronized (SyntheticResources.class) {
                    id = DRAWABLES.get(param.args[0]);
                }
                if (id != null) param.setResult(id);
            }
        }, String.class, String.class, String.class);
        // AppCompat reads the value of a drawable id before it loads the drawable.
        hook("getValue", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (entry(param.args[0]) == null) return;
                describe((Integer) param.args[0], (TypedValue) param.args[1]);
                param.setResult(null);
            }
        }, Integer.TYPE, TypedValue.class, Boolean.TYPE);
        hook("getValueForDensity", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (entry(param.args[0]) == null) return;
                describe((Integer) param.args[0], (TypedValue) param.args[2]);
                param.setResult(null);
            }
        }, Integer.TYPE, Integer.TYPE, TypedValue.class, Boolean.TYPE);
        XC_MethodHook drawable = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Entry entry = entry(param.args[0]);
                if (entry == null || entry.bitmap() == null) return;
                param.setResult(new BitmapDrawable((Resources) param.thisObject, entry.bitmap()));
            }
        };
        hook("getDrawable", drawable, Integer.TYPE, Resources.Theme.class);
        hook("getDrawableForDensity", drawable, Integer.TYPE, Integer.TYPE, Resources.Theme.class);
        XC_MethodHook rawResource = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Entry entry = entry(param.args[0]);
                if (entry != null && entry.file != null) param.setResult(new FileInputStream(entry.file));
            }
        };
        hook("openRawResource", rawResource, Integer.TYPE);
        hook("openRawResource", rawResource, Integer.TYPE, TypedValue.class);
        XC_MethodHook decode = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Entry entry = entry(param.args[1]);
                if (entry != null && entry.bitmap() != null) param.setResult(entry.bitmap());
            }
        };
        OptionalHook.method(BitmapFactory.class, "decodeResource", decode, Resources.class, Integer.TYPE);
        OptionalHook.method(BitmapFactory.class, "decodeResource", decode, Resources.class, Integer.TYPE, BitmapFactory.Options.class);
        XC_MethodHook text = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Entry entry = entry(param.args[0]);
                if (entry != null && entry.text != null) param.setResult(param.method.getName().equals("getString") ? entry.text.toString() : entry.text);
            }
        };
        hook("getString", text, Integer.TYPE);
        hook("getString", text, Integer.TYPE, Object[].class);
        hook("getText", text, Integer.TYPE);
        hook("getText", text, Integer.TYPE, CharSequence.class);
        Log.i(TAG, "synthetic resource lookups installed");
    }

    private static void hook(String name, XC_MethodHook hook, Class<?>... types) {
        OptionalHook.method(Resources.class, name, hook, types);
    }

    private static void describe(int id, TypedValue out) {
        Entry entry = entry(id);
        out.type = TypedValue.TYPE_STRING;
        out.string = "res/" + (entry != null && entry.name != null ? entry.name : "text") + ".png";
        out.resourceId = id;
        out.data = id;
        out.assetCookie = id;
        out.changingConfigurations = 0;
    }
}
