/**
 * Capture screen. Follows the phone, fills the 36-dot sphere, and starts the stitch.
 */
package com.panorama.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.SensorManager;
import android.media.MediaActionSound;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.panorama.app.camera.CameraController;
import com.panorama.app.data.CaptureStorage;
import com.panorama.app.data.FrameRecord;
import com.panorama.app.data.PanoramaDatabase;
import com.panorama.app.sensor.HeadingTracker;
import com.panorama.app.stitch.PanoramaStitcher;
import com.panorama.app.ui.CaptureGridView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

public class CaptureActivity extends AppCompatActivity implements HeadingTracker.Listener, CameraController.Callback {
    public static final String EXTRA_SESSION_ID = "session_id";
    private static final int REQUEST_CAMERA = 21;
    private static final int MIN_BUILD = 6;
    private static final long STEADY_MS = 700L;
    private static final float STEADY_MOVE = 3.5f;

    private long sessionId;
    private PanoramaDatabase database;
    private PanoramaApp app;
    private HeadingTracker tracker;
    private CameraController camera;
    private CaptureGridView grid;
    private TextView statusView;
    private TextView gridHint;
    private Button captureButton;
    private Button buildButton;
    private View progress;

    private final boolean[][] filled = new boolean[CaptureGeometry.PITCH_ROWS][CaptureGeometry.SECTOR_COUNT];
    private final MediaActionSound shutter = new MediaActionSound();

    private float azimuth;
    private float pitch;
    private float roll;
    private boolean headingReady;
    private boolean absoluteHeading;
    private boolean cameraReady;
    private boolean saving;
    private boolean stitching;
    private int latchedSpot = -1;
    private boolean steadyAnchorSet;
    private float steadyAnchor;
    private float steadyPitch;
    private long steadySince;
    private long messageUntil;
    private Pending pending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_capture);
        sessionId = getIntent().getLongExtra(EXTRA_SESSION_ID, -1L);
        database = PanoramaDatabase.get(this);
        app = (PanoramaApp) getApplication();
        if (database.getSession(sessionId) == null) {
            finish();
            return;
        }

        grid = findViewById(R.id.grid);
        statusView = findViewById(R.id.status);
        gridHint = findViewById(R.id.grid_hint);
        captureButton = findViewById(R.id.capture_button);
        buildButton = findViewById(R.id.build_button);
        progress = findViewById(R.id.progress_group);

        tracker = new HeadingTracker(
                (SensorManager) getSystemService(SENSOR_SERVICE), this);
        camera = new CameraController(this, (TextureView) findViewById(R.id.preview), this);
        shutter.load(MediaActionSound.SHUTTER_CLICK);

        captureButton.setOnClickListener(v -> captureAimedSpot(false));
        buildButton.setOnClickListener(v -> buildPanorama());
        findViewById(R.id.close_button).setOnClickListener(v -> finish());

        if (!tracker.hasSensor()) {
            statusView.setText(R.string.no_rotation_sensor);
            captureButton.setEnabled(false);
        }
        loadFrames();
    }

    @Override
    protected void onResume() {
        super.onResume();
        tracker.start();
        if (hasCameraPermission()) {
            camera.start();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    @Override
    protected void onPause() {
        camera.stop();
        cameraReady = false;
        tracker.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        shutter.release();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_CAMERA && hasCameraPermission()) {
            camera.start();
        } else if (requestCode == REQUEST_CAMERA) {
            Toast.makeText(this, R.string.camera_required, Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    public void onHeading(float azimuthDegrees, float pitchDegrees, float rollDegrees, boolean absolute) {
        azimuth = azimuthDegrees;
        pitch = pitchDegrees;
        roll = rollDegrees;
        headingReady = true;
        absoluteHeading = absolute;
        grid.setAbsoluteNorth(absolute);
        updateSteady();
        int sector = CaptureGeometry.sectorOf(azimuth);
        int row = CaptureGeometry.pitchRowOf(pitch);
        boolean ready = canAutoCapture(row, sector);
        grid.setAim(azimuth, pitch, sector, row, ready);
        if (SystemClock.elapsedRealtime() >= messageUntil) {
            statusView.setText(liveStatus(row, sector, ready));
        }
        gridHint.setText(absolute ? R.string.grid_hint_north : R.string.grid_hint_relative);
        if (ready) {
            captureAimedSpot(true);
        }
    }

    @Override
    public void onCameraOpened() {
        cameraReady = true;
        updateBuildButton();
    }

    @Override
    public void onCameraError(String message) {
        saving = false;
        pending = null;
        latchedSpot = -1;
        statusView.setText(message);
        updateBuildButton();
    }

    @Override
    public void onPhoto(Bitmap bitmap) {
        Pending shot = pending;
        pending = null;
        if (shot == null || bitmap == null) {
            saving = false;
            return;
        }
        shutter.play(MediaActionSound.SHUTTER_CLICK);
        app.io().execute(() -> {
            try {
                File file = CaptureStorage.frameFile(this, sessionId, shot.row, shot.sector);
                writeJpeg(bitmap, file);
                Bitmap thumb = scaleThumb(bitmap, 160);
                if (thumb != bitmap) {
                    bitmap.recycle();
                }
                runOnUiThread(() -> {
                    if (isDestroyed()) {
                        return;
                    }
                    database.upsertFrame(sessionId, shot.row, shot.sector, shot.azimuth, shot.pitch, file.getAbsolutePath());
                    filled[shot.row][shot.sector] = true;
                    grid.setThumbnail(shot.row, shot.sector, thumb);
                    saving = false;
                    latchedSpot = shot.spot();
                    messageUntil = SystemClock.elapsedRealtime() + 1200L;
                    statusView.setText(getString(R.string.captured_spot, rowName(shot.row), shot.sector + 1));
                    updateBuildButton();
                });
            } catch (IOException error) {
                bitmap.recycle();
                runOnUiThread(() -> {
                    saving = false;
                    latchedSpot = -1;
                    statusView.setText(R.string.save_failed);
                });
            }
        });
    }

    private void loadFrames() {
        app.io().execute(() -> {
            List<FrameRecord> frames = database.listFrames(sessionId);
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                for (FrameRecord frame : frames) {
                    if (frame.pitchRow < 0 || frame.pitchRow >= filled.length) {
                        continue;
                    }
                    filled[frame.pitchRow][frame.sector] = true;
                    grid.setThumbnail(frame.pitchRow, frame.sector, decodeThumb(frame.filePath));
                }
                updateBuildButton();
            });
        });
    }

    private void captureAimedSpot(boolean fromAuto) {
        if (saving || stitching || !cameraReady || !headingReady) {
            return;
        }
        if (!rollOk()) {
            statusView.setText(R.string.hold_upright);
            messageUntil = SystemClock.elapsedRealtime() + 800L;
            return;
        }
        int sector = CaptureGeometry.sectorOf(azimuth);
        int row = CaptureGeometry.pitchRowOf(pitch);
        if (!CaptureGeometry.pitchMatches(pitch, row)) {
            statusView.setText(liveStatus(row, sector, false));
            messageUntil = SystemClock.elapsedRealtime() + 800L;
            return;
        }
        int spot = row * CaptureGeometry.SECTOR_COUNT + sector;
        if (fromAuto && (filled[row][sector] || spot == latchedSpot || !isSteady())) {
            return;
        }
        saving = true;
        pending = new Pending(row, sector, azimuth, pitch);
        latchedSpot = spot;
        captureButton.setEnabled(false);
        camera.takePicture();
    }

    private void buildPanorama() {
        if (stitching || saving || countFilled() < MIN_BUILD) {
            statusView.setText(R.string.need_more_spots);
            return;
        }
        stitching = true;
        progress.setVisibility(View.VISIBLE);
        captureButton.setEnabled(false);
        buildButton.setEnabled(false);
        statusView.setText(R.string.building);
        app.io().execute(() -> {
            PanoramaStitcher.Result result = PanoramaStitcher.stitch(getApplicationContext(), sessionId);
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    return;
                }
                stitching = false;
                progress.setVisibility(View.GONE);
                updateBuildButton();
                statusView.setText(result.message);
                if (result.ok) {
                    Intent intent = new Intent(this, ViewerActivity.class);
                    intent.putExtra(ViewerActivity.EXTRA_SESSION_ID, sessionId);
                    startActivity(intent);
                }
            });
        });
    }

    private String liveStatus(int row, int sector, boolean ready) {
        int filledCount = countFilled();
        if (!rollOk()) {
            return getString(R.string.hold_upright);
        }
        if (filledCount == CaptureGeometry.SPOT_COUNT) {
            return getString(R.string.all_spots_filled);
        }
        if (!CaptureGeometry.pitchMatches(pitch, row)) {
            String way = pitch < CaptureGeometry.PITCH_TARGETS[row]
                    ? getString(R.string.tilt_up)
                    : getString(R.string.tilt_down);
            return getString(R.string.tilt_toward, way, rowName(row), filledCount, CaptureGeometry.SPOT_COUNT);
        }
        if (!filled[row][sector]) {
            if (ready) {
                return getString(R.string.hold_steady, rowName(row), sector + 1, filledCount, CaptureGeometry.SPOT_COUNT);
            }
            return getString(R.string.spot_in_view, rowName(row), sector + 1, filledCount, CaptureGeometry.SPOT_COUNT);
        }
        int[] next = CaptureGeometry.nearestEmpty(azimuth, pitch, filled);
        if (next == null) {
            return getString(R.string.all_spots_filled);
        }
        if (next[0] != row) {
            String way = CaptureGeometry.PITCH_TARGETS[next[0]] > pitch
                    ? getString(R.string.tilt_up)
                    : getString(R.string.tilt_down);
            return getString(R.string.tilt_toward, way, rowName(next[0]), filledCount, CaptureGeometry.SPOT_COUNT);
        }
        float delta = CaptureGeometry.signedDelta(azimuth, CaptureGeometry.sectorCenter(next[1]));
        String turn = delta >= 0f ? getString(R.string.turn_right) : getString(R.string.turn_left);
        return getString(R.string.turn_to_spot, turn, rowName(next[0]), next[1] + 1,
                filledCount, CaptureGeometry.SPOT_COUNT);
    }

    private boolean canAutoCapture(int row, int sector) {
        int spot = row * CaptureGeometry.SECTOR_COUNT + sector;
        return cameraReady
                && headingReady
                && !saving
                && !stitching
                && !filled[row][sector]
                && spot != latchedSpot
                && CaptureGeometry.pitchMatches(pitch, row)
                && rollOk()
                && isSteady();
    }

    private boolean rollOk() {
        return Math.abs(roll) <= CaptureGeometry.MAX_ROLL;
    }

    private String rowName(int row) {
        if (row <= 0) {
            return getString(R.string.row_up);
        }
        if (row >= CaptureGeometry.PITCH_ROWS - 1) {
            return getString(R.string.row_down);
        }
        return getString(R.string.row_level);
    }

    private void updateSteady() {
        long now = SystemClock.elapsedRealtime();
        if (!steadyAnchorSet) {
            steadyAnchor = azimuth;
            steadyPitch = pitch;
            steadySince = now;
            steadyAnchorSet = true;
            return;
        }
        if (Math.abs(CaptureGeometry.signedDelta(steadyAnchor, azimuth)) > STEADY_MOVE
                || Math.abs(pitch - steadyPitch) > STEADY_MOVE) {
            steadyAnchor = azimuth;
            steadyPitch = pitch;
            steadySince = now;
        }
    }

    private boolean isSteady() {
        return steadyAnchorSet && SystemClock.elapsedRealtime() - steadySince >= STEADY_MS;
    }

    private int countFilled() {
        int count = 0;
        for (boolean[] row : filled) {
            for (boolean spot : row) {
                if (spot) {
                    count++;
                }
            }
        }
        return count;
    }

    private void updateBuildButton() {
        int count = countFilled();
        buildButton.setText(getString(R.string.build_count, count, CaptureGeometry.SPOT_COUNT));
        buildButton.setEnabled(!stitching && !saving && count >= MIN_BUILD);
        captureButton.setEnabled(!stitching && !saving && tracker.hasSensor());
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static void writeJpeg(Bitmap bitmap, File file) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)) {
                throw new IOException("compress");
            }
        }
    }

    private static Bitmap scaleThumb(Bitmap source, int width) {
        int height = Math.max(1, source.getHeight() * width / Math.max(1, source.getWidth()));
        return Bitmap.createScaledBitmap(source, width, height, true);
    }

    private static Bitmap decodeThumb(String path) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int sample = 1;
        while (bounds.outWidth / sample > 240 && sample < 32) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeFile(path, options);
    }

    private static final class Pending {
        final int row;
        final int sector;
        final float azimuth;
        final float pitch;

        Pending(int row, int sector, float azimuth, float pitch) {
            this.row = row;
            this.sector = sector;
            this.azimuth = azimuth;
            this.pitch = pitch;
        }

        int spot() {
            return row * CaptureGeometry.SECTOR_COUNT + sector;
        }
    }
}
