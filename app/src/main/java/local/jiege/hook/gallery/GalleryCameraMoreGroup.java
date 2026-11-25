package local.jiege.hook.gallery;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;
import local.jiege.hook.common.SyntheticResources;
import local.jiege.hook.watermark.FilmFrame;
import local.jiege.hook.watermark.LeicaStyles;

/**
 * Gallery, the photo watermark settings page (watermarkCamera): adds the "更多" and "徕卡" groups
 * sequentially right after "品牌力量".
 */
public final class GalleryCameraMoreGroup {
    private static final String TAG = "GalleryCameraMoreGroup";

    private static final class GroupDef {
        final String tag;
        final String title;
        final String prefix;
        final String styleDir;
        final boolean isMore;
        int titleId;

        GroupDef(String tag, String title, String prefix, String styleDir, boolean isMore) {
            this.tag = tag;
            this.title = title;
            this.prefix = prefix;
            this.styleDir = styleDir;
            this.isMore = isMore;
        }

        synchronized int titleId() {
            if (titleId == 0) titleId = SyntheticResources.string(title);
            return titleId;
        }

        String[] allStyles() {
            return isMore ? GalleryMoreSeries.styles() : GalleryMoreSeries.leicaStyles();
        }
    }

    private static final GroupDef[] GROUPS = {
        new GroupDef(
                "more_series_group",
                "更多",
                FilmFrame.STYLE_PREFIX,
                "/data/user/0/com.coloros.gallery3d/files/watermark_master/more_series",
                true),
        new GroupDef(
                "leica_series_group",
                "徕卡",
                LeicaStyles.STYLE_PREFIX,
                "/data/user/0/com.coloros.gallery3d/files/watermark_master/leica_series",
                false),
    };

    private static final Map<Object, Map<String, Object>> ADAPTERS = new WeakHashMap<>();

    private GalleryCameraMoreGroup() {}

    public static void installGallery() {
        Symbols symbols = Symbols.get();
        Log.guard(TAG, "group styles", () -> hookStyleProvider(symbols));
        Log.guard(TAG, "group view", () -> hookGroupView(symbols));
        Log.guard(TAG, "group selection", () -> hookSelection(symbols));
    }

    private static void hookSelection(Symbols symbols) {
        XposedBridge.hookMethod(symbols.method("CameraSection.styleAdapterOf"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.getResult() != null || !(param.args[0] instanceof String)) return;
                String styleId = (String) param.args[0];
                GroupDef target = null;
                for (GroupDef g : GROUPS) {
                    if (styleId.startsWith(g.prefix)) {
                        target = g;
                        break;
                    }
                }
                if (target == null) return;
                Object adapter;
                synchronized (ADAPTERS) {
                    Map<String, Object> perPage = ADAPTERS.get(param.thisObject);
                    adapter = perPage != null ? perPage.get(target.prefix) : null;
                }
                if (adapter != null) param.setResult(adapter);
            }
        });
    }

    private static void hookStyleProvider(Symbols symbols) {
        Method itemFactory = symbols.method("MasterVM.styleItems");
        XposedBridge.hookMethod(symbols.method("StyleProvider.query"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object[] arguments = (Object[]) param.args[0];
                if (arguments == null || arguments.length == 0) return;
                GroupDef match = null;
                for (GroupDef g : GROUPS) {
                    if (Integer.valueOf(g.titleId()).equals(arguments[0])) {
                        match = g;
                        break;
                    }
                }
                if (match == null) return;
                try {
                    Object master = fieldOfType(param.thisObject, itemFactory.getDeclaringClass());
                    List<String> styles = new ArrayList<>();
                    for (String style : match.allStyles()) {
                        if (new File(match.styleDir, style + ".json").isFile()) styles.add(style);
                    }
                    GalleryMoreSeries.CAMERA_PAGE.set(Boolean.TRUE);
                    try {
                        param.setResult(itemFactory.invoke(
                                master, new ArrayList<>(styles), new int[0], symbols.get("MasterVM.itemSize", null)));
                    } finally {
                        GalleryMoreSeries.CAMERA_PAGE.remove();
                    }
                } catch (Throwable error) {
                    Log.e(TAG, "group styles failed: " + match.title, error);
                    param.setResult(new ArrayList<>());
                }
            }
        });
    }

    private static void hookGroupView(Symbols symbols) {
        Method adapterFactory = symbols.method("CameraSection.styleAdapter");
        XposedBridge.hookMethod(adapterFactory, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Context context = (Context) XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
                    int brandTitle = context.getResources().getIdentifier(
                            "pitcure_edtior_text_watermark_brand_power", "string", context.getPackageName());
                    if (!Integer.valueOf(brandTitle).equals(param.args[1])) return;
                    addGroups(param.args[0], adapterFactory, context);
                } catch (Throwable error) {
                    Log.e(TAG, "group view failed", error);
                }
            }
        });
    }

    private static void addGroups(Object page, Method adapterFactory, Context context) throws Throwable {
        int layoutId = context.getResources().getIdentifier(
                "camera_series_brand_layout", "id", context.getPackageName());
        ViewGroup brand = null;
        for (Class<?> type = page.getClass(); type != null && brand == null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!ViewGroup.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object view = field.get(page);
                if (view instanceof View && ((View) view).findViewById(layoutId) instanceof ViewGroup) {
                    brand = (ViewGroup) ((View) view).findViewById(layoutId);
                    break;
                }
            }
        }
        if (brand == null || !(brand.getParent() instanceof LinearLayout)) return;
        LinearLayout parent = (LinearLayout) brand.getParent();
        for (int i = 0; i < parent.getChildCount(); i++) {
            Object childTag = parent.getChildAt(i).getTag();
            for (GroupDef g : GROUPS) {
                if (g.tag.equals(childTag)) {
                    parent.removeViewAt(i--);
                    break;
                }
            }
        }

        TextView brandTitle = null;
        ViewGroup brandList = null;
        for (int i = 0; i < brand.getChildCount(); i++) {
            View child = brand.getChildAt(i);
            if (brandTitle == null && child instanceof TextView) brandTitle = (TextView) child;
            else if (brandList == null && isRecycler(child)) brandList = (ViewGroup) child;
        }
        if (brandList == null) return;

        Map<String, Object> pageAdapters = new HashMap<>();
        int insertIndex = parent.indexOfChild(brand) + 1;
        for (GroupDef g : GROUPS) {
            boolean hasAny = false;
            for (String style : g.allStyles()) {
                if (new File(g.styleDir, style + ".json").isFile()) {
                    hasAny = true;
                    break;
                }
            }
            if (!hasAny) continue;

            Object adapter = adapterFactory.invoke(null, page, g.titleId(), false, 6);
            if (adapter == null) continue;

            LinearLayout group = new LinearLayout(context);
            group.setTag(g.tag);
            group.setOrientation(LinearLayout.VERTICAL);
            group.setLayoutParams(new LinearLayout.LayoutParams(brand.getLayoutParams()));
            group.setClipChildren(false);

            TextView title = new TextView(context);
            if (brandTitle != null) {
                title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, brandTitle.getTextSize());
                title.setTextColor(brandTitle.getTextColors());
                title.setTypeface(brandTitle.getTypeface());
                title.setLayoutParams(new LinearLayout.LayoutParams((ViewGroup.MarginLayoutParams) brandTitle.getLayoutParams()));
                title.setPadding(brandTitle.getPaddingLeft(), brandTitle.getPaddingTop(), brandTitle.getPaddingRight(), brandTitle.getPaddingBottom());
            }
            title.setText(g.title);
            group.addView(title);

            ViewGroup list = (ViewGroup) XposedHelpers.newInstance(brandList.getClass(), context);
            list.setLayoutParams(new LinearLayout.LayoutParams(brandList.getLayoutParams()));
            list.setPadding(brandList.getPaddingLeft(), brandList.getPaddingTop(), brandList.getPaddingRight(), brandList.getPaddingBottom());
            Object manager = XposedHelpers.callMethod(brandList, "getLayoutManager");
            XposedHelpers.callMethod(list, "setLayoutManager", XposedHelpers.newInstance(manager.getClass(), context, 0, false));
            if ((Integer) XposedHelpers.callMethod(brandList, "getItemDecorationCount") > 0) {
                XposedHelpers.callMethod(list, "addItemDecoration", XposedHelpers.callMethod(brandList, "getItemDecorationAt", 0));
            }
            XposedHelpers.callMethod(list, "setAdapter", adapter);
            group.addView(list);

            parent.addView(group, insertIndex++);
            pageAdapters.put(g.prefix, adapter);
        }

        synchronized (ADAPTERS) {
            ADAPTERS.put(page, pageAdapters);
        }
        Log.i(TAG, "groups added after brand group: " + pageAdapters.size());
    }

    private static boolean isRecycler(View view) {
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if ("androidx.recyclerview.widget.RecyclerView".equals(type.getName())) return true;
        }
        return false;
    }

    private static Object fieldOfType(Object target, Class<?> type) throws IllegalAccessException {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!type.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                return field.get(target);
            }
        }
        throw new IllegalStateException("no field of " + type.getName());
    }
}
