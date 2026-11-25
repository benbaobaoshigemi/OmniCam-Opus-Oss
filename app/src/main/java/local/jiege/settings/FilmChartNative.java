package local.jiege.settings;

import android.content.res.AssetManager;

import java.util.Map;

/**
 * Renders the film colour-chart swatch matrix directly through native {@code fotufilm::Engine}.
 */
public final class FilmChartNative {
    public static final int COLS = 11;
    public static final int ROWS = 9;
    public static final int PATCHES = COLS * ROWS;
    public static final int OUT_FLOATS = PATCHES * 6;

    private static final boolean LOADED;

    static {
        boolean ok = false;
        try {
            System.loadLibrary("jiege_native");
            ok = true;
        } catch (UnsatisfiedLinkError ignored) {
        }
        LOADED = ok;
    }

    private FilmChartNative() {}

    public static boolean isAvailable() {
        return LOADED;
    }

    public static boolean render(AssetManager assetManager, String lookId, Map<String, Float> params, float[] outP3) {
        if (!LOADED || assetManager == null || lookId == null || outP3 == null || outP3.length < OUT_FLOATS) {
            return false;
        }
        int n = params == null ? 0 : params.size();
        String[] keys = new String[n];
        float[] values = new float[n];
        if (params != null && n > 0) {
            int i = 0;
            for (Map.Entry<String, Float> entry : params.entrySet()) {
                keys[i] = entry.getKey();
                Float v = entry.getValue();
                values[i] = v != null ? v : 0f;
                i++;
            }
        }
        return nativeRenderChart(assetManager, lookId, keys, values, outP3);
    }

    public static native boolean nativeRenderChart(
        AssetManager assetManager,
        String lookId,
        String[] paramKeys,
        float[] paramValues,
        float[] outP3
    );
}
