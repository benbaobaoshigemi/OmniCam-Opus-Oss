package local.jiege.hook.mastercolor;

import android.content.res.Resources;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.DisplayMetrics;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import local.jiege.hook.common.DataStore;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.masterlut.MasterLutSlots;
import local.jiege.settings.ModuleSettings;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Preview-side physical cos^4(theta) optical vignette for the Film Engine in Master mode:
 * - Observes Master mode's native vignette toggle (Lut3dData.vigSwitch) and suppresses the stock
 *   post-LUT vignette whenever the user enables Film Engine physical vignette.
 * - Injects a linear-light optical vignette into the BasicTone preview shaders right before
 *   uBasicToneLut3dTexture lookup, decoding preview codes through the engine's 1D builtin curve
 *   (EngineData::builtin / cap) and encoding back into preview space.
 * - Strictly isolated to Master mode ("professional"); Ricoh GR and other modes stay untouched.
 */
public final class MasterPhysicalVignette {
    private static final String TAG = "MasterPhysVig";
    private static final double HALF_LONG_43 = 17.306646;
    private static final double HALF_SHORT_43 = 12.979985;
    private static final int LUT_W = 256;
    private static final int LUT_H = 128;
    private static final float MAX_VIG_GAIN = 2.6f;
    private static final int VIG_TEX_UNIT = 15;
    private static final long RATIO_POLL_MS = 250;

    private static String glslHelper(boolean es3) {
        String sample = es3 ? "texture" : "texture2D";
        float halfH = 0.5f / LUT_H;
        float spanH = (LUT_H - 1.0f) / LUT_H;
        return
            "uniform int uFilmVigEnable;\n" +
            "uniform vec2 uFilmVigScale;\n" +
            "uniform float uFilmVigGain;\n" +
            "uniform sampler2D uFilmVigTex;\n" +
            "vec4 applyFilmVig(vec4 c, vec2 uv) {\n" +
            "    if (uFilmVigEnable <= 0) return c;\n" +
            "    highp vec2 d = (uv * 2.0 - 1.0) * uFilmVigScale;\n" +
            "    highp float c1 = 1.0 / (1.0 + dot(d, d));\n" +
            "    highp float g = clamp((c1 * c1 * uFilmVigGain) / " + MAX_VIG_GAIN + "f, 0.0, 1.0);\n" +
            "    highp float v = g * " + spanH + " + " + halfH + ";\n" +
            "    highp vec3 u = clamp(c.rgb, 0.0, 1.0) * 0.99609375 + 0.001953125;\n" +
            "    return vec4(\n" +
            "        " + sample + "(uFilmVigTex, vec2(u.r, v)).r,\n" +
            "        " + sample + "(uFilmVigTex, vec2(u.g, v)).r,\n" +
            "        " + sample + "(uFilmVigTex, vec2(u.b, v)).r,\n" +
            "        c.a);\n" +
            "}\n\n";
    }

    private static final class Uniforms {
        final int enable;
        final int scale;
        final int gain;
        final int tex;
        final int origVigSwitch;
        boolean samplerSet;
        int lastEnable = -1;
        float lastSx = -1f;
        float lastSy = -1f;
        float lastGain = -1f;
        int lastOrigVig = -1;

        Uniforms(int program) {
            enable = GLES20.glGetUniformLocation(program, "uFilmVigEnable");
            scale = GLES20.glGetUniformLocation(program, "uFilmVigScale");
            gain = GLES20.glGetUniformLocation(program, "uFilmVigGain");
            tex = GLES20.glGetUniformLocation(program, "uFilmVigTex");
            origVigSwitch = GLES20.glGetUniformLocation(program, "uVigSwitch");
        }
    }

    private static volatile boolean masterMode;
    private static volatile boolean filmPhysicalSelected;
    private static volatile float vigCompFraction;
    private static volatile boolean nativeVigOn;
    private static volatile boolean vigSwitchObserved;
    private static volatile float cachedFocalMm;

    private static volatile ShortBuffer vigLutHalf;
    private static final Map<Long, Integer> vigTexByContext = new ConcurrentHashMap<>();

    private static volatile Object photoRatioKey;
    private static volatile boolean photoRatioLookupFailed;
    private static volatile long ratioCheckedAt;
    private static volatile String lastRatioName;
    private static volatile double cachedAspect = 4.0 / 3.0;
    private static volatile boolean loggedState;

    private static final Map<Long, Map<Integer, Uniforms>> uniformsByContext = new ConcurrentHashMap<>();

    private MasterPhysicalVignette() {}

    private static long currentContextHandle() {
        EGLContext ctx = EGL14.eglGetCurrentContext();
        return (ctx == null || ctx == EGL14.EGL_NO_CONTEXT) ? 0L : ctx.getNativeHandle();
    }

    static void installCamera(ClassLoader classLoader) {
        Log.guard(TAG, "vigSwitch hook", MasterPhysicalVignette::hookVigSwitch);
        Log.guard(TAG, "preview GL hook", () -> hookGlPreview(classLoader));
    }

    public static void setFocalMm(float focalMm) {
        cachedFocalMm = focalMm;
    }

    static void setMasterMode(boolean on) {
        if (masterMode == on) return;
        masterMode = on;
        nativeSetState(on, nativeVigOn);
    }

    static void updateFilmSelection(String lookId, Map<String, Float> params) {
        boolean selected = params != null && params.containsKey("vignette") && params.get("vignette") > 0.5f;
        filmPhysicalSelected = selected;
        float comp = (lookId != null && !"oppo".equals(lookId) && params != null && params.containsKey("vig_comp"))
            ? Math.max(0f, Math.min(1f, params.get("vig_comp")))
            : 0f;
        vigCompFraction = comp;
    }

    static boolean isActive() {
        return masterMode && filmPhysicalSelected && nativeVigOn
            && MasterLutSlots.get(MasterLutSlots.Param.MONOPAN) == 0;
    }

    private static float computeVigGain(double sx, double sy) {
        float frac = vigCompFraction;
        if (!(frac > 0f)) return 1.0f;
        final int nx = 12, ny = 9;
        double sum = 0.0;
        for (int j = 0; j < ny; j++) {
            double y = ((j + 0.5) / ny * 2.0 - 1.0) * sy;
            double y2 = y * y;
            for (int i = 0; i < nx; i++) {
                double x = ((i + 0.5) / nx * 2.0 - 1.0) * sx;
                double c1 = 1.0 / (1.0 + x * x + y2);
                sum += c1 * c1;
            }
        }
        double mean = Math.max(1e-3, sum / (nx * ny));
        double gain = Math.pow(1.0 / mean, frac);
        return (float) Math.max(1.0, Math.min(MAX_VIG_GAIN, gain));
    }

    private static void hookVigSwitch() {
        Symbols symbols = Symbols.get();
        if (!symbols.has("Lut3dData.vigSwitch")) {
            Log.i(TAG, "Lut3dData.vigSwitch symbol missing");
            return;
        }
        XposedBridge.hookMethod(symbols.method("Lut3dData.vigSwitch"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!masterMode) return;
                Object res = param.getResult();
                boolean on = (res instanceof Integer) && ((Integer) res) > 0;
                if (!vigSwitchObserved || nativeVigOn != on) {
                    vigSwitchObserved = true;
                    nativeVigOn = on;
                    nativeSetState(masterMode, on);
                    Log.i(TAG, "master native vigSwitch=" + on);
                }
                if (on && filmPhysicalSelected && MasterLutSlots.get(MasterLutSlots.Param.MONOPAN) == 0) {
                    param.setResult(0);
                }
            }
        });
    }

    private static void hookGlPreview(ClassLoader classLoader) {
        final Symbols symbols = Symbols.get();
        if (!symbols.has("GlShader.readSource") || !symbols.has("GlShader.use") || !symbols.has("GlShader.program")) {
            Log.i(TAG, "GlShader symbols incomplete");
            return;
        }
        XposedBridge.hookMethod(symbols.method("GlShader.readSource"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object res = param.getResult();
                if (!(res instanceof String)) return;
                String src = (String) res;
                if (!src.contains("uVigSwitch")) return;
                String patched = injectShader(src, String.valueOf(param.args[0]));
                if (patched != null) param.setResult(patched);
            }
        });
        XposedBridge.hookMethod(symbols.method("GlShader.use"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Integer boxed;
                try {
                    boxed = (Integer) symbols.get("GlShader.program", param.thisObject);
                } catch (Throwable ignored) {
                    return;
                }
                int program = boxed != null ? boxed : 0;
                long ctx = currentContextHandle();
                if (program <= 0 || ctx == 0L) return;
                Map<Integer, Uniforms> byProg = uniformsByContext.computeIfAbsent(ctx, k -> new ConcurrentHashMap<>());
                Uniforms u = byProg.get(program);
                if (u == null) {
                    u = new Uniforms(program);
                    byProg.put(program, u);
                    if (u.enable >= 0) {
                        Log.i(TAG, "bound preview vignette uniforms ctx=" + Long.toHexString(ctx) + " program=" + program
                            + " enable=" + u.enable + " scale=" + u.scale + " tex=" + u.tex + " vigSwitch=" + u.origVigSwitch);
                    }
                }
                if (u.enable >= 0) applyUniforms(u, ctx, classLoader);
            }
        });
        if (symbols.has("GlShader.setInt")) {
            XposedBridge.hookMethod(symbols.method("GlShader.setInt"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if ("uVigSwitch".equals(param.args[1]) && isActive()) {
                        param.args[0] = 0;
                    }
                }
            });
        }
        if (symbols.has("GlShader.release")) {
            XposedBridge.hookMethod(symbols.method("GlShader.release"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Integer boxed = (Integer) symbols.get("GlShader.program", param.thisObject);
                        long ctx = currentContextHandle();
                        if (boxed != null && boxed > 0 && ctx != 0L) {
                            Map<Integer, Uniforms> byProg = uniformsByContext.get(ctx);
                            if (byProg != null) byProg.remove(boxed);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
        Log.i(TAG, "GlShader hooks installed");
    }

    private static String injectShader(String src, String tag) {
        if (src.contains("uFilmVigEnable")) return null;
        int fnPos = src.indexOf("mediump float getGain(");
        if (fnPos < 0) fnPos = src.indexOf("vec4 apply3DLut(");
        if (fnPos < 0) return null;
        String withHelper = src.substring(0, fnPos) + glslHelper(src.contains("#version 300")) + src.substring(fnPos);
        String targetTex = "apply3DLut(uBasicToneLut3dTexture, uBasicToneLutSize, textureColor, uNeedBasicTone)";
        String targetFrag = "apply3DLut(uBasicToneLut3dTexture, uBasicToneLutSize, FragColor, uNeedBasicTone)";
        String targetI60 = "apply3DLut(textureColor)";
        String targetOrig = "vFragColor = originalPixel;";
        String replaced;
        if (withHelper.contains(targetTex)) {
            replaced = withHelper.replace(targetTex,
                "apply3DLut(uBasicToneLut3dTexture, uBasicToneLutSize, applyFilmVig(textureColor, vTexCoords), uNeedBasicTone)");
        } else if (withHelper.contains(targetFrag)) {
            replaced = withHelper.replace(targetFrag,
                "apply3DLut(uBasicToneLut3dTexture, uBasicToneLutSize, applyFilmVig(FragColor, vTexCoords), uNeedBasicTone)");
        } else if (withHelper.contains(targetI60)) {
            replaced = withHelper.replace(targetI60,
                "apply3DLut(applyFilmVig(textureColor, vTexCoords))");
        } else if (withHelper.contains(targetOrig)) {
            replaced = withHelper.replace(targetOrig,
                "vFragColor = applyFilmVig(originalPixel, vTextureCoord);");
        } else {
            return null;
        }
        Log.i(TAG, "injected GLSL preview vignette resId=" + tag);
        return replaced;
    }

    private static boolean ensureLutLoaded() {
        if (vigLutHalf != null) return true;
        float[] raw = nativeVignetteLut(LUT_W, LUT_H);
        if (raw == null || raw.length != LUT_W * LUT_H) return false;
        ShortBuffer buf = ByteBuffer.allocateDirect(raw.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
        for (float v : raw) buf.put(Float.floatToFloat16(v));
        buf.rewind();
        vigLutHalf = buf;
        Log.i(TAG, "loaded preview vignette 2D LUT " + LUT_W + "x" + LUT_H);
        return true;
    }

    private static int bindVigTexture(long ctx) {
        if (ctx == 0L) return 0;
        Integer existing = vigTexByContext.get(ctx);
        if (existing != null && existing > 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + VIG_TEX_UNIT);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, existing);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            return existing;
        }
        ShortBuffer buf = vigLutHalf;
        if (buf == null) return 0;
        int[] prevActive = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE, prevActive, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + VIG_TEX_UNIT);
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        int texId = ids[0];
        if (texId > 0) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            buf.rewind();
            GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_R16F, LUT_W, LUT_H, 0,
                GLES30.GL_RED, GLES30.GL_HALF_FLOAT, buf);
            vigTexByContext.put(ctx, texId);
        }
        GLES20.glActiveTexture(prevActive[0]);
        return texId;
    }

    private static void applyUniforms(Uniforms u, long ctx, ClassLoader classLoader) {
        if (u.enable < 0) return;
        if (!u.samplerSet && u.tex >= 0) {
            GLES20.glUniform1i(u.tex, VIG_TEX_UNIT);
            u.samplerSet = true;
        }
        final boolean active = isActive();
        final boolean lutReady = active && ensureLutLoaded();
        float focal = cachedFocalMm;
        if (focal <= 0f && lutReady) {
            focal = nativeFocalMm();
            if (focal > 0f) cachedFocalMm = focal;
        }
        int texId = (active && lutReady && focal > 0f) ? bindVigTexture(ctx) : 0;
        if (active && lutReady && focal > 0f && texId > 0) {
            double aspect = currentAspect(classLoader);
            double halfLongMm = aspect >= 4.0 / 3.0 ? HALF_LONG_43 : HALF_SHORT_43 * aspect;
            double halfShortMm = aspect >= 4.0 / 3.0 ? HALF_LONG_43 / aspect : HALF_SHORT_43;
            float sx = (float) (halfLongMm / Math.max(focal, 1.0));
            float sy = (float) (halfShortMm / Math.max(focal, 1.0));
            float vigGain = computeVigGain(sx, sy);
            if (u.lastEnable != 1) {
                GLES20.glUniform1i(u.enable, 1);
                u.lastEnable = 1;
            }
            if (u.scale >= 0 && (Math.abs(sx - u.lastSx) > 1e-4f || Math.abs(sy - u.lastSy) > 1e-4f)) {
                GLES20.glUniform2f(u.scale, sx, sy);
                u.lastSx = sx;
                u.lastSy = sy;
            }
            if (u.gain >= 0 && Math.abs(vigGain - u.lastGain) > 1e-4f) {
                GLES20.glUniform1f(u.gain, vigGain);
                u.lastGain = vigGain;
            }
            if (u.origVigSwitch >= 0 && u.lastOrigVig != 0) {
                GLES20.glUniform1i(u.origVigSwitch, 0);
                u.lastOrigVig = 0;
            }
            if (!loggedState) {
                loggedState = true;
                Log.i(TAG, "preview active focal=" + focal + "mm aspect=" + aspect + " scale=(" + sx + ", " + sy + ")");
            }
        } else {
            if (u.lastEnable != 0) {
                GLES20.glUniform1i(u.enable, 0);
                u.lastEnable = 0;
            }
            if (masterMode && vigSwitchObserved && u.origVigSwitch >= 0) {
                int orig = nativeVigOn ? 1 : 0;
                if (u.lastOrigVig != orig) {
                    GLES20.glUniform1i(u.origVigSwitch, orig);
                    u.lastOrigVig = orig;
                }
            }
            loggedState = false;
        }
    }

    private static double currentAspect(ClassLoader classLoader) {
        long now = System.currentTimeMillis();
        if (now - ratioCheckedAt < RATIO_POLL_MS) return cachedAspect;
        ratioCheckedAt = now;
        try {
            Object key = photoRatioKey;
            if (key == null && !photoRatioLookupFailed) {
                try {
                    key = DataStore.key(Symbols.get(), "Keys.photoRatio", "pref_camera_photo_ratio_key");
                    photoRatioKey = key;
                } catch (Throwable err) {
                    photoRatioLookupFailed = true;
                    Log.e(TAG, "photo ratio key lookup", err);
                }
            }
            if (key != null) {
                Object val = DataStore.get(Symbols.get(), classLoader, key, "standard");
                String ratio = String.valueOf(val);
                if (!ratio.equals(lastRatioName)) {
                    lastRatioName = ratio;
                    cachedAspect = parseAspect(ratio);
                    Log.i(TAG, "photo ratio=" + ratio + " aspect=" + cachedAspect);
                }
            }
        } catch (Throwable err) {
            Log.e(TAG, "read photo ratio", err);
        }
        return cachedAspect;
    }

    private static double parseAspect(String ratio) {
        if (ratio == null) return 4.0 / 3.0;
        switch (ratio) {
            case "16_9":
                return 16.0 / 9.0;
            case "3_2":
                return 3.0 / 2.0;
            case "square":
            case "1_1":
                return 1.0;
            case "full":
                DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
                int hi = Math.max(dm.widthPixels, dm.heightPixels);
                int lo = Math.max(1, Math.min(dm.widthPixels, dm.heightPixels));
                return (double) hi / lo;
            case "standard":
            case "4_3":
            default:
                return 4.0 / 3.0;
        }
    }

    private static native void nativeSetState(boolean masterMode, boolean nativeVig);
    private static native float nativeFocalMm();
    private static native float[] nativeVignetteLut(int w, int h);
}
