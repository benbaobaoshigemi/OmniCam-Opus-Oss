package local.jiege.hook.quickruler;

import android.app.Activity;
import android.view.View;
import de.robv.android.xposed.XposedHelpers;
import java.util.List;
import local.jiege.hook.common.DataStore;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.ProParamStore;
import local.jiege.hook.common.Reflection;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.filterpanel.LegacyFilterPanel;

/**
 * Exposure compensation of the mode in use, through the camera's own scripting class
 * (com.oplus.camera.util.AndroidTestAdapter, whose names are not obfuscated). Its setters write the camera's store
 * under the current mode's name and make the value take effect the way the camera's own controls do, so the
 * stock readouts follow the ruler and the ruler follows them; the master mode and the Ricoh mode keep separate
 * values because the camera scopes them by mode.
 *
 * The camera has two exposure compensations per mode: the global one of the Auto gear (the readout "EV" in the top
 * bar) and the one of the Pro parameter bar. The ruler works on the one of the gear in use.
 */
final class EvBinding implements QuickRulerView.Binding {
    private static final String TAG = "QuickRulerEv";
    private static final String ADAPTER = "com.oplus.camera.util.AndroidTestAdapter";
    private static final String CAMERA_MANAGER = "com.oplus.camera.CameraManager";
    private static final String GEAR_KEY = "key_master_mode_auto_option";
    private static final String TOP_LEFT_MENU = "com.oplus.camera.ui.newsetting.container.CameraMenuContainerTopLeft";
    private static final long RETRY_MS = 2000;

    private final Activity activity;
    private final Symbols symbols = Symbols.get();
    private Object adapter;
    private Object gearKey;
    /**
     * The options of the gear the model was read for, as the camera spells them ("-0.3", "0.0", "+0.3": rounded, so
     * a third of an EV is not exactly 0.3). The ruler counts options, not EV: its value is the distance in options
     * from the option that is 0, one tick each, and the text shown is the camera's own spelling.
     */
    private String[] names = {"-1.0", "0.0", "+1.0"};
    /** What is written for each option: the camera's own spelling in the Auto gear, the EV the option stands for in the Pro gear. */
    private String[] labels = names;
    private int zero = 1;
    /** The distance between two options in EV, which tells how many ticks make a whole EV. */
    private float evStep = 1f;
    private boolean modelPro;
    private String modelMode;
    private boolean modelLoaded;
    private int proIndex;
    private long failedAt;

    EvBinding(Activity activity) {
        this.activity = activity;
    }

    private boolean proGear() throws Throwable {
        if (gearKey == null) gearKey = DataStore.key(symbols, "Keys.proAuto", GEAR_KEY);
        return Boolean.FALSE.equals(DataStore.get(symbols, activity.getClassLoader(), gearKey, Boolean.TRUE));
    }

    private Object adapter() throws IllegalAccessException {
        if (adapter == null) {
            Object manager = Reflection.findFieldByType(activity, CAMERA_MANAGER);
            if (manager == null) throw new IllegalStateException("camera manager not found");
            adapter = XposedHelpers.newInstance(XposedHelpers.findClass(ADAPTER, activity.getClassLoader()), activity, manager);
        }
        return adapter;
    }

    /** Reads the options of the mode and gear in use once for each; true when the model is usable. */
    private boolean model() {
        try {
            String mode = LegacyFilterPanel.modeName(activity);
            if (!"gr".equals(mode) && !"professional".equals(mode)) return false;
            boolean pro = proGear();
            if (modelLoaded && pro == modelPro && java.util.Objects.equals(mode, modelMode)) return true;
            if (System.currentTimeMillis() - failedAt < RETRY_MS) return false;
            String[] options;
            if (pro) {
                proIndex = (Integer) XposedHelpers.callMethod(adapter(), "getProfessionalParamIndex", "EV");
                if (proIndex < 0) return false;
                options = (String[]) XposedHelpers.callMethod(adapter(), "getProfessionalList", proIndex);
            } else {
                @SuppressWarnings("unchecked")
                List<String> list = (List<String>) XposedHelpers.callMethod(adapter(), "getSupportedGlobalEvList");
                options = list == null ? null : list.toArray(new String[0]);
            }
            // The Pro parameter bar container may not be inflated yet on attach or mode entry; wait silently.
            if (options == null) return false;
            if (options.length < 2) throw new IllegalStateException("too few options: " + options.length);
            int home = 0;
            float first = 0f;
            float last = 0f;
            for (int i = 0; i < options.length; i++) {
                float parsed = Float.parseFloat(options[i].trim());
                if (i == 0) first = parsed;
                last = parsed;
                if (Math.abs(parsed) < Math.abs(Float.parseFloat(options[home].trim()))) home = i;
            }
            String[] written = options;
            float step = Math.abs(last - first) / (options.length - 1);
            if (pro) {
                // The Pro options are whole numbers (-18..18); the camera's own bar shows them as EV over the same
                // range as the Auto gear's options, so the EV of one option is that range over the last number.
                @SuppressWarnings("unchecked")
                List<String> global = (List<String>) XposedHelpers.callMethod(adapter(), "getSupportedGlobalEvList");
                if (global == null || global.isEmpty() || last == 0f) return false;
                float range = Math.abs(Float.parseFloat(global.get(global.size() - 1).trim()));
                float perOption = range / Math.abs(last);
                written = new String[options.length];
                for (int i = 0; i < options.length; i++) written[i] = evText(Float.parseFloat(options[i].trim()) * perOption);
                step = perOption;
            }
            names = options;
            labels = written;
            zero = home;
            evStep = step;
            modelPro = pro;
            modelMode = mode;
            modelLoaded = true;
            Log.i(TAG, (pro ? "pro" : "global") + " options " + String.join(",", options) + " zero " + zero
                + " labels " + String.join(",", written));
            return true;
        } catch (Throwable error) {
            failedAt = System.currentTimeMillis();
            Log.e(TAG, "options not read", error);
            return false;
        }
    }

    private int indexOf(float value) {
        return Math.max(0, Math.min(names.length - 1, Math.round(value) + zero));
    }

    @Override public String name() {
        return "EV";
    }

    @Override public float min() {
        return -zero;
    }

    @Override public float max() {
        return names.length - 1 - zero;
    }

    @Override public float step() {
        return 1f;
    }

    @Override public int ticksPerLong() {
        return Math.max(1, Math.round(1f / evStep));
    }

    @Override public float home() {
        return 0f;
    }

    @Override public float read() {
        if (!model()) return 0f;
        try {
            String text;
            if (modelPro) text = (String) XposedHelpers.callMethod(adapter(), "getProfessionalExposureCompensation");
            else text = (String) XposedHelpers.callMethod(adapter(), "getCurrentGlobalEvValue");
            if (text == null || text.isEmpty()) return 0f;
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(text)) return i - zero;
            }
            float ev = Float.parseFloat(text.trim());
            int best = 0;
            for (int i = 1; i < names.length; i++) {
                if (Math.abs(Float.parseFloat(names[i].trim()) - ev) < Math.abs(Float.parseFloat(names[best].trim()) - ev)) best = i;
            }
            return best - zero;
        } catch (Throwable error) {
            Log.e(TAG, "value not read", error);
            return 0f;
        }
    }

    @Override public void write(float value) {
        if (!model()) return;
        try {
            int index = indexOf(value);
            if (modelPro) {
                XposedHelpers.callMethod(adapter(), "setProfessionalParameter", proIndex, index);
                showOnParameterBar();
            } else {
                XposedHelpers.callMethod(adapter(), "setGlobalEvValue", names[index]);
                updateTopBarReadout(names[index]);
            }
        } catch (Throwable error) {
            Log.e(TAG, "value not set", error);
        }
    }

    /**
     * The exposure readout in the top bar ("EV 0.0") is set by the top-left menu container when the camera's own
     * controls change the exposure compensation (it is given the text to show); the store changed here without
     * that, so the container is given the same text. Without this the readout only changes once it is tapped.
     */
    private void updateTopBarReadout(String text) throws Throwable {
        View bar = activity.findViewById(activity.getResources().getIdentifier("oplus_setting_bar_left", "id", activity.getPackageName()));
        for (View view = bar; view != null; view = view.getParent() instanceof View ? (View) view.getParent() : null) {
            if (TOP_LEFT_MENU.equals(view.getClass().getName())) {
                symbols.call("TopLeftMenu.updateEv", view, text);
                return;
            }
        }
    }

    /** Has the stock Pro parameter bar show what the store holds now (the same projection the Pro parameter changes use). */
    private void showOnParameterBar() throws Throwable {
        Object features = symbols.call("FeatureManager.of", null, activity);
        Object presenter = features == null ? null : symbols.call("FeatureManager.captureParams", features);
        if (presenter != null) ProParamStore.refreshPanel(symbols, presenter);
    }

    @Override public String format(float v) {
        return labels[indexOf(v)];
    }

    /** An EV the way the camera writes it: one decimal, a sign unless zero. */
    private static String evText(float ev) {
        return Math.abs(ev) < 0.05f ? "0.0" : String.format(java.util.Locale.US, "%+.1f", ev);
    }
}
