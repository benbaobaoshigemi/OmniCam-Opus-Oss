package local.jiege.hook.pop;

import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * POP port: keeps the shutter's drawing geometry centred when the shutter changes size.
 *
 * MainShutterButton creates its ring and disc rectangles once, centred on the view size at that first draw,
 * and onSizeChanged never updates them. Stock X9U modes all use the same shutter size, so this never shows.
 * The ported POP mode uses a larger shutter (288 px vs 264 px); once the shutter has been drawn at one size,
 * the other size draws the inner disc 12 px off centre on both axes (measured +11.5, +11.5) until the camera
 * restarts.
 *
 * On a real size change the rectangles that sit on the old view centre are moved to the new centre, the
 * sweep gradients already set on paints are moved with them, and the gradients the draw builds lazily from
 * those rectangles are dropped, so the next draw matches the new size. The fields are found by type and by
 * where their rectangle sits, not by name; the one gradient the stock code reads without a null check
 * (getOutsideCircleGradient's cache) is the Shutter.outsideGradient symbol and stays.
 */
final class PopShutterGeometry {
    /** Rectangles within this many pixels of the old view centre count as centred (the draw halves an int size). */
    private static final float CENTRE_TOLERANCE = 1f;

    private PopShutterGeometry() {}

    static void install(ClassLoader classLoader) {
        final local.jiege.hook.common.Symbols symbols = local.jiege.hook.common.Symbols.get();
        final Field keep = symbols.field("Shutter.outsideGradient");
        keep.setAccessible(true);
        final Class<?> button = XposedHelpers.findClass("com.oplus.camera.control.MainShutterButton", classLoader);
        final List<Field> rects = new ArrayList<>(), gradients = new ArrayList<>(), paints = new ArrayList<>();
        for (Field field : button.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.equals(keep)) continue;
            Class<?> type = field.getType();
            List<Field> into = type == RectF.class ? rects
                : type == SweepGradient.class || type == LinearGradient.class ? gradients
                : type == Paint.class ? paints : null;
            if (into == null) continue;
            field.setAccessible(true);
            into.add(field);
        }
        if (rects.isEmpty()) throw new IllegalStateException("shutter: no rectangle fields");
        XposedHelpers.findAndHookMethod(button, "onSizeChanged",
            Integer.TYPE, Integer.TYPE, Integer.TYPE, Integer.TYPE, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    int width = (Integer) param.args[0], height = (Integer) param.args[1];
                    int oldWidth = (Integer) param.args[2], oldHeight = (Integer) param.args[3];
                    if (oldWidth == 0 || oldHeight == 0 || (width == oldWidth && height == oldHeight)) return;
                    try {
                        recentre(param.thisObject, rects, gradients, paints,
                            oldWidth / 2f, oldHeight / 2f, (width - oldWidth) / 2f, (height - oldHeight) / 2f);
                    } catch (Throwable error) {
                        local.jiege.hook.common.Log.e("PopPort", "shutter recentre failed", error);
                    }
                }
            });
    }

    private static void recentre(Object view, List<Field> rects, List<Field> gradients, List<Field> paints,
            float oldCentreX, float oldCentreY, float dx, float dy) throws IllegalAccessException {
        for (Field field : rects) {
            RectF rect = (RectF) field.get(view);
            if (rect == null) continue;
            if (Math.abs(rect.centerX() - oldCentreX) <= CENTRE_TOLERANCE && Math.abs(rect.centerY() - oldCentreY) <= CENTRE_TOLERANCE) {
                rect.offset(dx, dy);
            }
        }
        Set<Shader> moved = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Field field : paints) {
            Paint paint = (Paint) field.get(view);
            Shader shader = paint == null ? null : paint.getShader();
            if (shader instanceof SweepGradient && moved.add(shader)) {
                Matrix matrix = new Matrix();
                shader.getLocalMatrix(matrix);
                matrix.postTranslate(dx, dy);
                shader.setLocalMatrix(matrix);
            }
        }
        for (Field field : gradients) field.set(view, null);
        ((android.view.View) view).invalidate();
    }
}
