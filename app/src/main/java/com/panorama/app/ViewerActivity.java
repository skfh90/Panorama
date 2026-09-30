/**
 * Shows the finished sphere. Phone look is relative to the pose when this screen opened.
 */
package com.panorama.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.panorama.app.data.PanoramaDatabase;
import com.panorama.app.data.Session;
import com.panorama.app.sensor.HeadingTracker;
import com.panorama.app.ui.SpherePanoramaView;

public class ViewerActivity extends AppCompatActivity implements HeadingTracker.Listener {
    public static final String EXTRA_SESSION_ID = "session_id";

    private SpherePanoramaView sphere;
    private TextView hint;
    private HeadingTracker tracker;
    private boolean lookWithPhone = true;
    private boolean haveLookAnchor;
    private float anchorYaw;
    private float anchorPitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);
        long sessionId = getIntent().getLongExtra(EXTRA_SESSION_ID, -1L);
        Session session = PanoramaDatabase.get(this).getSession(sessionId);
        sphere = findViewById(R.id.sphere);
        hint = findViewById(R.id.viewer_hint);
        View lookButton = findViewById(R.id.look_button);
        findViewById(R.id.close_button).setOnClickListener(v -> finish());

        if (session == null || !session.hasPanorama()) {
            Toast.makeText(this, R.string.missing_panorama, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        tracker = new HeadingTracker((SensorManager) getSystemService(SENSOR_SERVICE), this);
        ((TextView) lookButton).setText(R.string.drag_to_look);
        hint.setText(R.string.viewer_turn);
        lookButton.setOnClickListener(v -> {
            lookWithPhone = !lookWithPhone;
            haveLookAnchor = false;
            ((TextView) lookButton).setText(lookWithPhone ? R.string.drag_to_look : R.string.look_with_phone);
            hint.setText(lookWithPhone ? R.string.viewer_turn : R.string.viewer_drag);
            if (lookWithPhone) {
                tracker.start();
            } else {
                tracker.stop();
                sphere.setDeviceLook(0f, 0f);
            }
        });

        String path = session.panoramaPath;
        ((PanoramaApp) getApplication()).io().execute(() -> {
            Bitmap bitmap = decode(path);
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    if (bitmap != null) {
                        bitmap.recycle();
                    }
                    return;
                }
                if (bitmap == null) {
                    Toast.makeText(this, R.string.missing_panorama, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                sphere.setPanorama(bitmap);
            });
        });
    }

    @Override
    protected void onPause() {
        if (sphere != null) {
            sphere.onPause();
        }
        if (tracker != null) {
            tracker.stop();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sphere != null) {
            sphere.onResume();
        }
        if (lookWithPhone && tracker != null) {
            tracker.start();
        }
    }

    @Override
    public void onHeading(float azimuthDegrees, float pitchDegrees, float rollDegrees, boolean absolute) {
        if (!lookWithPhone || sphere == null) {
            return;
        }
        if (!haveLookAnchor) {
            anchorYaw = azimuthDegrees;
            anchorPitch = pitchDegrees;
            haveLookAnchor = true;
            sphere.setDeviceLook(0f, 0f);
            return;
        }
        float yaw = CaptureGeometry.signedDelta(anchorYaw, azimuthDegrees);
        float lookPitch = pitchDegrees - anchorPitch;
        sphere.setDeviceLook(yaw, lookPitch);
    }

    @Override
    protected void onDestroy() {
        if (sphere != null) {
            sphere.setPanorama(null);
        }
        super.onDestroy();
    }

    private static Bitmap decode(String path) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int sample = 1;
        int edge = Math.max(bounds.outWidth, bounds.outHeight);
        while (edge / sample > 2048 && sample < 16) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(path, options);
    }
}
