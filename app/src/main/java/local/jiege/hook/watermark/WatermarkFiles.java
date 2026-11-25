package local.jiege.hook.watermark;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import local.jiege.hook.common.Log;

/** Retains resources while the camera cleans the native photo-watermark directory. */
final class WatermarkFiles {
    private static final String TAG = "Watermark";
    static final String PHOTO_DIR = "/data/user/0/com.oplus.camera/files/photo_watermark";
    private static final String BACKUP_DIR = "/data/user/0/com.oplus.camera/files/jiege/wm_backup";

    private WatermarkFiles() {}

    static File styleJson(String dir, String style) {
        return new File(dir + "/" + style + "/" + style + ".json");
    }

    /** Keeps a copy of the style's resource folder where the camera's cleanup does not reach. */
    static void backupFiles(String style) {
        if (style.isEmpty() || !styleJson(PHOTO_DIR, style).isFile()) return;
        try {
            copyTree(new File(PHOTO_DIR, style).toPath(), new File(BACKUP_DIR, style).toPath());
        } catch (IOException error) {
            Log.e(TAG, "style backup failed " + style, error);
        }
    }

    /** True if the style's files are in place; a missing folder is copied back from the backup. */
    static boolean ensureFiles(String style) {
        if (styleJson(PHOTO_DIR, style).isFile()) return true;
        if (!styleJson(BACKUP_DIR, style).isFile()) return false;
        try {
            copyTree(new File(BACKUP_DIR, style).toPath(), new File(PHOTO_DIR, style).toPath());
        } catch (IOException error) {
            Log.e(TAG, "style restore failed " + style, error);
            return false;
        }
        return styleJson(PHOTO_DIR, style).isFile();
    }

    private static void copyTree(final Path from, final Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
