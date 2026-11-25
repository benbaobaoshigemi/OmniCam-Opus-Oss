package local.jiege.hook.shutter;

import android.app.Application;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * Counts the shutters of the master (大师), XPAN and GR modes: the total, and today's. The settings app
 * shows the total on the home card and pays out card draws from today's count (Diagnostics carries both,
 * DiagnosticsContract.KEY_SHUTTER_COUNT and KEY_SHUTTER_TODAY).
 *
 * Every capture runs the filter presenter's capture request stage once, with the presenter's mode
 * name ("professional" for master, "gr", "xpan"). The counts are kept in the camera's own files
 * (files/jiege/shutter_count: total, the day, that day's count; one line each), written off the
 * capture thread. A new day starts today's count at zero.
 */
public final class ShutterCount {
    private static final String TAG = "ShutterCount";
    private static final String[] COUNTED = {"professional", "gr", "xpan"};

    private static final Object lock = new Object();
    private static final ExecutorService writer = Executors.newSingleThreadExecutor();
    private static final Set<String> seenModes = new HashSet<>();
    private static volatile boolean installed;
    private static long total = -1;
    /** The day [dayCount] was counted on (ISO date), and its count. */
    private static String day = "";
    private static long dayCount;

    private ShutterCount() {}

    public static void installCamera() {
        Log.guard(TAG, "shutter count", ShutterCount::install);
    }

    private static void install() throws Throwable {
        final Symbols symbols = Symbols.get();
        final Field modeName = symbols.field("FeaturePresenter.modeName");
        modeName.setAccessible(true);
        XposedBridge.hookMethod(symbols.method("FilterPresenter.captureRequest"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.hasThrowable()) return;
                String mode = (String) modeName.get(param.thisObject);
                if (mode == null) return;
                boolean counted = counted(mode);
                boolean first;
                synchronized (seenModes) {
                    first = seenModes.add(mode);
                }
                if (first) Log.i(TAG, "capture request in mode " + mode + (counted ? " (counted)" : ""));
                if (counted) add(mode);
            }
        });
        installed = true;
        Log.i(TAG, "installed");
    }

    private static boolean counted(String mode) {
        for (String name : COUNTED) {
            if (name.equals(mode)) return true;
        }
        return false;
    }

    /** The total, or -1 where nothing is counted (not the camera, or the files are unreadable). */
    public static long total() {
        if (!installed) return -1;
        synchronized (lock) {
            return load();
        }
    }

    /** Today's count, or -1 where nothing is counted. */
    public static long today() {
        if (!installed) return -1;
        synchronized (lock) {
            if (load() < 0) return -1;
            return LocalDate.now().toString().equals(day) ? dayCount : 0;
        }
    }

    private static void add(String mode) {
        final long value;
        final long dayValue;
        final String dayName;
        synchronized (lock) {
            if (load() < 0) return;
            String today = LocalDate.now().toString();
            if (!today.equals(day)) {
                day = today;
                dayCount = 0;
            }
            value = ++total;
            dayValue = ++dayCount;
            dayName = day;
        }
        Log.i(TAG, "shutter " + value + ", today " + dayValue + " (" + mode + ")");
        writer.execute(() -> save(value, dayName, dayValue));
    }

    /** Reads the stored counts once; lock held. */
    private static long load() {
        if (total >= 0) return total;
        File file = file();
        if (file == null) return -1;
        try {
            if (!file.isFile()) {
                total = 0;
            } else {
                String[] lines = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim().split("\\s*\\n\\s*");
                total = Long.parseLong(lines[0].trim());
                if (lines.length >= 3) {
                    day = lines[1].trim();
                    dayCount = Long.parseLong(lines[2].trim());
                }
            }
        } catch (Throwable error) {
            Log.e(TAG, "count unreadable: " + file, error);
        }
        return total;
    }

    private static void save(long value, String dayName, long dayValue) {
        File file = file();
        if (file == null) return;
        try {
            File dir = file.getParentFile();
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("cannot create " + dir);
            File temp = new File(dir, file.getName() + ".tmp");
            Files.write(temp.toPath(), (value + "\n" + dayName + "\n" + dayValue).getBytes(StandardCharsets.UTF_8));
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable error) {
            Log.e(TAG, "count not saved: " + file, error);
        }
    }

    private static File file() {
        try {
            Application app = (Application) XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
            return app == null ? null : new File(app.getFilesDir(), "jiege/shutter_count");
        } catch (Throwable error) {
            return null;
        }
    }
}
