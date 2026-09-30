/**
 * Loads frame paths and headings, calls native code, and records success in SQLite.
 */
package com.panorama.app.stitch;

import android.content.Context;

import com.panorama.app.data.CaptureStorage;
import com.panorama.app.data.FrameRecord;
import com.panorama.app.data.PanoramaDatabase;

import java.io.File;
import java.util.List;

public final class PanoramaStitcher {
    public static final class Result {
        public final boolean ok;
        public final String message;
        public final String path;

        private Result(boolean ok, String message, String path) {
            this.ok = ok;
            this.message = message;
            this.path = path;
        }
    }

    private PanoramaStitcher() {
    }

    public static Result stitch(Context context, long sessionId) {
        PanoramaDatabase database = PanoramaDatabase.get(context);
        List<FrameRecord> frames = database.listFrames(sessionId);
        if (frames.size() < 2) {
            return new Result(false, messageFor(NativeStitcher.ERR_NEED_MORE_IMGS), null);
        }
        String[] paths = new String[frames.size()];
        float[] azimuths = new float[frames.size()];
        float[] pitches = new float[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            FrameRecord frame = frames.get(i);
            paths[i] = frame.filePath;
            azimuths[i] = frame.azimuth;
            pitches[i] = frame.pitch;
        }
        File output = CaptureStorage.panoramaFile(context, sessionId);
        try {
            NativeStitcher.ensureLoaded();
            int code = NativeStitcher.stitch(paths, azimuths, pitches, output.getAbsolutePath());
            if (code == NativeStitcher.OK && output.isFile()) {
                database.markStitched(sessionId, output.getAbsolutePath());
                return new Result(true, "Sphere picture is ready.", output.getAbsolutePath());
            }
            database.markFailed(sessionId);
            if (output.exists() && code != NativeStitcher.OK) {
                output.delete();
            }
            return new Result(false, messageFor(code), null);
        } catch (UnsatisfiedLinkError | RuntimeException error) {
            database.markFailed(sessionId);
            String detail = error.getMessage() == null ? "Stitching library failed to load." : error.getMessage();
            return new Result(false, detail, null);
        }
    }

    public static String messageFor(int code) {
        switch (code) {
            case NativeStitcher.OK:
                return "Sphere picture is ready.";
            case NativeStitcher.ERR_NEED_MORE_IMGS:
                return "Need more overlapping photos. Fill neighboring spots before building.";
            case NativeStitcher.ERR_HOMOGRAPHY_EST_FAIL:
                return "Could not match the photos. Turn more slowly and recapture the gaps.";
            case NativeStitcher.ERR_CAMERA_PARAMS_ADJUST_FAIL:
                return "Could not align the photos. Hold the phone upright and recapture.";
            case -1:
                return "Could not read the captured photos.";
            case -3:
                return "Could not save the panorama.";
            default:
                return "Stitching failed (" + code + ").";
        }
    }
}
