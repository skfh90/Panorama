/**
 * Reads the rotation vector. Azimuth 0 is north when the compass is available.
 */
package com.panorama.app.sensor;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

import com.panorama.app.CaptureGeometry;

/**
 * Heading of the back camera while the phone is held upright.
 * Azimuth 0 is north when the compass rotation vector is available.
 * Turning right increases the angle.
 */
public final class HeadingTracker implements SensorEventListener {
    public interface Listener {
        void onHeading(float azimuthDegrees, float pitchDegrees, float rollDegrees, boolean absolute);
    }

    private final SensorManager sensors;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float[] rotation = new float[9];

    private Sensor sensor;
    private boolean absolute;
    private boolean originSet;
    private float origin;
    private float smoothedAzimuth = Float.NaN;
    private float smoothedPitch;
    private boolean registered;

    public HeadingTracker(SensorManager sensors, Listener listener) {
        this.sensors = sensors;
        this.listener = listener;
        Sensor rotationVector = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rotationVector != null) {
            sensor = rotationVector;
            absolute = true;
        } else {
            sensor = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            absolute = false;
        }
    }

    public boolean hasSensor() {
        return sensor != null;
    }

    public boolean isAbsolute() {
        return absolute;
    }

    public void start() {
        if (sensor == null || registered) {
            return;
        }
        sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler);
        registered = true;
    }

    public void stop() {
        if (!registered) {
            return;
        }
        sensors.unregisterListener(this);
        registered = false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int length = Math.min(event.values.length, 4);
        if (length < 3) {
            return;
        }
        float[] vector = new float[length];
        System.arraycopy(event.values, 0, vector, 0, length);
        SensorManager.getRotationMatrixFromVector(rotation, vector);

        float east = -rotation[2];
        float north = -rotation[5];
        float up = -rotation[8];
        float azimuth = (float) Math.toDegrees(Math.atan2(east, north));
        float horizontal = (float) Math.hypot(east, north);
        float pitch = (float) Math.toDegrees(Math.atan2(up, horizontal));
        float roll = (float) Math.toDegrees(Math.atan2(rotation[6], rotation[7]));

        if (!absolute) {
            if (!originSet) {
                origin = azimuth;
                originSet = true;
            }
            azimuth = CaptureGeometry.wrap(azimuth - origin);
        } else {
            azimuth = CaptureGeometry.wrap(azimuth);
        }

        if (Float.isNaN(smoothedAzimuth)) {
            smoothedAzimuth = azimuth;
            smoothedPitch = pitch;
        } else {
            smoothedAzimuth = CaptureGeometry.wrap(
                    smoothedAzimuth + 0.25f * CaptureGeometry.signedDelta(smoothedAzimuth, azimuth));
            smoothedPitch = smoothedPitch + 0.25f * (pitch - smoothedPitch);
        }
        listener.onHeading(smoothedAzimuth, smoothedPitch, roll, absolute);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
