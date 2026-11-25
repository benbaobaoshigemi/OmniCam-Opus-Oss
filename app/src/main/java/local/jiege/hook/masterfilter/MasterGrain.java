package local.jiege.hook.masterfilter;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.mastercolor.MasterColor;
import local.jiege.hook.masterlut.ModuleEffects;
import local.jiege.hook.softlight.SoftLight;
import local.jiege.settings.FilmStyle;
import local.jiege.settings.HncsStyle;
import local.jiege.settings.MasterStyle;
import local.jiege.settings.MasterStyles;
import local.jiege.settings.ModuleSettings;
import local.jiege.settings.X100viStyle;

/**
 * 颗粒 in master mode's effect bar, placed before 暗角: a tap opens two half-width scale bars, 强度
 * and 大小, built like professional mode's 色温 / 色调 pair (WbPanel). Pressing one expands it while
 * the other and its title fade out; collapsing restores both. Another tap on 颗粒, a tap on any other
 * item, or closing the bar hides them. The item shows the current strength.
 *
 * Scale: grain amount and size ×100, 0–100. Defaults are the stock master values: Portra 400, 800T
 * and the three Fuji masters 36 / 70 (800T with 霓虹 36 / 42, MasterNeonGlow), 黑白 HC 40 / 47,
 * 正片 25 / 47, every other filter 0 / 70.
 * Storage: module effect values (ModuleEffects), amount and size of the selected preset or filter, -1 = not set;
 * saved presets keep them with their base. The filter type is the app's (FilterNames.types at
 * CaptureParams.filterIndex), it decides the stock defaults; the capture tag's type carries HDR variants.
 * The bar's reset returns the selected preset's values to its base; the item's own value (EffectItem.value)
 * only says whether they differ from it, so that the reset button lights up through EffectMenu.modified.
 *
 * Capture: after ProfessionalMode's last grain setter (setPositiveFilterGrain), set values replace
 * the stock amount and size. A filter without stock grain gets the colour film set with the amount
 * negative: the native filter sets grain only for filter types it knows, and the module's
 * libAlgoInterface.so takes a negative amount as "grain for this filter too" (tools/algo/grain_gate.S).
 * 标准 sends no filter metadata and its capture list has no filter node: with a strength above 0, the
 * capture metadata gets an empty filter type and the film grain, and aps_algo_filter goes in where the
 * app's own list puts it. The node then runs without a LUT.
 *
 * The 胶片 colour style with grain or halation on: the film's own grain and halation are laid on the node's input frame
 * (film_spatial.cpp), so the Meishe grain is off for every filter and 标准 gets the node with a vanishing amount.
 */
final class MasterGrain {
    private static final String TAG = "MasterGrain";
    private static final String SDK = "com.oplus.ocs.camera.";
    private static final String KEY_NAME = "key_master_mode_effect_muse_grain";
    private static final String AMOUNT_KEY = "amount";
    private static final String SIZE_KEY = "size";
    private static final String PREF_GROUP = "com.oplus.camera_preferences_";
    private static final String PACKAGE = "com.oplus.camera";
    private static final int UNSET = -1;
    private static final int LEVELS = 101;
    private static final long FADE = 150L;
    private static final String MASTER_MODE = "professional_mode";
    private static final String FILTER_NODE = "aps_algo_filter";
    /** Nodes the app's own capture list puts after the filter node (ApsProcessor's list builder). */
    private static final List<String> AFTER_FILTER = Arrays.asList("aps_algo_eye_enhance", "aps_algo_super_text",
        "aps_algo_starburst", "aps_algo_fish_eye", "aps_algo_rotate_mirror", "aps_algo_tilt_shift", "aps_algo_watermark");
    private static final List<String> FILM_MASTERS = Arrays.asList("800t_master.bin", "fuji_cc_master.bin",
        "fuji-nc_master.bin", "fuji-proNegHi_master.bin");
    /** The film grain without amount and size, as ApsProcessor writes the request tag's fields into the capture metadata. */
    private static final String[][] FILM_GRAIN = {
        {"KEY_FILTER_GRAIN_TYPE", "2"},
        {"KEY_FILTER_GRAIN_HIGHTLIGHT", "0.85"},
        {"KEY_FILTER_GRAIN_ROUGHNESS", "0.33"},
        {"KEY_FILTER_GRAIN_COLOR_ENABLE", "1"},
        {"KEY_FILTER_GRAIN_COLOR_RATIO", "0.7"},
        {"KEY_FILTER_GRAIN_MIDTONES", "0.9"},
        {"KEY_FILTER_GRAIN_SHADOWS", "0.89"},
        {"KEY_FILTER_GRAIN_FINALLY_ENABLE", "true"},
    };

    private static volatile Object itemKey;
    private static volatile Object menu;
    private static volatile Object filterNames;
    private static final Map<String, Object> keys = new HashMap<>();
    private static final ThreadLocal<Object> building = new ThreadLocal<>();
    private static boolean resetting;
    private static boolean hidingStock;
    /** The latest touch-down (uptime) and whether it started outside the bars. */
    private static volatile Object row;
    /** The gesture a touch on the blank area collapsed an expanded bar with; the rest of it is not passed on. */
    private static boolean swallowing;
    private static volatile long lastDown;
    private static volatile boolean lastDownOutside;
    private static final long TOUCH_WINDOW = 300L;
    private static Panel panel;
    private static final String SCALE_BAR = "com.oplus.camera.coui.seekbar.HSScaleBar";
    private static final ThreadLocal<Boolean> constructing = new ThreadLocal<>();
    private static volatile Object leftBar;
    private static volatile Object rightBar;

    private MasterGrain() {}

    static void install(ClassLoader classLoader) {
        final Symbols symbols = Symbols.get();
        XposedBridge.hookAllConstructors(symbols.cls("FilterNames"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                filterNames = param.thisObject;
            }
        });
        XposedBridge.hookAllConstructors(symbols.cls("EffectMenu"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object mode = param.args[param.args.length - 1];
                if (!"masterVideo".equals(mode)) building.set(param.thisObject);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (building.get() == param.thisObject) {
                    menu = param.thisObject;
                    panel = null;
                }
                building.remove();
            }
        });
        XposedBridge.hookAllConstructors(symbols.cls("EffectContainer"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object owner = building.get();
                if (owner == null) return;
                row = param.thisObject;
                try {
                    addItem(symbols, owner, (Context) XposedHelpers.callMethod(param.thisObject, "getContext"));
                } catch (Throwable error) {
                    Log.e(TAG, "grain item not added", error);
                }
            }
        });
        // collapsed, the stock layout shifts the row by half of (bar width - row width) to centre it. With 颗粒 the row
        // is longer than the bar, the shift turns negative and pushes the reset item past the start, where scrolling
        // cannot reach. A row longer than the bar is not centred; the backdrop bounds are recomputed for the new position.
        XposedHelpers.findAndHookMethod(symbols.cls("EffectContainer"), "onLayout",
            boolean.class, int.class, int.class, int.class, int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    View row = (View) param.thisObject;
                    // The stock shift is half of (bar width - row width): negative exactly when the row is longer than the bar.
                    if (row.getTranslationX() >= 0) return;
                    row.setTranslationX(0);
                    symbols.call("EffectContainer.updateBounds", row);
                }
            });
        XposedBridge.hookMethod(symbols.method("EffectItemView.onClick"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object item = symbols.get("EffectItemView.item", param.thisObject);
                Object owner = menu;
                if (owner == null) return;
                if (isGrain(symbols, item)) {
                    togglePanel(symbols, owner);
                    param.setResult(null);
                    return;
                }
                hidePanel();
                resetting = item != null && symbols.get("EffectItem.key", item) == null;
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                resetting = false;
            }
        });
        // the stock value callback would store the item's value per preset; the reset clears the filter's values
        XposedBridge.hookMethod(symbols.method("EffectMenu.dispatch"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (itemKey == null || !itemKey.equals(param.args[1])) return;
                param.setResult(null);
                if (!resetting) return;
                try {
                    String scope = ModuleEffects.scope(symbols);
                    ModuleEffects.put(symbols, ModuleEffects.Value.GRAIN_AMOUNT, scope, ModuleEffects.base(symbols, ModuleEffects.Value.GRAIN_AMOUNT, scope));
                    ModuleEffects.put(symbols, ModuleEffects.Value.GRAIN_SIZE, scope, ModuleEffects.base(symbols, ModuleEffects.Value.GRAIN_SIZE, scope));
                } catch (Throwable error) {
                    Log.e(TAG, "grain reset failed", error);
                }
            }
        });
        XposedBridge.hookMethod(symbols.method("EffectMenu.modified"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object item = grainItem(symbols, param.thisObject);
                    if (item == null) return;
                    boolean set = ModuleEffects.modified(symbols, ModuleEffects.scope(symbols), ModuleEffects.Value.GRAIN_AMOUNT, ModuleEffects.Value.GRAIN_SIZE);
                    symbols.set("EffectItem.value", item, set ? 1 : 0);
                } catch (Throwable error) {
                    Log.e(TAG, "grain state not read", error);
                }
            }
        });
        // The stock hides its seek bar from many places (touches on the item row, the shutter, state events) and the call
        // does nothing when that bar is not shown, which it never is while ours is up. Ours closes on what the user does:
        // a touch that starts outside it, which the stock reports through this same method within the same dispatch.
        XposedHelpers.findAndHookMethod(Activity.class, "dispatchTouchEvent", MotionEvent.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                MotionEvent event = (MotionEvent) param.args[0];
                int action = event.getActionMasked();
                if (swallowing) {
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swallowing = false;
                    param.setResult(true);
                    return;
                }
                if (action != MotionEvent.ACTION_DOWN) return;
                lastDownOutside = panel == null || !panel.shown || !panel.contains(event.getRawX(), event.getRawY());
                lastDown = SystemClock.uptimeMillis();
                // As in professional mode: with one bar expanded, a touch on the blank area first returns to the pair.
                if (lastDownOutside && panel != null && panel.collapseExpanded()) {
                    swallowing = true;
                    param.setResult(true);
                }
            }
        });
        XposedBridge.hookMethod(symbols.method("EffectMenu.hideSeekBar"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (hidingStock || panel == null || !panel.shown) return;
                if (lastDownOutside && SystemClock.uptimeMillis() - lastDown < TOUCH_WINDOW) hidePanel();
            }
        });
        XposedBridge.hookMethod(symbols.method("EffectValueView.bind"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Object item = symbols.get("EffectItemView.item", param.thisObject);
                if (!isGrain(symbols, item)) return;
                show(symbols, param.thisObject);
                itemView(symbols, param.thisObject).setVisibility(MonoPan50.owns(currentType(symbols)) ? View.GONE : View.VISIBLE);
                param.setResult(null);
            }
        });
        final Class<?> masterMode = XposedHelpers.findClass(SDK + "producer.mode.ProfessionalMode", classLoader);
        XposedHelpers.findAndHookMethod(SDK + "producer.mode.BaseMode", classLoader, "setPositiveFilterGrain",
            SDK + "common.util.CameraRequestTag", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object tag = param.args[0];
                    if (tag == null || !masterMode.isInstance(param.thisObject)) return;
                    try {
                        applyCapture(symbols, tag);
                    } catch (Throwable error) {
                        Log.e(TAG, "capture grain failed", error);
                    }
                }
            });
        hookHalfBars(classLoader);
        hookStandardCapture(symbols, classLoader);
        Log.i(TAG, "master grain installed");
    }

    /**
     * The professional white balance pair are HSScaleBar subclasses that only start collapsed and
     * move the collapsed scale to the right half; the app build has no constructor for them to call
     * (R8 inlined it), so plain HSScaleBars get the same overrides here: collapsed state for both,
     * right-half geometry for the size bar (the stock WB tone bar's formulas, left-to-right layout).
     */
    private static void hookHalfBars(ClassLoader classLoader) {
        Class<?> bar = XposedHelpers.findClass(SCALE_BAR, classLoader);
        XposedHelpers.findAndHookMethod(bar, "getDefaultUIState", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (constructing.get() != null || param.thisObject == leftBar || param.thisObject == rightBar) param.setResult(0);
            }
        });
        for (final String name : new String[] {"getCollapsedScaleStartX", "getCollapsedScaleEndX",
                "getCollapsedTouchStartX", "getCollapsedTouchEndX"}) {
            XposedHelpers.findAndHookMethod(bar, name, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.thisObject != rightBar) return;
                    View view = (View) param.thisObject;
                    int half = view.getMeasuredWidth() / 2;
                    int margin = (Integer) XposedHelpers.callMethod(view, "getCollapsedContainerMargin");
                    int width = (Integer) XposedHelpers.callMethod(view, "getCollapsedContainerWidth");
                    int start = (Integer) XposedHelpers.callMethod(view, "getCollapsedScaleMarginStart");
                    int scale = (Integer) XposedHelpers.callMethod(view, "getScaleWidth");
                    switch (name) {
                        case "getCollapsedScaleStartX": param.setResult(scale / 2 + start + margin + half); break;
                        case "getCollapsedScaleEndX": param.setResult(width + margin + half - start - scale / 2); break;
                        case "getCollapsedTouchStartX": param.setResult(half); break;
                        default: param.setResult(width + margin * 2 + half); break;
                    }
                }
            });
        }
    }

    /** The item's own view: the child of the effect row that holds the item's name. */
    private static View itemView(Symbols symbols, Object holder) throws Throwable {
        View view = (View) symbols.get("EffectItemView.name", holder);
        Class<?> row = symbols.cls("EffectContainer");
        while (view.getParent() instanceof View && !row.isInstance(view.getParent())) view = (View) view.getParent();
        return view;
    }

    /** The 颗粒 item follows the selected filter: MonoPan50 has the vendor grain of its own and no item. */
    static void refreshItem(Symbols symbols) {
        try {
            Object owner = menu;
            if (owner == null) return;
            // Called on every master LUT load (page refreshes such as the level or the move-away hint reload it too): the
            // bars only close when the selected filter is another one.
            if (panel != null && panel.shown && !panel.type.equals(currentType(symbols))) hidePanel();
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) symbols.get("EffectMenuBase.items", owner);
            int position = items.indexOf(grainItem(symbols, owner));
            if (position >= 0) symbols.call("EffectMenuBase.refreshItem", owner, position);
        } catch (Throwable error) {
            Log.e(TAG, "grain item not refreshed", error);
        }
    }

    /** The 胶片 colour style lays its own grain and halation at the filter node. */
    private static boolean filmSpatial() {
        MasterStyle style = MasterStyles.byIndex(Settings.getInt(ModuleSettings.MASTER_LOOK));
        if (Settings.getInt(ModuleSettings.MASTER_LOOK_MODE) == MasterStyles.MODE_BLEND && !style.blendable()) return false;
        return style instanceof FilmStyle && FilmStyle.spatial(Settings::getInt);
    }

    /** HNCS capture grain in Master mode only (never in GR mode), applied post-LUT at the filter node. */
    private static boolean hncsGrain() {
        if (!MasterColor.inMaster()) return false;
        boolean blend = Settings.getInt(ModuleSettings.MASTER_LOOK_MODE) == MasterStyles.MODE_BLEND;
        MasterStyle style = MasterStyles.byIndex(Settings.getInt(ModuleSettings.MASTER_LOOK));
        if (blend && !style.blendable()) return false;
        int tone = blend ? Settings.getInt(ModuleSettings.MASTER_BLEND_TONE) : 100;
        int colour = blend ? Settings.getInt(ModuleSettings.MASTER_BLEND_COLOUR) : 100;
        return HncsStyle.isGrainActive(style, blend, tone, colour, Settings::getInt);
    }

    /** X100VI capture grain in Master mode only (never in GR mode), applied post-LUT at the filter node. */
    private static boolean x100viGrain() {
        if (!MasterColor.inMaster()) return false;
        boolean blend = Settings.getInt(ModuleSettings.MASTER_LOOK_MODE) == MasterStyles.MODE_BLEND;
        MasterStyle style = MasterStyles.byIndex(Settings.getInt(ModuleSettings.MASTER_LOOK));
        if (blend && !style.blendable()) return false;
        int tone = blend ? Settings.getInt(ModuleSettings.MASTER_BLEND_TONE) : 100;
        int colour = blend ? Settings.getInt(ModuleSettings.MASTER_BLEND_COLOUR) : 100;
        return X100viStyle.isGrainActive(style, blend, tone, colour, Settings::getInt);
    }

    private static void applyCapture(Symbols symbols, Object tag) throws Throwable {
        String type = currentType(symbols);
        if (MonoPan50.owns(type)) return;
        boolean hncs = hncsGrain();
        boolean x100vi = x100viGrain();
        if (filmSpatial() || hncs || x100vi) {
            if (hncs || x100vi) SoftLight.syncHncsGrain(true);
            XposedHelpers.setFloatField(tag, "mbFilterGainAmount", 0.0f);
            Log.i(TAG, "capture " + XposedHelpers.getObjectField(tag, "mFilterType") + ": " + (x100vi ? "x100vi" : (hncs ? "hncs" : "film")) + " grain, Meishe grain off");
            return;
        }
        int amount = stored(symbols, AMOUNT_KEY, type);
        int size = stored(symbols, SIZE_KEY, type);
        Log.i(TAG, "capture " + XposedHelpers.getObjectField(tag, "mFilterType") + " as " + type + " " + amount + "/" + size);
        if (amount == UNSET && size == UNSET) return;
        int[] stock = defaults(type);
        if (amount == UNSET) amount = stock[0];
        if (size == UNSET) size = stock[1];
        if (XposedHelpers.getFloatField(tag, "mbFilterGainAmount") > 0.0f) {
            XposedHelpers.setFloatField(tag, "mbFilterGainAmount", amount / 100.0f);
            XposedHelpers.setFloatField(tag, "mbFilterGainSize", size / 100.0f);
        } else if (amount > 0) {
            applyFilmGrain(tag, amount, size);
        }
    }

    private static void hookStandardCapture(final Symbols symbols, ClassLoader classLoader) {
        final Class<?> keyClass = XposedHelpers.findClass(SDK + "common.util.ParameterKeys", classLoader);
        final Object algorithmKey = XposedHelpers.getStaticObjectField(keyClass, "KEY_CAPTURE_ALGO_LIST");
        final Object filterTypeKey = XposedHelpers.getStaticObjectField(keyClass, "KEY_FILTER_TYPE");
        XposedHelpers.findAndHookMethod(SDK + "consumer.ApsProcessor", classLoader, "createMetaItemInfo",
            SDK + "common.util.CameraRequestTag", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable()) return;
                    try {
                        Object tag = param.args[0];
                        Object request = XposedHelpers.getObjectField(tag, "mApsRequestTag");
                        if (request == null || !MASTER_MODE.equals(XposedHelpers.getObjectField(request, "mModeName"))) return;
                        Object filter = XposedHelpers.getObjectField(tag, "mFilterType");
                        if (XposedHelpers.getBooleanField(tag, "mbFilterOpen") && !"default".equals(filter)) return;
                        String type = currentType(symbols);
                        if (MonoPan50.owns(type)) return;
                        boolean film = filmSpatial();
                        boolean hncs = hncsGrain();
                        boolean x100vi = x100viGrain();
                        int amount = stored(symbols, AMOUNT_KEY, type);
                        if (amount <= 0 && !film && !hncs && !x100vi) return;
                        if (hncs || x100vi) SoftLight.syncHncsGrain(true);
                        int size = stored(symbols, SIZE_KEY, type);
                        if (size == UNSET) size = defaults(type)[1];
                        Object meta = param.getResult();
                        String[] algorithms = (String[]) XposedHelpers.callMethod(meta, "get", algorithmKey);
                        if (algorithms == null) return;
                        List<String> updated = new ArrayList<>(Arrays.asList(algorithms));
                        if (!updated.contains(FILTER_NODE)) {
                            int insertion = updated.size();
                            for (int i = 0; i < updated.size(); i++) {
                                if (AFTER_FILTER.contains(updated.get(i))) { insertion = i; break; }
                            }
                            updated.add(insertion, FILTER_NODE);
                            XposedHelpers.callMethod(meta, "setParameter", algorithmKey, (Object) updated.toArray(new String[0]));
                        }
                        XposedHelpers.callMethod(meta, "setParameter", filterTypeKey, "");
                        for (String[] entry : FILM_GRAIN) {
                            XposedHelpers.callMethod(meta, "setParameter", XposedHelpers.getStaticObjectField(keyClass, entry[0]), entry[1]);
                        }
                        // The film, HNCS or X100VI grain replaces Meishe's: a negative amount still runs the node, too faint to show.
                        String grainAmount = (film || hncs || x100vi) ? "-0.0001" : String.valueOf(-amount / 100.0f);
                        XposedHelpers.callMethod(meta, "setParameter",
                            XposedHelpers.getStaticObjectField(keyClass, "KEY_FILTER_GRAIN_AMOUNT"), grainAmount);
                        XposedHelpers.callMethod(meta, "setParameter",
                            XposedHelpers.getStaticObjectField(keyClass, "KEY_FILTER_GRAIN_SIZE"), String.valueOf(size / 100.0f));
                        Log.i(TAG, "standard capture grain " + amount + "/" + size + " " + updated);
                    } catch (Throwable error) {
                        Log.e(TAG, "standard capture grain failed", error);
                    }
                }
            });
    }

    /** The grain master mode gives its colour film filters (BaseMode.setPortraFilterGrain), amount negative. */
    private static void applyFilmGrain(Object tag, int amount, int size) {
        XposedHelpers.setFloatField(tag, "mbFilterGainAmount", -amount / 100.0f);
        XposedHelpers.setIntField(tag, "mnFilterGrainType", 2);
        XposedHelpers.setIntField(tag, "mFilterColorGrain", 1);
        XposedHelpers.setFloatField(tag, "mbFilterGainColorRatio", 0.7f);
        XposedHelpers.setFloatField(tag, "mbFilterGainHighlight", 0.85f);
        XposedHelpers.setFloatField(tag, "mbFilterGainMidtones", 0.9f);
        XposedHelpers.setFloatField(tag, "mbFilterGainShadows", 0.89f);
        XposedHelpers.setFloatField(tag, "mbFilterGainSize", size / 100.0f);
        XposedHelpers.setFloatField(tag, "mbFilterGainRoughness", 0.33f);
        XposedHelpers.setBooleanField(tag, "mFilterGrainEnable", true);
    }

    /** Stock {strength, size} of a master filter type, ×100. */
    private static int[] defaults(String type) {
        if (type.contains("portra400") || FILM_MASTERS.contains(type)) {
            boolean neon = "800t_master.bin".equals(type) && Settings.get(ModuleSettings.MASTER_EFFECT_NEON);
            return new int[] {36, neon ? 42 : 70};
        }
        if (type.contains("blackandwhite_HC")) return new int[] {40, 47};
        if (type.contains("positive")) return new int[] {25, 47};
        return new int[] {0, 70};
    }

    static String currentType(Symbols symbols) throws Throwable {
        Object names = filterNames;
        if (names == null) return "default";
        Object presenter = symbols.get("FilterNames.presenter", names);
        List<?> types = (List<?>) symbols.call("FilterNames.types", names);
        int index = (Integer) symbols.call("CaptureParams.filterIndex", presenter);
        if (types == null || index < 0 || index >= types.size()) return "default";
        Object type = types.get(index);
        return type == null || "".equals(type) ? "default" : type.toString();
    }

    private static int value(Symbols symbols, String prefix, String type) throws Throwable {
        int stored = stored(symbols, prefix, type);
        return stored != UNSET ? stored : defaults(type)[AMOUNT_KEY.equals(prefix) ? 0 : 1];
    }

    private static ModuleEffects.Value param(String prefix) {
        return AMOUNT_KEY.equals(prefix) ? ModuleEffects.Value.GRAIN_AMOUNT : ModuleEffects.Value.GRAIN_SIZE;
    }

    private static int stored(Symbols symbols, String prefix, String type) throws Throwable {
        return ModuleEffects.get(symbols, param(prefix), ModuleEffects.scope(symbols));
    }

    private static void store(Symbols symbols, String prefix, String type, int value) throws Throwable {
        ModuleEffects.put(symbols, param(prefix), ModuleEffects.scope(symbols), value);
    }

    /** Same factory as the stock effect keys: the store type as group, the per-camera preference file. */
    private static Object key(Symbols symbols, String name) throws Throwable {
        synchronized (keys) {
            Object key = keys.get(name);
            if (key == null) {
                Class<?> dataKey = symbols.method("Prefs.get").getParameterTypes()[0];
                Object group = symbols.call("Prefs.storage", null);
                key = dataKey.getConstructor(String.class, String.class, String.class, Class.class)
                    .newInstance(group, PREF_GROUP, name, Integer.class);
                keys.put(name, key);
            }
            return key;
        }
    }

    private static boolean isGrain(Symbols symbols, Object item) throws Throwable {
        return item != null && itemKey != null && itemKey.equals(symbols.get("EffectItem.key", item));
    }

    @SuppressWarnings("unchecked")
    private static Object grainItem(Symbols symbols, Object owner) throws Throwable {
        for (Object item : (List<Object>) symbols.get("EffectMenuBase.items", owner)) {
            if (isGrain(symbols, item)) return item;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void addItem(Symbols symbols, Object owner, Context context) throws Throwable {
        List<Object> items = (List<Object>) symbols.get("EffectMenuBase.items", owner);
        if (items.size() < 2) return;
        int label = id(context, "string", "camera_gr_particle_amount");
        int icon = id(context, "drawable", "gr_mode_effect_particle_amount_icon");
        if (label == 0 || icon == 0) throw new IllegalStateException("grain resources missing");
        if (itemKey == null) itemKey = key(symbols, KEY_NAME);
        Constructor<?> make = symbols.cls("EffectItem").getDeclaredConstructors()[0];
        Object item = make.newInstance(label, icon, 0, itemKey);
        items.add(items.size() - 1, item);
    }

    private static void show(Symbols symbols, Object view) throws Throwable {
        TextView name = (TextView) symbols.get("EffectItemView.name", view);
        Context context = name.getContext();
        name.setText("颗粒");
        ImageView icon = (ImageView) symbols.get("EffectItemView.icon", view);
        icon.setImageResource(id(context, "drawable", "gr_mode_effect_particle_amount_icon"));
        TextView value = (TextView) symbols.get("EffectValueView.value", view);
        value.setText(String.valueOf(value(symbols, AMOUNT_KEY, currentType(symbols))));
    }

    private static void togglePanel(Symbols symbols, Object owner) throws Throwable {
        if (panel != null && panel.shown) {
            panel.hide();
            return;
        }
        hidingStock = true;
        try {
            symbols.call("EffectMenuBase.hideSeekBar", owner, true);
        } finally {
            hidingStock = false;
        }
        View stock = (View) symbols.get("EffectMenuBase.seekBar", owner);
        ViewGroup parent = (ViewGroup) stock.getParent();
        View softLight = parent.getRootView().findViewById(id(parent.getContext(), "id", "softLightLayout"));
        if (softLight != null && softLight.isShown()) softLight.setVisibility(View.GONE);
        if (panel == null || panel.parent != parent) panel = new Panel(symbols, owner, parent);
        panel.show(stock);
        highlight(symbols, owner, true);
        Log.i(TAG, "panel shown for " + currentType(symbols));
    }

    /** The effect row's highlight on the 颗粒 item while its bars are up; the stock's own selection takes over on the next tap. */
    private static void highlight(Symbols symbols, Object owner, boolean on) {
        try {
            Object container = row;
            if (container == null) return;
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) symbols.get("EffectMenuBase.items", owner);
            symbols.call("EffectContainer.select", container, on ? items.indexOf(grainItem(symbols, owner)) : -1);
        } catch (Throwable error) {
            Log.e(TAG, "grain highlight failed", error);
        }
    }

    private static void hidePanel() {
        if (panel != null && panel.shown) panel.hide();
    }

    private static void changed(Symbols symbols, Object owner, String prefix, int index) {
        try {
            store(symbols, prefix, currentType(symbols), index);
            @SuppressWarnings("unchecked")
            List<Object> items = (List<Object>) symbols.get("EffectMenuBase.items", owner);
            int position = items.indexOf(grainItem(symbols, owner));
            if (position >= 0) symbols.call("EffectMenuBase.refreshItem", owner, position);
            symbols.call("EffectMenuBase.refreshReset", owner, true);
        } catch (Throwable error) {
            Log.e(TAG, "grain value not stored", error);
        }
    }

    private static int id(Context context, String type, String name) {
        return context.getResources().getIdentifier(name, type, PACKAGE);
    }

    /** The 强度 / 大小 pair: left and right half bars, each with a title above it. */
    private static final class Panel {
        final Symbols symbols;
        final Object owner;
        final ViewGroup parent;
        final View strength;
        final View size;
        final TextView strengthTitle;
        final TextView sizeTitle;
        final int halfCenter;
        final int expandedLeft;
        final int expandedTy;
        boolean shown;
        /** The filter type the bars were opened for. */
        String type = "";
        /** Listener methods the bar calls for its UI states 1 (expand) and 3 (collapse). */
        String expandCallback;
        String collapseCallback;

        Panel(Symbols symbols, Object owner, ViewGroup parent) throws Throwable {
            this.symbols = symbols;
            this.owner = owner;
            this.parent = parent;
            Context context = parent.getContext();
            halfCenter = dimen(context, "hs_professional_mode_scale_bar_collapsed_margin")
                + dimen(context, "hs_professional_mode_scale_bar_collapsed_width") / 2;
            expandedLeft = dimen(context, "hs_professional_mode_scale_bar_expanded_margin_left");
            expandedTy = dimen(context, "hs_professional_mode_scale_bar_color_tone_title_expanded_ty");
            strength = bar(context);
            size = bar(context);
            probeUiStates(context);
            leftBar = strength;
            rightBar = size;
            strengthTitle = title(context, "强度");
            sizeTitle = title(context, "大小");
            listen(strength, AMOUNT_KEY, strengthTitle, size, sizeTitle, -1);
            listen(size, SIZE_KEY, sizeTitle, strength, strengthTitle, 1);
            for (View view : new View[] {strength, size, strengthTitle, sizeTitle}) {
                view.setVisibility(View.GONE);
                parent.addView(view);
            }
        }

        /**
         * Which listener methods setUiState(1) (expand) and setUiState(3) (collapse) call. The names change with every camera
         * build, so a throwaway bar with a recording listener is put through both states.
         */
        private void probeUiStates(Context context) {
            try {
                final String[] called = {null};
                Class<?> listener = symbols.cls("ScaleBarListener");
                Object recorder = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[] {listener}, new InvocationHandler() {
                    @Override public Object invoke(Object self, Method method, Object[] args) {
                        if (method.getDeclaringClass() == Object.class) return method.getName().equals("hashCode") ? 0 : method.getName().equals("equals") ? Boolean.FALSE : "probe";
                        if (method.getParameterTypes().length == 0) called[0] = method.getName();
                        return defaultValue(method.getReturnType());
                    }
                });
                View probe = bar(context);
                symbols.call("ScaleBar.setListener", probe, recorder);
                called[0] = null;
                XposedHelpers.callMethod(probe, "setUiState", 1);
                expandCallback = called[0];
                called[0] = null;
                XposedHelpers.callMethod(probe, "setUiState", 3);
                collapseCallback = called[0];
                Log.i(TAG, "bar ui states: expand=" + expandCallback + " collapse=" + collapseCallback);
            } catch (Throwable error) {
                Log.e(TAG, "bar ui state probe failed", error);
            }
        }

        private View bar(Context context) throws Throwable {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < LEVELS; i++) names.add(String.valueOf(i));
            Constructor<?> make = XposedHelpers.findClass(SCALE_BAR, context.getClassLoader()).getDeclaredConstructor(
                Context.class, int.class, Handler.class, List.class, String[].class, HashMap.class);
            constructing.set(true);
            View bar;
            try {
                bar = (View) make.newInstance(context, LEVELS, new Handler(Looper.getMainLooper()), names, null, null);
            } finally {
                constructing.remove();
            }
            XposedHelpers.callMethod(bar, "setCountBetweenBigScale", 10);
            XposedHelpers.callMethod(bar, "setScaleInterval", 2);
            return bar;
        }

        /** As the professional white balance titles (ScaleBarTitle in wb_scale_bar_title_container). */
        private TextView title(Context context, String text) {
            TextView title = new TextView(context);
            title.setText(text);
            title.setTextColor(Color.WHITE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(context, "hs_professional_mode_scale_bar_title_text_size"));
            title.setShadowLayer(2, 0, 0, 0x4d000000);
            title.setMaxLines(1);
            title.setGravity(Gravity.CENTER);
            return title;
        }

        /** side: -1 left half, 1 right half (mirrored for right-to-left layouts). */
        private void listen(final View bar, final String prefix, final TextView title, final View other,
                            final TextView otherTitle, final int side) throws Throwable {
            final int[] last = {-1};
            Class<?> listener = symbols.cls("ScaleBarListener");
            // The listener's methods are told apart by signature (the value change is the one that takes an int)
            // and, for the two UI states the bar reports, by probing (see probeUiStates).
            // Every other method answers with the default of its return type (a null for a primitive crashes the caller).
            Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[] {listener}, new InvocationHandler() {
                @Override public Object invoke(Object self, Method method, Object[] args) {
                    Class<?>[] types = method.getParameterTypes();
                    if (types.length == 1 && types[0] == int.class) {
                        tick(last, (Integer) args[0]);
                        changed(symbols, owner, prefix, (Integer) args[0]);
                    } else if (types.length == 0 && method.getName().equals(expandCallback)) {
                        expandedBar = bar;
                        expand(title, other, otherTitle);
                    } else if (types.length == 0 && method.getName().equals(collapseCallback)) {
                        expandedBar = null;
                        collapse(title, other, otherTitle, side);
                    } else if (method.getDeclaringClass() == Object.class) {
                        switch (method.getName()) {
                            case "equals": return self == args[0];
                            case "hashCode": return System.identityHashCode(self);
                            default: return TAG + " bar";
                        }
                    }
                    return defaultValue(method.getReturnType());
                }
            });
            symbols.call("ScaleBar.setListener", bar, proxy);
        }

        private static Object defaultValue(Class<?> type) {
            if (type == boolean.class) return Boolean.FALSE;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0f;
            if (type == double.class) return 0d;
            if (type == short.class) return (short) 0;
            if (type == byte.class) return (byte) 0;
            if (type == char.class) return (char) 0;
            return null;
        }

        /**
         * The master bar's haptics (its scale bar listener): a tick on every even value, and one when a
         * fast drag skips past an even value; strong where the device supports it.
         */
        private void tick(int[] last, int index) {
            int previous = last[0];
            last[0] = index;
            if (!shown || previous == -1 || previous == index) return;
            int low = Math.min(previous, index);
            if (index % 2 != 0 && low + (2 - low % 2) >= Math.max(previous, index)) return;
            try {
                Class<?> config = XposedHelpers.findClass("com.oplus.camera.configure.CameraConfig", parent.getContext().getClassLoader());
                boolean strong = (Boolean) XposedHelpers.callStaticMethod(config, "getConfigBooleanValue", "com.oplus.strong.vibrate.support");
                symbols.call("Vibrate.play", symbols.get("Vibrate.instance", null), strong ? 1 : 0, 0);
            } catch (Throwable error) {
                Log.e(TAG, "tick failed", error);
            }
        }

        void show(View stock) throws Throwable {
            String type = currentType(symbols);
            this.type = type;
            int[] stockValues = defaults(type);
            place(strength, stock, AMOUNT_KEY, type, stockValues[0]);
            place(size, stock, SIZE_KEY, type, stockValues[1]);
            placeTitle(strengthTitle, stock, -1);
            placeTitle(sizeTitle, stock, 1);
            for (View view : new View[] {strength, size, strengthTitle, sizeTitle}) fade(view, true);
            shown = true;
            filterUi("FilterSource.hideFilterUi");
        }

        /** Whether a screen point lies in the bars' box. */
        boolean contains(float x, float y) {
            int[] at = new int[2];
            strength.getLocationOnScreen(at);
            return x >= at[0] && x <= at[0] + strength.getWidth() && y >= at[1] && y <= at[1] + strength.getHeight();
        }

        /** The bar that is expanded (being adjusted), or null while the pair is at rest. */
        View expandedBar;

        /** Brings the expanded bar back to the pair; false when none is expanded. */
        boolean collapseExpanded() {
            View bar = expandedBar;
            if (!shown || bar == null) return false;
            try {
                symbols.call("ScaleBar.collapse", bar, true);
            } catch (Throwable error) {
                Log.e(TAG, "bar collapse failed", error);
                return false;
            }
            return true;
        }

        void hide() {
            expandedBar = null;
            highlight(symbols, owner, false);
            for (View view : new View[] {strength, size, strengthTitle, sizeTitle}) fade(view, false);
            shown = false;
            filterUi("FilterSource.showFilterUi");
        }

        /** What the stock bar does on show / hide: the filter name card and the filter dots go away / come back. */
        private void filterUi(String method) {
            try {
                symbols.call(method, symbols.get("EffectMenuBase.host", owner));
            } catch (Throwable error) {
                Log.e(TAG, method + " failed", error);
            }
        }

        private void place(View bar, View stock, String prefix, String type, int stockValue) throws Throwable {
            bar.setLayoutParams(new RelativeLayout.LayoutParams((RelativeLayout.LayoutParams) stock.getLayoutParams()));
            XposedHelpers.callMethod(bar, "setShowDots", new ArrayList<>(Collections.singletonList(stockValue)));
            int index = value(symbols, prefix, type);
            symbols.call("ScaleBar.select", bar, index, false);
            symbols.call("ScaleBar.scrollTo", bar, index);
        }

        /**
         * The title shares the bar's box, its text centred over the bar's half. As in the white balance panel,
         * whose box is its scale bar's, the text is centred in a title band below the box's top.
         */
        private void placeTitle(TextView title, View stock, int side) {
            RelativeLayout.LayoutParams box = new RelativeLayout.LayoutParams((RelativeLayout.LayoutParams) stock.getLayoutParams());
            Context context = title.getContext();
            int top = dimen(context, "hs_professional_mode_scale_bar_title_margin_top");
            int band = dimen(context, "hs_professional_mode_scale_bar_title_height");
            title.setPadding(0, top, 0, Math.max(0, box.height - top - band));
            title.setLayoutParams(box);
            title.setTranslationX(collapsedX(side));
            title.setTranslationY(0);
        }

        private float collapsedX(int side) {
            boolean rtl = parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            return (rtl ? -side : side) * halfCenter;
        }

        private void expand(TextView title, View other, TextView otherTitle) {
            other.animate().alpha(0).setStartDelay(0).setDuration(FADE).start();
            otherTitle.animate().alpha(0).setStartDelay(0).setDuration(FADE).start();
            boolean rtl = parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            float edge = title.getWidth() / 2.0f - expandedLeft - title.getPaint().measureText(title.getText().toString()) / 2.0f;
            title.animate().translationX(rtl ? edge : -edge).translationY(expandedTy).setStartDelay(0).setDuration(320L).start();
        }

        private void collapse(TextView title, View other, TextView otherTitle, int side) {
            if (!shown) return;
            other.animate().alpha(1).setDuration(FADE).setStartDelay(120L).start();
            otherTitle.animate().alpha(1).setDuration(FADE).setStartDelay(120L).start();
            title.animate().translationX(collapsedX(side)).translationY(0).setDuration(320L).start();
        }

        private static void fade(final View view, final boolean in) {
            view.animate().cancel();
            if (in) {
                view.setAlpha(0);
                view.setVisibility(View.VISIBLE);
                view.animate().alpha(1).setStartDelay(0).setDuration(FADE).withEndAction(null).start();
            } else if (view.getVisibility() == View.VISIBLE) {
                view.animate().alpha(0).setStartDelay(0).setDuration(FADE).withEndAction(new Runnable() {
                    @Override public void run() { view.setVisibility(View.GONE); }
                }).start();
            }
        }

        private static int dimen(Context context, String name) {
            int id = id(context, "dimen", name);
            return id == 0 ? 0 : context.getResources().getDimensionPixelSize(id);
        }
    }
}
