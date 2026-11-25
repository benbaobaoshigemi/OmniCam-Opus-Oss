package local.jiege.hook.common;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** A file of the module APK's assets, copied once to the hosting app's cache for APIs that need a path. */
public final class ModuleAssetFile {
    private ModuleAssetFile() {}

    public static synchronized File extract(Context context, String assetPath, String cacheName) throws Exception {
        File file = new File(context.getCacheDir(), cacheName);
        if (file.length() > 0) return file;
        try (ZipFile apk = new ZipFile(Symbols.modulePath())) {
            ZipEntry entry = apk.getEntry("assets/" + assetPath);
            File temporary = new File(file.getPath() + ".tmp");
            try (InputStream in = apk.getInputStream(entry); OutputStream out = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            }
            if (!temporary.renameTo(file)) throw new IllegalStateException("cache not written: " + file);
        }
        return file;
    }
}
