/**
 * Application object. Holds the single background thread used for disk and stitching.
 */
package com.panorama.app;

import android.app.Application;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PanoramaApp extends Application {
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    public ExecutorService io() {
        return io;
    }
}
