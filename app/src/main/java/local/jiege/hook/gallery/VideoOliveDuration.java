package local.jiege.hook.gallery;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * Video editor (EditorActivity, SaveType.OLIVE): lifts the 3s/6s live-photo export duration cap.
 *
 * The photo viewer's "导出实况" flow (VideoExportOliveFragment) is left untouched: ExportOliveTask
 * marks that flow via isMenuExport = true, so OliveExportLimit.maxMs is overridden only while
 * ExportOliveTask.run executes for the video editor (isMenuExport == false).
 */
public final class VideoOliveDuration {
    private static final String TAG = "VideoOliveDuration";
    private static final String KEY_MAX_DURATION = "video_editor_olive_save_max_duration";
    private static final int MAX_DURATION_SEC = 86400;
    private static final long MAX_DURATION_MS = MAX_DURATION_SEC * 1000L;

    private static final ThreadLocal<Boolean> inVideoEditorExport = new ThreadLocal<>();

    private VideoOliveDuration() {}

    public static void installGallery() {
        Symbols symbols = Symbols.get();
        Log.guard(TAG, "video editor olive max duration config", () -> hookConfigMaxDuration(symbols));
        Log.guard(TAG, "video editor olive task duration limit", () -> hookTaskLimit(symbols));
        Log.guard(TAG, "video editor olive description", () -> hookDescription(symbols));
    }

    private static void hookConfigMaxDuration(Symbols symbols) {
        XposedBridge.hookMethod(symbols.method("Config.getInt"), new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (KEY_MAX_DURATION.equals(param.args[0])) {
                    param.setResult(MAX_DURATION_SEC);
                }
            }
        });
    }

    private static void hookTaskLimit(Symbols symbols) {
        Field isMenuExport = symbols.field("ExportOliveTask.isMenuExport");
        XposedBridge.hookMethod(symbols.method("ExportOliveTask.run"), new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (!isMenuExport.getBoolean(param.thisObject)) {
                    inVideoEditorExport.set(Boolean.TRUE);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                inVideoEditorExport.remove();
            }
        });
        XposedBridge.hookMethod(symbols.method("OliveExportLimit.maxMs"), new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(inVideoEditorExport.get())) {
                    param.setResult(MAX_DURATION_MS);
                }
            }
        });
    }

    private static void hookDescription(Symbols symbols) {
        XposedBridge.hookMethod(symbols.method("TopActionbarSection.oliveDescription"),
            XC_MethodReplacement.returnConstant("支持导出任意时长的实况照片"));
    }
}
