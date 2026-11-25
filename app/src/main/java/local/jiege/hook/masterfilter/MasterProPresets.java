package local.jiege.hook.masterfilter;

import android.app.Activity;
import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.PopupWindow;
import android.widget.Toast;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import local.jiege.hook.common.DataStore;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.ProParamStore;
import local.jiege.hook.common.Settings;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.filters.FilterCards;
import local.jiege.hook.filters.ProParamsRecord;
import local.jiege.hook.masterlut.ModuleEffects;
import local.jiege.settings.ModuleSettings;

/** Pro settings attached to master filters and presets. */
public final class MasterProPresets {
    private static final String TAG = "MasterPro";
    private static final String PREFIX = "pref_jiege_pro_";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadLocal<MenuState> menuState = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> saving = new ThreadLocal<>();
    private static final ThreadLocal<String> createdKey = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> switchingInEffectPanel = new ThreadLocal<>();
    private static ClassLoader loader;
    private static Symbols symbols;
    private static volatile Object currentPresenter;
    private static View.OnClickListener gear;
    private static Dialog professionalDialog;
    private static String lastSelection;
    private static long selectionGeneration;

    private MasterProPresets() {}

    static void install(ClassLoader classLoader) throws Throwable {
        loader = classLoader;
        symbols = Symbols.get();
        Class<?> presenterClass = symbols.cls("CaptureParams");

        XposedBridge.hookAllConstructors(presenterClass, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                currentPresenter = p.thisObject;
            }
        });

        XposedBridge.hookMethod(symbols.method("CaptureParams.beforePreview"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                currentPresenter = p.thisObject;
                Object view = symbols.get("CaptureParams.view", p.thisObject);
                if (view != null) {
                    symbols.set("CaptureView.resetOnPreview", view, false);
                }
                boolean pro = (Boolean) p.args[0];
                if (!pro) {
                    if ("professional".equals(symbols.get("FeaturePresenter.modeName", p.thisObject)) && hasCurrentRecord()) {
                        p.args[0] = true;
                    }
                }
            }
        });

        XposedBridge.hookMethod(symbols.method("CaptureView.animateParameters"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (Boolean.TRUE.equals(switchingInEffectPanel.get())) p.setResult(null);
            }
        });

        Class<?> gearClass = symbols.cls("CaptureParams.gearListener");
        XposedBridge.hookAllConstructors(gearClass, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (presenterOf(p.thisObject, presenterClass) != null) gear = (View.OnClickListener) p.thisObject;
            }
        });

        installMenu();

        for (String method : new String[] {"EffectData.findDuplicate", "EffectData.isDuplicate"}) {
            XposedBridge.hookMethod(symbols.method(method), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (Boolean.TRUE.equals(saving.get())) p.setResult("EffectData.findDuplicate".equals(method) ? -1 : false);
                }
            });
        }
        XposedBridge.hookMethod(symbols.method("EffectData.add"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (Boolean.TRUE.equals(saving.get()) && p.getResult() != null
                    && (Integer) symbols.get("PresetSaveResult.status", p.getResult()) == 0) {
                    createdKey.set((String) symbols.get("PresetRecord.key", p.args[0]));
                }
            }
        });
        XposedBridge.hookMethod(symbols.method("EffectData.savePreset"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                if (professionalDialog == null || !professionalDialog.isShowing()) return;
                saving.set(true);
                p.setObjectExtra("proValues", snapshot());
            }
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (p.getObjectExtra("proValues") == null) return;
                saving.remove();
                professionalDialog = null;
                Object result = p.getResult();
                if (result != null && (Integer) symbols.get("PresetSaveResult.status", result) == 0) {
                    String key = createdKey.get();
                    @SuppressWarnings("unchecked") Map<String, String> values = (Map<String, String>) p.getObjectExtra("proValues");
                    save(key, values);
                    lastSelection = key;
                    Log.i(TAG, "saved professional preset " + key);
                }
                createdKey.remove();
            }
        });
        XposedBridge.hookMethod(symbols.method("EffectData.delete"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                DataStore.put(symbols, loader, record((String) p.args[0]), "");
            }
        });
        XposedBridge.hookMethod(symbols.method("DataManager.put"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                if (!(p.args[1] instanceof String) || !ModuleEffects.isPresetKey(p.args[0])) return;
                String key = (String) p.args[1];
                if (key.equals(lastSelection)) return;
                lastSelection = key;
                long generation = ++selectionGeneration;
                MAIN.post(() -> {
                    try {
                        if (generation == selectionGeneration && key.equals(ModuleEffects.scope(symbols))) apply(key);
                    } catch (Throwable e) { Log.e(TAG, "cannot restore professional parameters", e); }
                });
            }
        });
    }

    /** The rows this feature adds to the preset menu. */
    private enum Action {
        REMEMBER("记忆专业参数"), SAVE_AS("另存专业预设"), CLEAR("清除专业参数");

        final String title;

        Action(String title) { this.title = title; }
    }

    private static final class MenuState {
        final Object owner;
        final boolean pro;
        int first;
        Action[] actions;

        MenuState(Object owner, boolean pro) { this.owner = owner; this.pro = pro; }
    }

    private static void installMenu() throws Throwable {
        if (symbols.has("PresetUi.showMenu", "PresetPopup.items", "PresetPopup.listener")) {
            installListPopupMenu();
        } else if (symbols.has("PresetUi.showMenu", "CameraPopup.show", "CameraPopup.refresh", "CameraPopup.dismiss")) {
            installCameraPopupMenu();
        } else {
            Log.i(TAG, "professional preset menu not offered: the preset popup was not found in this camera build");
        }
    }

    /** Camera builds that open the preset menu in the camera's own CameraPopupListWindow. */
    private static void installCameraPopupMenu() throws Throwable {
        final Class<?> presetUi = symbols.cls("PresetUi");
        final Class<?> popupClass = symbols.cls("CameraPopup");
        XposedBridge.hookMethod(symbols.method("PresetUi.showMenu"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                Object owner = holding(p.thisObject, presetUi);
                if (owner == null) return;
                boolean pro = inPro();
                if (pro || hasCurrentRecord()) menuState.set(new MenuState(owner, pro));
            }
            @Override protected void afterHookedMethod(MethodHookParam p) { menuState.remove(); }
        });
        XposedBridge.hookMethod(symbols.method("CameraPopup.show"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                MenuState state = menuState.get();
                if (state == null || fieldOfType(state.owner, popupClass) != p.thisObject) return;
                addCameraPopupItems(state, p.thisObject);
            }
        });
    }

    /** The object itself, or the object held by one of its fields, that is an instance of the type; else null. */
    private static Object holding(Object object, Class<?> type) throws IllegalAccessException {
        if (type.isInstance(object)) return object;
        return object == null ? null : fieldOfType(object, type);
    }

    private static Object fieldOfType(Object object, Class<?> type) throws IllegalAccessException {
        for (Field field : object.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
            field.setAccessible(true);
            Object value = field.get(object);
            if (type.isInstance(value)) return value;
        }
        return null;
    }

    private static Field declaredField(Class<?> owner, java.util.function.Predicate<Class<?>> type) {
        for (Field field : owner.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || !type.test(field.getType())) continue;
            field.setAccessible(true);
            return field;
        }
        throw new IllegalStateException("no matching field in " + owner.getName());
    }

    @SuppressWarnings("unchecked")
    private static void addCameraPopupItems(final MenuState state, final Object popup) throws Throwable {
        ClassLoader camera = popup.getClass().getClassLoader();
        Class<?> adapterType = Class.forName("androidx.recyclerview.widget.RecyclerView$Adapter", false, camera);
        Object adapter = declaredField(popup.getClass(), adapterType::isAssignableFrom).get(popup);
        List<Object> items = (List<Object>) declaredField(adapter.getClass(), List.class::isAssignableFrom).get(adapter);
        appendMenuItems(state, items);
        adapterType.getMethod("notifyDataSetChanged").invoke(adapter);
        symbols.call("CameraPopup.refresh", popup);

        // The click callback is the popup's one single-method interface that takes the row index first (not the
        // Runnable it also holds). A click on an added row
        // dismisses the popup first, then runs the action.
        final Field callbackField = declaredField(popup.getClass(), type -> type.isInterface() && type.getMethods().length == 1
            && type.getMethods()[0].getParameterTypes().length > 0 && type.getMethods()[0].getParameterTypes()[0] == int.class);
        final Field afterDismiss = declaredField(popup.getClass(), Runnable.class::equals);
        final Object original = callbackField.get(popup);
        callbackField.set(popup, java.lang.reflect.Proxy.newProxyInstance(camera, new Class<?>[] {callbackField.getType()},
            (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    switch (method.getName()) {
                        case "equals": return proxy == args[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        default: return "professional preset menu";
                    }
                }
                boolean ours = args != null && args.length > 0 && args[0] instanceof Integer
                    && (Integer) args[0] >= state.first && (Integer) args[0] < state.first + state.actions.length;
                if (!ours) return original == null ? null : method.invoke(original, args);
                final Action action = state.actions[(Integer) args[0] - state.first];
                final Runnable previous = (Runnable) afterDismiss.get(popup);
                afterDismiss.set(popup, (Runnable) () -> {
                    if (previous != null) previous.run();
                    try {
                        afterDismiss.set(popup, previous);
                        runMenuAction(state.owner, action);
                    } catch (Throwable error) {
                        Log.e(TAG, "professional preset action failed", error);
                    }
                });
                symbols.call("CameraPopup.dismiss", popup);
                return null;
            }));
    }

    /** Fills state.actions for the current record and appends one row per action, cloned from the first row. */
    private static void appendMenuItems(MenuState state, List<Object> items) throws Throwable {
        String stored = (String) DataStore.get(symbols, loader, record(ModuleEffects.scope(symbols)), "");
        if (!state.pro) state.actions = new Action[] {Action.CLEAR};
        else if (stored.isEmpty()) state.actions = new Action[] {Action.REMEMBER, Action.SAVE_AS};
        else state.actions = Action.values();
        state.first = items.size();
        Object prototype = items.get(0);
        Field title = null;
        for (Field field : prototype.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
            field.setAccessible(true);
            Object value = field.get(prototype);
            if (value instanceof String && !((String) value).isEmpty()) { title = field; break; }
        }
        if (title == null) throw new IllegalStateException("preset menu title unavailable");
        for (Action action : state.actions) {
            Object item = prototype.getClass().getDeclaredConstructor().newInstance();
            for (Field field : prototype.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                field.set(item, field.get(prototype));
            }
            title.set(item, action.title);
            items.add(item);
        }
    }

    /** Camera builds that open the preset menu in the COUI list popup. */
    private static void installListPopupMenu() {
        XposedBridge.hookMethod(symbols.method("PresetUi.showMenu"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                boolean pro = inPro();
                if (pro || hasCurrentRecord()) menuState.set(new MenuState(p.thisObject, pro));
            }
            @Override protected void afterHookedMethod(MethodHookParam p) { menuState.remove(); }
        });
        XposedBridge.hookMethod(symbols.method("PresetPopup.listener"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                MenuState state = menuState.get();
                if (state == null) return;
                AdapterView.OnItemClickListener original = (AdapterView.OnItemClickListener) p.args[0];
                PopupWindow popup = (PopupWindow) p.thisObject;
                p.args[0] = (AdapterView.OnItemClickListener) (parent, view, position, id) -> {
                    if (state.actions == null || position < state.first || position >= state.first + state.actions.length) {
                        original.onItemClick(parent, view, position, id);
                        return;
                    }
                    popup.dismiss();
                    Action action = state.actions[position - state.first];
                    MAIN.post(() -> {
                        try { runMenuAction(state.owner, action); }
                        catch (Throwable error) { Log.e(TAG, "professional preset action failed", error); }
                    });
                };
            }
        });
        XposedBridge.hookMethod(symbols.method("PresetPopup.items"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                MenuState state = menuState.get();
                if (state == null) return;
                @SuppressWarnings("unchecked") List<Object> items = (List<Object>) p.args[0];
                appendMenuItems(state, items);
            }
        });
    }

    private static void runMenuAction(Object owner, Action action) throws Throwable {
        Activity activity = (Activity) symbols.get("PresetUi.activity", owner);
        if (action == Action.REMEMBER) {
            String key = ModuleEffects.scope(symbols);
            save(key, snapshot());
            lastSelection = key;
            Toast.makeText(activity, "已记忆专业参数", Toast.LENGTH_SHORT).show();
        } else if (action == Action.SAVE_AS) {
            Object manager = symbols.get("PresetUi.data", owner);
            Object current = symbols.call("EffectData.current", manager);
            symbols.call("PresetUi.showInput", owner, 0, current);
            professionalDialog = (Dialog) symbols.get("PresetUi.dialog", owner);
        } else {
            String key = ModuleEffects.scope(symbols);
            DataStore.put(symbols, loader, record(key), "");
            FilterCards.refreshCurrent();
            lastSelection = null;
            Object presenter = presenter();
            if (presenter != null) ProParamStore.apply(symbols, loader, presenter, inPro(), null);
            else ProParamStore.set(symbols, loader, null);
            Toast.makeText(activity, "已清除专业参数", Toast.LENGTH_SHORT).show();
        }
    }

    private static Object presenterOf(Object listener, Class<?> presenterClass) {
        if (listener == null) return null;
        for (Field field : listener.getClass().getDeclaredFields()) {
            if (field.getType().isPrimitive()) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(listener);
                if (presenterClass.isInstance(value)) return value;
            } catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        }
        return null;
    }

    private static Object presenter() {
        if (currentPresenter != null) return currentPresenter;
        return presenterOf(gear, symbols.cls("CaptureParams"));
    }

    private static boolean inPro() throws Throwable {
        Object presenter = presenter();
        return presenter != null && "professional".equals(symbols.get("FeaturePresenter.modeName", presenter))
            && (Boolean) symbols.get("CaptureView.proActive", symbols.get("CaptureParams.view", presenter));
    }

    private static Object record(String key) throws Throwable {
        return symbols.call("DataKeys.prefs", null, String.class, PREFIX + key);
    }

    public static boolean hasCurrentRecord() throws Throwable {
        if (loader == null || symbols == null) return false;
        String scope = ModuleEffects.scope(symbols);
        if (scope == null || scope.isEmpty()) return false;
        String text = (String) DataStore.get(symbols, loader, record(scope), "");
        return text != null && !text.isEmpty();
    }

    private static Map<String, String> snapshot() throws Throwable {
        return ProParamStore.snapshot(symbols, loader);
    }

    private static void save(String key, Map<String, String> values) throws Throwable {
        DataStore.put(symbols, loader, record(key), ProParamsRecord.write(values));
        FilterCards.refreshCurrent();
    }

    private static void apply(String key) throws Throwable {
        Object presenter = presenter();
        if (presenter == null || !"professional".equals(symbols.get("FeaturePresenter.modeName", presenter))) return;
        String text = (String) DataStore.get(symbols, loader, record(key), "");
        if (text.isEmpty()) {
            if (Settings.get(ModuleSettings.MASTER_PRO_EXPAND)) {
                ProParamStore.apply(symbols, loader, presenter, null, null);
                if (inPro()) switchGear(presenter);
            } else {
                ProParamStore.apply(symbols, loader, presenter, false, null);
            }
            Log.i(TAG, "cleared pro parameters for " + key);
            return;
        }
        Map<String, String> values = ProParamsRecord.parse(text);
        ProParamStore.set(symbols, loader, values);
        if (!inPro() && Settings.get(ModuleSettings.MASTER_PRO_EXPAND)) {
            switchGear(presenter);
        }
        ProParamStore.apply(symbols, loader, presenter, true, values);
        Log.i(TAG, "restored professional parameters for " + key);
    }

    private static void switchGear(Object presenter) throws Throwable {
        if (gear == null) return;
        Object effectPanel = symbols.get("CaptureParams.effectMenu", presenter);
        if (effectPanel != null && (Boolean) symbols.call("EffectMenuBase.expanded", effectPanel)) {
            switchingInEffectPanel.set(true);
            try { gear.onClick(null); }
            finally { switchingInEffectPanel.remove(); }
        } else {
            gear.onClick(null);
        }
    }
}
