package local.jiege.hook.common;

/**
 * Makes the camera's preview LUT manager (Lut3dDataManager) build a new table on its next load. The manager
 * sets up its CombineLut context in every load that changes the table index and releases it only when the
 * mode is left, so the hooks that reset the index release the old context first; otherwise its native tables
 * (ten of about 3 MB each) stay allocated for the life of the process.
 */
public final class Lut3dInvalidate {
    private Lut3dInvalidate() {}

    /** {@code loadArgs} are the arguments of the Lut3d.load call about to run (index, size, mode). */
    public static void now(Object manager, Object[] loadArgs) throws Throwable {
        Symbols symbols = Symbols.get();
        boolean loads = loadArgs != null && loadArgs.length > 0 && loadArgs[0] instanceof Integer && (Integer) loadArgs[0] != -1;
        if (loads && symbols.has("Lut3d.release")) symbols.call("Lut3d.release", manager, false);
        // The load call returns early while the stored index and size equal its arguments; either one at -1 forces a rebuild.
        for (java.lang.reflect.Field key : symbols.<java.lang.reflect.Field>all("Lut3d.cacheKey")) key.set(manager, -1);
    }
}
