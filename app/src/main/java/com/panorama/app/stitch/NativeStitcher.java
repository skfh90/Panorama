/**
 * JNI entry to libpanorama_stitch.so.
 */
package com.panorama.app.stitch;

public final class NativeStitcher {
    public static final int OK = 0;
    public static final int ERR_NEED_MORE_IMGS = 1;
    public static final int ERR_HOMOGRAPHY_EST_FAIL = 2;
    public static final int ERR_CAMERA_PARAMS_ADJUST_FAIL = 3;

    private static boolean loaded;

    private NativeStitcher() {
    }

    public static synchronized void ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("panorama_stitch");
            loaded = true;
        }
    }

    public static native int stitch(String[] imagePaths, float[] azimuthDegrees, float[] pitchDegrees, String outputPath);
}
