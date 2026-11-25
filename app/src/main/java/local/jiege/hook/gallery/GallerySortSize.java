package local.jiege.hook.gallery;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import androidx.core.graphics.drawable.DrawableCompat;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import local.jiege.hook.common.Log;
import local.jiege.hook.common.Symbols;

/**
 * Extends gallery album sorting with a file-size option ("大小").
 *
 * Sort codes:
 *   7 = size descending (_size DESC, local_media._id DESC)
 *   8 = size ascending (_size ASC, local_media._id ASC)
 *
 * The sort-clause dispatch, the sort popup controller and its view model are symbols (SortSize.*, SortMenu.*,
 * SortVm.*), found by what they do.
 */
public final class GallerySortSize {
    private static final String TAG = "GallerySortSize";
    public static final int SORT_SIZE_DESC = 7;
    public static final int SORT_SIZE_ASC = 8;

    private static final Map<Object, Object> sizeItems = new WeakHashMap<>();

    private GallerySortSize() {}

    public static void installGallery(ClassLoader loader) {
        Symbols symbols = Symbols.get();
        Log.guard(TAG, "gallery size sort clause", () -> hookClauseGenerator(symbols));
        Log.guard(TAG, "gallery size sort menu", () -> hookMenuController(symbols, loader));
    }

    private static void hookClauseGenerator(Symbols symbols) {
        XposedBridge.hookMethod(symbols.method("SortSize.clause"), new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                int sortCode = (Integer) param.args[0];
                boolean reversed = (Boolean) param.args[1];
                if (sortCode == SORT_SIZE_DESC) {
                    param.setResult(reversed ? "_size ASC, local_media._id ASC" : "_size DESC, local_media._id DESC");
                } else if (sortCode == SORT_SIZE_ASC) {
                    param.setResult(reversed ? "_size DESC, local_media._id DESC" : "_size ASC, local_media._id ASC");
                }
            }
        });
    }

    /** The sort menu's icon table: the controller's map getter that is not the item table. */
    @SuppressWarnings("unchecked")
    private static Map<Integer, Drawable> icons(Symbols symbols, Object menu) throws Throwable {
        Method items = symbols.method("SortMenu.items");
        for (Method getter : symbols.<Method>all("SortMenu.maps")) {
            if (getter.equals(items)) continue;
            getter.setAccessible(true);
            return (Map<Integer, Drawable>) getter.invoke(menu);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void hookMenuController(Symbols symbols, ClassLoader loader) {
        // 1. Append "大小" item to sort sub-menu when the menu popup is configured
        XposedBridge.hookMethod(symbols.method("SortMenu.configure"), new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object popup = param.args[0];
                if (popup == null) return;
                List<?> items = (List<?>) XposedHelpers.callMethod(popup, "getItemList");
                if (items == null) return;

                for (Object item : items) {
                    List<Object> subItems = (List<Object>) XposedHelpers.callMethod(item, "getSubMenuItemList");
                    if (subItems == null || subItems.isEmpty()) continue;

                    boolean hasSize = false;
                    for (Object sub : subItems) {
                        CharSequence title = (CharSequence) XposedHelpers.callMethod(sub, "getTitle");
                        if (title != null && "大小".contentEquals(title)) {
                            hasSize = true;
                            break;
                        }
                    }

                    if (!hasSize) {
                        Object menu = param.thisObject;
                        Object sizeItem = sizeItems.get(menu);
                        if (sizeItem == null) {
                            sizeItem = createSizeItem(symbols, menu, loader);
                            sizeItems.put(menu, sizeItem);
                            try {
                                Map<Integer, Object> itemMap = (Map<Integer, Object>) symbols.call("SortMenu.items", menu);
                                itemMap.put(3, sizeItem);
                            } catch (Throwable ignored) {}
                        }
                        subItems.add(sizeItem);
                    }
                }
            }
        });

        // 2. Handle click on item index 3 ("大小")
        XposedBridge.hookMethod(symbols.method("SortMenu.select"), new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                int position = (Integer) param.args[0];
                if (position == 3) {
                    param.setResult(null); // Intercept native logic
                    Object menu = param.thisObject;
                    int current = (Integer) symbols.get("SortMenu.sort", menu);
                    int next = (current == SORT_SIZE_DESC) ? SORT_SIZE_ASC : SORT_SIZE_DESC;

                    Object callback = symbols.get("SortMenu.callback", menu);
                    if (callback != null) {
                        XposedHelpers.callMethod(callback, "invoke", next);
                    }

                    symbols.call("SortVm.setSort", symbols.get("SortMenu.viewModel", menu), next);
                }
            }
        });

        // 3. Update check state, tint and icons on UI refresh
        XposedBridge.hookMethod(symbols.method("SortMenu.refresh"), new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                int sort = (Integer) param.args[0];
                Object menu = param.thisObject;
                if (sort <= 0) {
                    sort = (Integer) symbols.call("SortVm.sort", symbols.get("SortMenu.viewModel", menu));
                }

                Map<Integer, Object> items = (Map<Integer, Object>) symbols.call("SortMenu.items", menu);
                Map<Integer, Drawable> icons = icons(symbols, menu);
                Object sizeItem = sizeItems.get(menu);
                if (sizeItem == null && items != null) sizeItem = items.get(3);

                Context context = (Context) symbols.call("SortMenu.context", menu);
                ColorStateList primaryColor = getPrimaryColor(context, loader);
                boolean isNight = (Boolean) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.coui.appcompat.darkmode.COUIDarkModeUtil", loader),
                    "isNightMode", context
                );
                ColorStateList normalColor = ColorStateList.valueOf(isNight ? 0xFFFFFFFF : 0xFF000000);

                if (sort == SORT_SIZE_DESC || sort == SORT_SIZE_ASC) {
                    param.setResult(null); // Intercept native switch

                    // Uncheck stock items (0: taken, 1: modified, 2: name)
                    for (int k = 0; k < 3; k++) {
                        Object it = items != null ? items.get(k) : null;
                        if (it != null) {
                            XposedHelpers.callMethod(it, "setChecked", false);
                            if (icons != null) XposedHelpers.callMethod(it, "setStateIcon", icons.get(0));
                            XposedHelpers.callMethod(it, "setTitleColorList", normalColor);
                        }
                    }

                    // Check and tint size item
                    if (sizeItem != null) {
                        XposedHelpers.callMethod(sizeItem, "setChecked", true);
                        Drawable icon = (icons != null) ? icons.get(sort == SORT_SIZE_DESC ? 2 : 1) : null;
                        if (icon != null && primaryColor != null) {
                            Drawable tinted = icon.getConstantState().newDrawable().mutate();
                            DrawableCompat.setTintList(tinted, primaryColor);
                            XposedHelpers.callMethod(sizeItem, "setStateIcon", tinted);
                        } else if (icon != null) {
                            XposedHelpers.callMethod(sizeItem, "setStateIcon", icon);
                        }
                        if (primaryColor != null) {
                            XposedHelpers.callMethod(sizeItem, "setTitleColorList", primaryColor);
                        }
                    }

                    symbols.set("SortMenu.sort", menu, sort);
                } else {
                    // Reset size item when another sort option is active
                    if (sizeItem != null) {
                        XposedHelpers.callMethod(sizeItem, "setChecked", false);
                        if (icons != null) XposedHelpers.callMethod(sizeItem, "setStateIcon", icons.get(0));
                        XposedHelpers.callMethod(sizeItem, "setTitleColorList", normalColor);
                    }
                }
            }
        });
    }

    private static Object createSizeItem(Symbols symbols, Object menu, ClassLoader loader) throws Throwable {
        Class<?> builderClass = XposedHelpers.findClass("com.coui.appcompat.poplist.PopupListItem$Builder", loader);
        Object builder = XposedHelpers.newInstance(builderClass);
        XposedHelpers.callMethod(builder, "setTitle", "大小");
        XposedHelpers.callMethod(builder, "setIsEnable", true);

        int currentSort = (Integer) symbols.get("SortMenu.sort", menu);
        boolean isSize = (currentSort == SORT_SIZE_DESC || currentSort == SORT_SIZE_ASC);
        XposedHelpers.callMethod(builder, "setIsChecked", isSize);

        Map<Integer, Drawable> icons = icons(symbols, menu);
        Drawable icon = icons != null ? icons.get(0) : null;
        if (icons != null) {
            if (currentSort == SORT_SIZE_DESC) icon = icons.get(2);
            else if (currentSort == SORT_SIZE_ASC) icon = icons.get(1);
        }

        if (icon != null) {
            XposedHelpers.callMethod(builder, "setStateIcon", icon);
        }

        Object item = XposedHelpers.callMethod(builder, "build");
        if (isSize) {
            Context context = (Context) symbols.call("SortMenu.context", menu);
            ColorStateList primaryColor = getPrimaryColor(context, loader);
            if (primaryColor != null) {
                XposedHelpers.callMethod(item, "setTitleColorList", primaryColor);
            }
        }
        return item;
    }

    private static ColorStateList getPrimaryColor(Context context, ClassLoader loader) {
        try {
            Class<?> themeUtils = XposedHelpers.findClass("com.coui.appcompat.theme.COUIThemeUtils", loader);
            Class<?> attrClass = XposedHelpers.findClass("com.support.appcompat.R$attr", loader);
            int attrId = XposedHelpers.getStaticIntField(attrClass, "couiColorPrimary");
            return (ColorStateList) XposedHelpers.callStaticMethod(themeUtils, "getThemeAttrColorStateList", context, attrId);
        } catch (Throwable t) {
            return null;
        }
    }
}
