package local.jiege.hook.masterlut;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.filters.FilterScope;

/**
 * 对比度 in the master effect bar, after 影调: the Ricoh GR contrast, applied through the Pre slot
 * (MasterLutSlots). It takes the stock 影调 icon, and 影调 takes the GR mode's 影调 icon.
 *
 * The item is a stock effect item with a key of its own on the -100..100 scale (EffectMenu.bipolar).
 * The value is a module effect value (ModuleEffects): it belongs to the selected preset or filter, and the stock
 * writes, restores and shows it like 影调. The module applies it: on every change, after the stock restore
 * dispatches it on a filter switch, on entering master mode and when the selected preset changes without the bar
 * open. The bar's reset (a dispatch with no key) returns it to the base of the selected preset.
 */
public final class MasterContrast {
    private static final String TAG = "MasterContrast";
    private static final String PACKAGE = "com.oplus.camera";

    private static final ThreadLocal<Object> building = new ThreadLocal<>();
    private static volatile Object itemKey;
    private static volatile Object menu;

    private MasterContrast() {}

    public static void installCamera(ClassLoader classLoader) {
        final Symbols symbols = Symbols.get();
        Log.guard(TAG, "value on entering master", () -> XposedBridge.hookMethod(symbols.method("Lut3d.load"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (FilterScope.MASTER_MODE.equals(param.args[2])) MasterLutSlots.set(MasterLutSlots.Param.CONTRAST, selected(symbols));
            }
        }));
        // A preset or filter chosen while the bar is closed: the stock restore does not run, the selection is written.
        for (String write : new String[] {"DataManager.put", "DataManager.putSync"}) {
            Log.guard(TAG, "preset change " + write, () -> XposedBridge.hookMethod(symbols.method(write), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!ModuleEffects.isPresetKey(param.args[0])) return;
                    MasterLutSlots.set(MasterLutSlots.Param.CONTRAST, selected(symbols));
                }
            }));
        }
        Log.guard(TAG, "menu construction", () -> XposedBridge.hookAllConstructors(symbols.cls("EffectMenu"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object mode = param.args[param.args.length - 1];
                if (!"masterVideo".equals(mode)) building.set(param.thisObject);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (building.get() == param.thisObject) menu = param.thisObject;
                building.remove();
            }
        }));
        // Effect items become views right after the container is built, so the list is arranged here.
        Log.guard(TAG, "item", () -> XposedBridge.hookAllConstructors(symbols.cls("EffectContainer"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object owner = building.get();
                if (owner == null) return;
                Context context = (Context) param.thisObject.getClass().getMethod("getContext").invoke(param.thisObject);
                arrange(symbols, owner, context);
            }
        }));
        Log.guard(TAG, "scale", () -> XposedBridge.hookMethod(symbols.method("EffectMenu.bipolar"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                List<?> items = (List<?>) symbols.get("EffectMenuBase.items", param.thisObject);
                int index = (Integer) param.args[0];
                if (index >= 0 && index < items.size() && isContrast(symbols, items.get(index))) param.setResult(true);
            }
        }));
        // The stock callback stores a value of the item under the selected preset; the module applies it.
        Log.guard(TAG, "value", () -> XposedBridge.hookMethod(symbols.method("EffectMenu.dispatch"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args[1] == null) {
                    if ((Integer) param.args[0] != 0 || itemKey == null) return;
                    String scope = ModuleEffects.scope(symbols);
                    int base = ModuleEffects.base(symbols, ModuleEffects.Value.CONTRAST, scope);
                    ModuleEffects.put(symbols, ModuleEffects.Value.CONTRAST, scope, base);
                    MasterLutSlots.set(MasterLutSlots.Param.CONTRAST, base);
                } else if (itemKey != null && itemKey.equals(param.args[1])) {
                    MasterLutSlots.set(MasterLutSlots.Param.CONTRAST, (Integer) param.args[0]);
                }
            }
        }));
    }

    @SuppressWarnings("unchecked")
    private static void arrange(Symbols symbols, Object owner, Context context) throws Throwable {
        List<Object> items = (List<Object>) symbols.get("EffectMenuBase.items", owner);
        int toneIcon = id(context, "drawable", "master_contrast_icon");
        int grToneIcon = id(context, "drawable", "gr_mode_effect_tone_icon");
        int label = id(context, "string", "camera_master_contrast");
        if (toneIcon == 0 || grToneIcon == 0 || label == 0) throw new IllegalStateException("contrast resources missing");
        Constructor<?> make = symbols.cls("EffectItem").getDeclaredConstructors()[0];
        for (int i = 0; i < items.size(); i++) {
            Object tone = items.get(i);
            if (resourceOf(context, tone, "drawable") != toneIcon) continue;
            items.set(i, make.newInstance(resourceOf(context, tone, "string"), grToneIcon, 0, symbols.get("EffectItem.key", tone)));
            items.add(i + 1, make.newInstance(label, toneIcon, selected(symbols), key(symbols)));
            return;
        }
        Log.i(TAG, "影调 item not found");
    }

    private static boolean isContrast(Symbols symbols, Object item) throws Throwable {
        return item != null && itemKey != null && itemKey.equals(symbols.get("EffectItem.key", item));
    }

    /** The bar and the slot show and use the selected preset's value again (a preset was created while the bar was open). */
    @SuppressWarnings("unchecked")
    static void refresh(Symbols symbols) throws Throwable {
        int value = selected(symbols);
        MasterLutSlots.set(MasterLutSlots.Param.CONTRAST, value);
        Object owner = menu;
        if (owner == null) return;
        List<Object> items = (List<Object>) symbols.get("EffectMenuBase.items", owner);
        for (int i = 0; i < items.size(); i++) {
            if (!isContrast(symbols, items.get(i))) continue;
            symbols.set("EffectItem.value", items.get(i), value);
            symbols.call("EffectMenuBase.refreshItem", owner, i);
        }
    }

    /** The value of the selected preset or filter. */
    private static int selected(Symbols symbols) throws Throwable {
        return ModuleEffects.get(symbols, ModuleEffects.Value.CONTRAST, ModuleEffects.scope(symbols));
    }

    private static Object key(Symbols symbols) throws Throwable {
        Object key = itemKey;
        if (key == null) {
            key = ModuleEffects.itemKey(symbols, ModuleEffects.Value.CONTRAST);
            itemKey = key;
        }
        return key;
    }

    /** The item's resource id of the given type (its label is a string, its icon a drawable), 0 if it has none. */
    private static int resourceOf(Context context, Object item, String type) throws IllegalAccessException {
        for (java.lang.reflect.Field field : item.getClass().getDeclaredFields()) {
            if (field.getType() != int.class || java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            int value = field.getInt(item);
            if (value == 0) continue;
            try {
                if (type.equals(context.getResources().getResourceTypeName(value))) return value;
            } catch (android.content.res.Resources.NotFoundException notAResource) {
                // a plain number, not a resource id
            }
        }
        return 0;
    }

    private static int id(Context context, String type, String name) {
        return context.getResources().getIdentifier(name, type, PACKAGE);
    }
}
