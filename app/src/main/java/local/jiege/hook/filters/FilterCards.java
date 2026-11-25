package local.jiege.hook.filters;

import android.content.res.Resources;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import local.jiege.hook.common.ModeName;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.masterfilter.MasterProPresets;
import local.jiege.settings.ModuleSettings;

/** Adapts native card layouts to filter titles and category labels. */
public final class FilterCards {
    private static final String TAG = "FilterCards";
    private static final String[] TYPES = {X10Filters.QING_TOU, X10Filters.HU_PO};
    private static final ThreadLocal<Boolean> proCardLayout = new ThreadLocal<>();
    private static WeakReference<Object> currentCard = new WeakReference<>(null);
    private FilterCards() {}

    /** Default filter's card in photo ("common"), portrait and (with its palette on) 哈苏超清 mode; it gets the same card as 清透/琥珀. */
    private static final String STANDARD = "default";
    /** The card's label gradient for "default": white to light grey. */
    private static final int[] WHITE_LABEL = {0xFFFFFFFF, 0xFFB9B9B9};

    /**
     * White label card text for a filter of the card's mode, from that mode's filter catalog
     * (normal or master), or null. The card keeps the title it was given.
     */
    private static String catalogLabel(Object model, String type) {
        String mode = ModeName.of(model);
        FilterEntry entry = FilterCore.get(FilterScope.ofMode(mode)).find(type);
        return entry == null ? null : entry.label;
    }

    /** Cards drawn in the X10 style: 清透, 琥珀, 标准 in photo, portrait and 哈苏超清 mode, registered label cards. */
    private static boolean usesLabelCard(Object manager) throws Throwable {
        Object model = Symbols.get().get("Card.mode", manager);
        if (model == null) return false;
        String type = filterType(model);
        return hasProLabel(model) || TYPES[0].equals(type) || TYPES[1].equals(type) || catalogLabel(model, type) != null
                || (STANDARD.equals(type) && isStandardCardMode(model));
    }

    private static boolean hasProLabel(Object model) throws Throwable {
        return "professional".equals(ModeName.of(model)) && Settings.get(ModuleSettings.MASTER_PRO_LABEL)
            && MasterProPresets.hasCurrentRecord();
    }

    private static boolean isStandardCardMode(Object mode) {
        String name = ModeName.of(mode);
        return "common".equals(name) || "portrait".equals(name)
                || ("highPixel".equals(name) && Settings.get(ModuleSettings.HIGH_PIXEL_EFFECTS));
    }

    /** The filter type of a card's mode (ModeUi.filterType, implemented by each mode). */
    private static String filterType(Object model) throws Throwable {
        return (String) Symbols.get().call("ModeUi.filterType", model);
    }

    private static XC_MethodHook.Unhook borrowCardType(Object manager) throws Throwable {
        final Symbols symbols = Symbols.get();
        Object model = symbols.get("Card.mode", manager);
        if (model == null) return null;
        final String type = filterType(model);
        // The card shows the label card only for its own film types; lay the card out as one of them.
        final String nativeType = hasProLabel(model) || TYPES[0].equals(type) || STANDARD.equals(type) || catalogLabel(model, type) != null ? "fuji-proNegHi.bin"
                : TYPES[1].equals(type) ? "ccd_base_warm.bin" : null;
        if (nativeType == null) return null;

        // The implementation the model's class runs (ModeUi.filterType is an interface method).
        final String typeName = symbols.name("ModeUi.filterType");
        Method typeMethod = null;
        for (Class<?> current = model.getClass(); current != null && typeMethod == null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (typeName.equals(method.getName()) && method.getParameterTypes().length == 0
                        && method.getReturnType() == String.class && !java.lang.reflect.Modifier.isAbstract(method.getModifiers())) {
                    typeMethod = method;
                    break;
                }
            }
        }
        if (typeMethod == null) throw new IllegalStateException("Missing " + model.getClass().getName() + "." + typeName + "()");
        final String cardClass = symbols.cls("Card").getName();
        final String filmCheck = symbols.name("Card.isFilmCard");
        final String layout = symbols.name("Card.layout");

        return XposedBridge.hookMethod(typeMethod, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam x) {
                if (!type.equals(x.getResult())) return;
                for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                    if (cardClass.equals(frame.getClassName())
                            && (filmCheck.equals(frame.getMethodName()) || layout.equals(frame.getMethodName()))) {
                        x.setResult(nativeType);
                        return;
                    }
                }
            }
        });
    }

    private static void releaseCardType(XC_MethodHook.MethodHookParam x) {
        XC_MethodHook.Unhook hook = (XC_MethodHook.Unhook) x.getObjectExtra("filter.card.native.type");
        if (hook != null) hook.unhook();
    }

    /**
     * The card's two text views: the title is the one inflated as effect_style_filter_hint, the
     * label the other one (created in code).
     */
    public static TextView[] cardTexts(Object manager) throws Throwable {
        TextView title = null, label = null;
        for (java.lang.reflect.Field field : Symbols.get().<java.lang.reflect.Field>all("Card.texts")) {
            TextView view = (TextView) field.get(manager);
            if (view == null) continue;
            boolean isTitle = false;
            try {
                isTitle = view.getId() > 0 && "effect_style_filter_hint".equals(view.getResources().getResourceEntryName(view.getId()));
            } catch (Resources.NotFoundException notNamed) {
                // a view without a resource name is the label
            }
            if (isTitle) title = view;
            else label = view;
        }
        return new TextView[] {title, label};
    }

    private static void restoreLabels(Object manager) throws Throwable {
        TextView[] texts = cardTexts(manager);
        TextView title = texts[0];
        TextView label = texts[1];
        if (title == null || label == null) return;
        Resources resources = title.getResources();
        Object model = Symbols.get().get("Card.mode", manager);
        String type = filterType(model);
        if (hasProLabel(model)) {
            label.setText("Pro Preset");
            label.setVisibility(View.VISIBLE);
            Object labelContainer = Symbols.get().get("Card.labelBox", manager);
            if (labelContainer instanceof View) ((View) labelContainer).setVisibility(View.VISIBLE);
            fitCardTextWidths(title, label, labelContainer);
            return;
        }
        String extraLabel = catalogLabel(model, type);
        int hintId = resources.getIdentifier("camera_filter_oplus_hint", "string", "com.oplus.camera");
        if (hintId == 0) throw new IllegalStateException("Missing native X10 filter label resources");
        if (!STANDARD.equals(type) && extraLabel == null) {  // 标准 and registered cards keep their title
            int titleId = resources.getIdentifier(TYPES[0].equals(type)
                    ? "camera_filter_oplus_qing_tou" : "camera_filter_oplus_hu_po", "string", "com.oplus.camera");
            if (titleId == 0) throw new IllegalStateException("Missing native X10 filter label resources");
            title.setText(titleId);
        }
        if (extraLabel != null) label.setText(extraLabel);
        else label.setText(hintId);
        // The borrowed film type brought its coloured label; 原生质感 uses the white "default" label.
        whiteLabel(label);
        Object labelContainer = Symbols.get().get("Card.labelBox", manager);
        if (labelContainer instanceof View) whiteLabel((View) labelContainer);

        fitCardTextWidths(title, label, labelContainer);
    }

    /** Expand the existing card to fit the final title and category label. */
    public static void fitCardTextWidths(Object manager) throws Throwable {
        TextView[] texts = cardTexts(manager);
        if (texts[0] == null || texts[1] == null) return;
        fitCardTextWidths(texts[0], texts[1], Symbols.get().get("Card.labelBox", manager));
    }

    public static void refreshCurrent() throws Throwable {
        Object manager = currentCard.get();
        if (manager == null) return;
        TextView title = cardTexts(manager)[0];
        if (title != null) Symbols.get().call("Card.layout", manager, title.getText().toString());
    }

    private static void fitCardTextWidths(TextView title, TextView label, Object labelContainer) {
        int width = Math.max(
                (int) Math.ceil(title.getPaint().measureText(title.getText().toString())) + title.getPaddingLeft() + title.getPaddingRight(),
                (int) Math.ceil(label.getPaint().measureText(label.getText().toString())) + label.getPaddingLeft() + label.getPaddingRight());
        ViewGroup.LayoutParams titleParams = title.getLayoutParams();
        ViewGroup.LayoutParams labelParams = label.getLayoutParams();
        if (titleParams != null && titleParams.width >= 0) width = Math.max(width, titleParams.width);
        if (labelParams != null && labelParams.width >= 0) width = Math.max(width, labelParams.width);
        if (titleParams != null) { titleParams.width = width; title.setLayoutParams(titleParams); }
        if (labelParams != null) { labelParams.width = width; label.setLayoutParams(labelParams); }
        if (labelContainer instanceof View) {
            View view = (View) labelContainer;
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null) { params.width = width; view.setLayoutParams(params); }
        }
    }

    private static void whiteLabel(View view) {
        GradientDrawable white = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, WHITE_LABEL);
        if (view.getBackground() instanceof GradientDrawable) {
            float[] radii = ((GradientDrawable) view.getBackground()).getCornerRadii();
            if (radii != null) white.setCornerRadii(radii);
        }
        view.setBackground(white);
    }

    public static void installCamera(ClassLoader classLoader) {
        ModeName.install(classLoader);
        try {
            Symbols symbols = Symbols.get();
            de.robv.android.xposed.XposedHelpers.findAndHookMethod(
                "com.oplus.camera.common.utils.PresetDataUtil", classLoader, "isCurrentCustomPreset", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam x) {
                        if (Boolean.TRUE.equals(proCardLayout.get())) x.setResult(false);
                    }
                });
            XposedBridge.hookMethod(symbols.method("Card.isFilmCard"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam x) throws Throwable {
                    if (usesLabelCard(x.thisObject)) {
                        x.setObjectExtra("filter.card.native.type", borrowCardType(x.thisObject));
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam x) { releaseCardType(x); }
            });
            XposedBridge.hookMethod(symbols.method("Card.layout"), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam x) throws Throwable {
                    Object model = symbols.get("Card.mode", x.thisObject);
                    if (model != null && hasProLabel(model)) {
                        x.setObjectExtra("filter.card.custom", de.robv.android.xposed.XposedHelpers.callStaticMethod(
                            de.robv.android.xposed.XposedHelpers.findClass("com.oplus.camera.common.utils.PresetDataUtil", classLoader), "isCurrentCustomPreset"));
                        proCardLayout.set(true);
                    }
                    if (usesLabelCard(x.thisObject)) {
                        x.setObjectExtra("filter.card.native.type", borrowCardType(x.thisObject));
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam x) throws Throwable {
                    releaseCardType(x);
                    proCardLayout.remove();
                    currentCard = new WeakReference<>(x.thisObject);
                    Object custom = x.getObjectExtra("filter.card.custom");
                    if (custom != null) symbols.set("Card.custom", x.thisObject, custom);
                    if (usesLabelCard(x.thisObject)) restoreLabels(x.thisObject);
                }
            });
            XposedBridge.log(TAG + ": master card labels installed");
        } catch (Throwable t) { XposedBridge.log(TAG + ": master card labels failed " + t); }
    }

}
