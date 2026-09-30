/**
 * Files for one session: row_R_sector_SS.jpg frames and panorama.jpg.
 */
package com.panorama.app.data;

import android.content.Context;
import android.os.Environment;

import java.io.File;

public final class CaptureStorage {
    private CaptureStorage() {
    }

    public static File sessionDir(Context context, long sessionId) {
        File pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File root = pictures != null ? pictures : context.getFilesDir();
        File dir = new File(root, "session_" + sessionId);
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IllegalStateException("Could not create " + dir.getAbsolutePath());
        }
        return dir;
    }

    public static File frameFile(Context context, long sessionId, int pitchRow, int sector) {
        return new File(sessionDir(context, sessionId),
                String.format("row_%d_sector_%02d.jpg", pitchRow, sector));
    }

    public static File panoramaFile(Context context, long sessionId) {
        return new File(sessionDir(context, sessionId), "panorama.jpg");
    }

    public static void deleteSessionFiles(Context context, long sessionId) {
        File pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File root = pictures != null ? pictures : context.getFilesDir();
        deleteRecursive(new File(root, "session_" + sessionId));
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }
}
