package local.jiege.settings;

/**
 * Interface between the settings app and the camera (MasterFilterSync). The configuration and the
 * imported LUTs are files in the module's LSPosed preferences directory (MasterFilterStore), read
 * by the camera directly. The camera's master list comes back with the camera's state
 * (DiagnosticsContract.KEY_MASTER_STOCK). The provider (MasterFilterProvider; only the camera and
 * the module may use it) serves:
 *
 *   call(METHOD_CONFIG) -> Bundle { KEY_TEXT: MasterConfig text } (diagnostics)
 *
 * Camera list text: the user's custom presets, then the camera's master (Pro) filter list as laid
 * out, one entity per line. A preset is "custom_<n>\t<preset name>\t<filter type>"; a filter is
 * "<filter id>\t<display name>", the name being the camera's for stock filters and empty for the
 * module's (the settings app names those from FilterCore).
 */
public final class MasterFilterContract {
    public static final String AUTHORITY = "local.jiege.camera.masterfilters";
    public static final String METHOD_CONFIG = "config";
    public static final String KEY_TEXT = "text";
    public static final String CAMERA_PACKAGE = "com.oplus.camera";
    public static final String MODULE_PACKAGE = "local.jiege.camera";

    private MasterFilterContract() {}

    public static String uri() {
        return "content://" + AUTHORITY;
    }
}
