package local.jiege.hook.gallery;

import android.content.Context;
import android.view.View;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.common.SyntheticResources;
import local.jiege.hook.watermark.FilmFrame;
import local.jiege.hook.watermark.LeicaStyles;
import local.jiege.hook.watermark.PhocusStyles;

/**
 * Gallery photo editor, personalised watermark ("个性画框"): custom series after built-in ones:
 * "更多" (Polaroid film frame + Hasselblad Phocus) followed by "徕卡" (Xiaomi Leica watermarks).
 */
public final class GalleryMoreSeries {
    private static final String TAG = "GalleryMoreSeries";
    private static final String ASSET_DIR = "watermark_master_styles/";
    private static final String MASTER_STYLE = "com.oplus.gallery.framework.abilities.watermark.masterstyle.WatermarkMasterStyle";
    private static final String BG_COLOR_ENUM = "com.oplus.gallery.framework.abilities.watermark.FrameBackgroundColor";

    /** Set while the camera watermark settings page builds its cards, which are tall (2:3); the editor's are square (1:1). */
    static final ThreadLocal<Boolean> CAMERA_PAGE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> CHOOSING_PREVIEW = new ThreadLocal<>();

    static final class SeriesDef {
        final String id;
        final String prefix;
        final String dir;
        final String title;
        final String description;
        final String[] styles;
        final boolean isLeica;
        int titleId;
        int descriptionId;

        SeriesDef(String id, String prefix, String dir, String title, String description,
                  String[] styles, boolean isLeica) {
            this.id = id;
            this.prefix = prefix;
            this.dir = dir;
            this.title = title;
            this.description = description;
            this.styles = styles;
            this.isLeica = isLeica;
        }

        boolean owns(Object styleId) {
            return styleId instanceof String && ((String) styleId).startsWith(prefix);
        }

        synchronized void ensureTitles() {
            if (titleId == 0) {
                titleId = SyntheticResources.string(title);
                descriptionId = SyntheticResources.string(description);
            }
        }
    }

    private static final SeriesDef MORE = new SeriesDef(
            "more_series",
            FilmFrame.STYLE_PREFIX,
            "/data/user/0/com.coloros.gallery3d/files/watermark_master/more_series",
            "更多",
            "拍立得胶片相框与哈苏 Phocus 水印",
            buildMoreStyles(),
            false);

    private static final SeriesDef LEICA = new SeriesDef(
            "leica_series",
            LeicaStyles.STYLE_PREFIX,
            "/data/user/0/com.coloros.gallery3d/files/watermark_master/leica_series",
            "徕卡",
            "小米徕卡相机经典画框与风格水印",
            buildLeicaStyles(),
            true);

    private static final SeriesDef[] ALL_SERIES = {MORE, LEICA};

    private static String[] buildMoreStyles() {
        int[] order = PhocusStyles.STYLE_ORDER;
        String[] result = new String[1 + order.length];
        result[0] = "personalize_more_1";
        for (int i = 0; i < order.length; i++) {
            result[i + 1] = PhocusStyles.STYLE_PREFIX + order[i];
        }
        return result;
    }

    private static String[] buildLeicaStyles() {
        int[] order = LeicaStyles.STYLE_ORDER;
        String[] result = new String[order.length];
        for (int i = 0; i < order.length; i++) {
            result[i] = LeicaStyles.STYLE_PREFIX + order[i];
        }
        return result;
    }

    public static String[] styles() {
        return MORE.styles.clone();
    }

    public static String[] leicaStyles() {
        return LEICA.styles.clone();
    }

    private static SeriesDef seriesOf(Object styleId) {
        for (SeriesDef s : ALL_SERIES) {
            if (s.owns(styleId)) return s;
        }
        return null;
    }

    private static boolean ours(Object styleId) {
        return seriesOf(styleId) != null;
    }

    private GalleryMoreSeries() {}

    public static void installGallery(ClassLoader classLoader) {
        Symbols symbols = Symbols.get();
        Log.guard(TAG, "series styles", () -> hookStyles(classLoader));
        Log.guard(TAG, "series style files", () -> hookStyleSource(symbols));
        Log.guard(TAG, "series title", () -> hookSeriesTitle(symbols));
        Log.guard(TAG, "style card images", () -> hookStyleCards(symbols));
        Log.guard(TAG, "instant film frame", () -> FilmFrame.install(classLoader, MORE.dir));
        Log.guard(TAG, "adjust panel", () -> hookAdjustPanel(symbols, classLoader));
        Log.guard(TAG, "preview photo", () -> hookPreviewPhoto(symbols, classLoader));
        Log.guard(TAG, "retap edit", () -> hookRetapEdit(symbols));
    }

    /** Styles without editable items: tapping the selected card again must not open the (empty) edit page. */
    private static void hookRetapEdit(Symbols symbols) {
        XposedBridge.hookMethod(symbols.method("StyleAdapter.itemClick"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object item = itemAt(param.thisObject, (Integer) param.args[0]);
                String id = item != null ? styleIdOf(item) : null;
                if (id == null || isEditable(id)) return;
                if (Boolean.TRUE.equals(XposedHelpers.callMethod(item, "isSelected"))) param.setResult(null);
            }
        });
    }

    private static boolean isEditable(String styleId) {
        if (styleId.startsWith(PhocusStyles.STYLE_PREFIX)) return false;
        if (styleId.startsWith(LeicaStyles.STYLE_PREFIX)) return LeicaStyles.supportsBgEdit(styleId);
        return true;
    }

    /** The adapter's list item at {@code position}, found by shape: the list whose item carries one of our style ids. */
    private static Object itemAt(Object adapter, int position) {
        for (Class<?> c = adapter.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object list = f.get(adapter);
                    if (!(list instanceof List) || position < 0 || position >= ((List<?>) list).size()) continue;
                    Object item = ((List<?>) list).get(position);
                    if (item != null && styleIdOf(item) != null) return item;
                } catch (IllegalAccessException ignored) {
                }
            }
        }
        return null;
    }

    private static String styleIdOf(Object item) {
        for (Field f : item.getClass().getDeclaredFields()) {
            if (f.getType() != String.class) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(item);
                if (ours(v)) return (String) v;
            } catch (IllegalAccessException ignored) {
            }
        }
        return null;
    }

    private static void hookPreviewPhoto(Symbols symbols, ClassLoader classLoader) throws Throwable {
        Class<?> companion = XposedHelpers.findClass(MASTER_STYLE + "$Companion", classLoader);
        XposedBridge.hookMethod(symbols.method("Personalized.previewPhoto"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                CHOOSING_PREVIEW.set(Boolean.TRUE);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                CHOOSING_PREVIEW.remove();
            }
        });
        XposedBridge.hookAllMethods(companion, "isBrandSeriesStyle", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (CHOOSING_PREVIEW.get() != null && ours(param.args[0])) param.setResult(Boolean.TRUE);
            }
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void hookAdjustPanel(Symbols symbols, ClassLoader classLoader) {
        String[] fullHide = {"sv_tool_container", "cl_tool_container", "rl_bottom_tool_frame_list", "rl_picture_area", "lv_picture_list",
            "btn_picture", "btn_text", "btn_background_color", "btn_texture", "btn_picture_edit", "btn_text_edit",
            "btn_composition_edit", "section_between_text_and_background", "section_between_background_and_composition"};
        String[] leicaHide = {"rl_bottom_tool_frame_list", "rl_picture_area", "lv_picture_list",
            "btn_picture", "btn_text", "btn_texture", "btn_picture_edit", "btn_text_edit", "btn_composition_edit",
            "section_between_text_and_background", "section_between_background_and_composition"};
        String[] bgShow = {"btn_background_color", "rl_background_area", "rl_background_color_list",
            "fl_select_color", "fl_white_color", "fl_black_color", "fl_colorful_color", "tv_background_description"};

        XposedBridge.hookMethod(symbols.method("Personalized.setup"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object styleId = symbols.get("Personalized.style", param.thisObject);
                    if (!(styleId instanceof String) || !LeicaStyles.supportsBgEdit((String) styleId)) return;
                    if (param.args.length == 0 || param.args[0] == null) return;
                    Class<?> enumCls = XposedHelpers.findClass(BG_COLOR_ENUM, classLoader);
                    List<Object> colors = findBackgroundColorList(param.args[0], enumCls);
                    if (colors != null) {
                        colors.clear();
                        colors.add(Enum.valueOf((Class) enumCls, "ABSORB"));
                        colors.add(Enum.valueOf((Class) enumCls, "WHITE"));
                        colors.add(Enum.valueOf((Class) enumCls, "BLACK"));
                        colors.add(Enum.valueOf((Class) enumCls, "BLUR"));
                    }
                } catch (Throwable error) {
                    Log.e(TAG, "inject leica background colors failed", error);
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object styleObj = symbols.get("Personalized.style", param.thisObject);
                    if (!ours(styleObj)) return;
                    String styleId = (String) styleObj;
                    Context context = (Context) symbols.get("Editing.context", param.thisObject);
                    View root = findSectionRoot(param.thisObject, context);
                    if (root == null) return;

                    if (LeicaStyles.supportsBgEdit(styleId)) {
                        for (String name : leicaHide) {
                            setViewVisibility(root, context, name, View.GONE);
                        }
                        for (String name : bgShow) {
                            setViewVisibility(root, context, name, View.VISIBLE);
                        }
                        int bgBtnId = context.getResources().getIdentifier("btn_background_color", "id", context.getPackageName());
                        View bgBtn = bgBtnId != 0 ? root.findViewById(bgBtnId) : null;
                        if (bgBtn != null) bgBtn.performClick();
                    } else {
                        for (String name : fullHide) {
                            setViewVisibility(root, context, name, View.GONE);
                        }
                    }
                } catch (Throwable error) {
                    Log.e(TAG, "adjust panel failed", error);
                }
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Object> findBackgroundColorList(Object uiBean, Class<?> enumCls)
            throws IllegalAccessException {
        List<Object> emptyFallback = null;
        for (Class<?> c = uiBean.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object val = f.get(uiBean);
                if (!(val instanceof List)) continue;
                List<Object> list = (List<Object>) val;
                if (!list.isEmpty() && enumCls.isInstance(list.get(0))) return list;
                if (list.isEmpty() && val instanceof ArrayList && emptyFallback == null) {
                    emptyFallback = list;
                }
            }
        }
        return emptyFallback;
    }

    private static View findSectionRoot(Object section, Context context) throws IllegalAccessException {
        int probe = context.getResources().getIdentifier("btn_picture", "id", context.getPackageName());
        for (Class<?> type = section.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!View.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object value = field.get(section);
                if (value instanceof View && ((View) value).findViewById(probe) != null) {
                    return (View) value;
                }
            }
        }
        return null;
    }

    private static void setViewVisibility(View root, Context context, String idName, int visibility) {
        int id = context.getResources().getIdentifier(idName, "id", context.getPackageName());
        View view = id != 0 ? root.getRootView().findViewById(id) : null;
        if (view == null) return;
        view.setVisibility(visibility);
        if (idName.startsWith("btn_") && view.getParent() instanceof View) {
            ((View) view.getParent()).setVisibility(visibility);
        }
    }

    private static void hookStyleCards(Symbols symbols) {
        XposedBridge.hookMethod(symbols.method("MasterVM.styleItems"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    List<?> styles = (List<?>) param.args[0];
                    int[] sketches = (int[]) param.args[1];
                    for (int i = 0; i < styles.size(); i++) {
                        SeriesDef s = seriesOf(styles.get(i));
                        if (s == null) continue;
                        Integer sketch = null;
                        if (Boolean.TRUE.equals(CAMERA_PAGE.get())) {
                            sketch = SyntheticResources.drawable(
                                    "sketch_camera_" + styles.get(i),
                                    new File(s.dir, "sketch_camera_" + styles.get(i) + ".png"));
                        }
                        if (sketch == null) {
                            sketch = SyntheticResources.drawable(
                                    "sketch_" + styles.get(i),
                                    new File(s.dir, "sketch_" + styles.get(i) + ".png"));
                        }
                        if (sketch == null) continue;
                        if (sketches.length <= i) sketches = Arrays.copyOf(sketches, i + 1);
                        sketches[i] = sketch;
                    }
                    param.args[1] = sketches;
                } catch (Throwable error) {
                    Log.e(TAG, "style card images failed", error);
                }
            }
        });
    }

    private static void hookStyles(ClassLoader classLoader) throws Throwable {
        Class<?> companion = XposedHelpers.findClass(MASTER_STYLE + "$Companion", classLoader);
        Class<?> pair = XposedHelpers.findClass("kotlin.Pair", classLoader);
        XposedBridge.hookAllMethods(companion, "getSeries", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.getResult() != null) return;
                SeriesDef s = seriesOf(param.args[0]);
                if (s != null) param.setResult(s.id);
            }
        });
        XposedBridge.hookAllMethods(companion, "isSupportLogoEdit", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (ours(param.args[0])) param.setResult(Boolean.FALSE);
            }
        });
        XposedBridge.hookAllMethods(companion, "isPersonalizeFrame", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object id = param.args[0];
                SeriesDef s = seriesOf(id);
                if (s == null) return;
                param.setResult(s.isLeica && LeicaStyles.supportsBgEdit((String) id));
            }
        });
        XC_MethodHook list = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    List<Object> styles = new ArrayList<>((List<?>) param.getResult());
                    for (SeriesDef s : ALL_SERIES) {
                        for (String style : s.styles) {
                            if (!new File(s.dir, style + ".json").isFile()) continue;
                            styles.add(XposedHelpers.newInstance(pair, style, ASSET_DIR + style + ".json"));
                        }
                    }
                    param.setResult(styles);
                } catch (Throwable error) {
                    Log.e(TAG, "series style list failed", error);
                }
            }
        };
        int hooked = XposedBridge.hookAllMethods(companion, "getBuiltInPersonalStyles", list).size();
        hooked += XposedBridge.hookAllMethods(companion, "getAllBuiltInStyles", list).size();
        if (hooked == 0) throw new IllegalStateException("no style list method");
    }

    private static void hookStyleSource(Symbols symbols) {
        XposedBridge.hookMethod(symbols.method("AssetStyleSource.open"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    String asset = (String) symbols.get("AssetStyleSource.name", param.thisObject);
                    if (asset == null || !asset.startsWith(ASSET_DIR) || !asset.endsWith(".json")) return;
                    String rel = asset.substring(ASSET_DIR.length());
                    for (SeriesDef s : ALL_SERIES) {
                        if (!rel.startsWith(s.prefix)) continue;
                        File style = new File(s.dir, rel);
                        if (style.isFile()) {
                            param.setResult(new FileInputStream(style));
                            return;
                        }
                    }
                } catch (Throwable error) {
                    Log.e(TAG, "series style routing failed", error);
                }
            }
        });
    }

    private static void hookSeriesTitle(Symbols symbols) {
        Method setTitle = symbols.method("MasterSection.setSeriesTitle");
        setTitle.setAccessible(true);
        XposedBridge.hookMethod(symbols.method("MasterSection.seriesTitle"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                SeriesDef s = seriesOf(param.args[0]);
                if (s == null) return;
                try {
                    s.ensureTitles();
                    setTitle.invoke(param.thisObject, s.titleId, s.descriptionId);
                    param.setResult(null);
                } catch (Throwable error) {
                    Log.e(TAG, "series title failed", error);
                }
            }
        });
    }
}
