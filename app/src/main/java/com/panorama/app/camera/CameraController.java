/**
 * Back camera preview and still capture. JPEGs are saved upright. Portrait preview is not given an extra quarter-turn.
 */
package com.panorama.app.camera;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class CameraController {
    public interface Callback {
        void onCameraOpened();

        void onCameraError(String message);

        void onPhoto(Bitmap bitmap);
    }

    private final Activity activity;
    private final TextureView textureView;
    private final Callback callback;

    private HandlerThread thread;
    private Handler handler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader imageReader;
    private Size previewSize;
    private int sensorOrientation;
    private String cameraId;
    private boolean busy;
    private boolean opening;

    private final TextureView.SurfaceTextureListener surfaceListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    openCamera(width, height);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                    configureTransform(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                }
            };

    public CameraController(Activity activity, TextureView textureView, Callback callback) {
        this.activity = activity;
        this.textureView = textureView;
        this.callback = callback;
    }

    public void start() {
        startThread();
        if (textureView.isAvailable()) {
            openCamera(textureView.getWidth(), textureView.getHeight());
        } else {
            textureView.setSurfaceTextureListener(surfaceListener);
        }
    }

    public void stop() {
        closeCamera();
        stopThread();
    }

    public boolean isReady() {
        return session != null && !busy;
    }

    public void takePicture() {
        if (session == null || camera == null || busy || handler == null) {
            callback.onCameraError("Camera is not ready yet.");
            return;
        }
        busy = true;
        handler.post(this::captureStill);
    }

    private void startThread() {
        if (thread != null) {
            return;
        }
        thread = new HandlerThread("panorama-camera");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    private void stopThread() {
        if (thread == null) {
            return;
        }
        thread.quitSafely();
        try {
            thread.join(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        thread = null;
        handler = null;
    }

    private void openCamera(int width, int height) {
        if (opening || camera != null || handler == null) {
            return;
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            callback.onCameraError("Camera permission is required.");
            return;
        }
        opening = true;
        handler.post(() -> openCameraOnBackground(width, height));
    }

    private void openCameraOnBackground(int viewWidth, int viewHeight) {
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = backCameraId(manager);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation == null ? 90 : orientation;
            android.hardware.camera2.params.StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                throw new IllegalStateException("No stream map");
            }
            Size[] previewSizes = map.getOutputSizes(SurfaceTexture.class);
            Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
            if (previewSizes == null || previewSizes.length == 0 || jpegSizes == null || jpegSizes.length == 0) {
                throw new IllegalStateException("No capture sizes");
            }
            previewSize = chooseSize(previewSizes, Math.max(viewWidth, viewHeight), 1920);
            Size jpegSize = chooseSize(jpegSizes, 1920, 1920);
            imageReader = ImageReader.newInstance(
                    jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(this::onImage, handler);
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice device) {
                    camera = device;
                    createSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice device) {
                    device.close();
                    camera = null;
                    opening = false;
                }

                @Override
                public void onError(@NonNull CameraDevice device, int error) {
                    device.close();
                    camera = null;
                    opening = false;
                    activity.runOnUiThread(() -> callback.onCameraError("Camera error " + error));
                }
            }, handler);
        } catch (CameraAccessException | SecurityException | IllegalStateException | NullPointerException error) {
            opening = false;
            activity.runOnUiThread(() -> callback.onCameraError("Could not open the camera."));
        }
    }

    private void createSession() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null || camera == null || previewSize == null) {
                opening = false;
                return;
            }
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface preview = new Surface(texture);
            camera.createCaptureSession(Arrays.asList(preview, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession captureSession) {
                            session = captureSession;
                            try {
                                CaptureRequest.Builder request =
                                        camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                request.addTarget(preview);
                                request.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                request.set(CaptureRequest.CONTROL_AE_MODE,
                                        CaptureRequest.CONTROL_AE_MODE_ON);
                                session.setRepeatingRequest(request.build(), null, handler);
                                opening = false;
                                activity.runOnUiThread(() -> {
                                    configureTransform(textureView.getWidth(), textureView.getHeight());
                                    callback.onCameraOpened();
                                });
                            } catch (CameraAccessException | IllegalStateException error) {
                                opening = false;
                                activity.runOnUiThread(() -> callback.onCameraError("Could not start preview."));
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession captureSession) {
                            opening = false;
                            activity.runOnUiThread(() -> callback.onCameraError("Could not configure the camera."));
                        }
                    }, handler);
        } catch (CameraAccessException error) {
            opening = false;
            activity.runOnUiThread(() -> callback.onCameraError("Could not configure the camera."));
        }
    }

    private void captureStill() {
        try {
            if (camera == null || session == null || imageReader == null) {
                busy = false;
                return;
            }
            CaptureRequest.Builder request =
                    camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            request.addTarget(imageReader.getSurface());
            request.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            request.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
            session.capture(request.build(), null, handler);
        } catch (CameraAccessException | IllegalStateException error) {
            busy = false;
            activity.runOnUiThread(() -> callback.onCameraError("Could not take the photo."));
        }
    }

    private void onImage(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireNextImage();
            if (image == null) {
                return;
            }
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            Bitmap bitmap = decodeUpright(bytes);
            if (bitmap != null) {
                Bitmap limited = limitEdge(bitmap, 1280);
                activity.runOnUiThread(() -> callback.onPhoto(limited));
            } else {
                activity.runOnUiThread(() -> callback.onCameraError("Could not read the photo."));
            }
        } catch (RuntimeException error) {
            activity.runOnUiThread(() -> callback.onCameraError("Could not read the photo."));
        } finally {
            if (image != null) {
                image.close();
            }
            busy = false;
        }
    }

    private void closeCamera() {
        if (handler == null) {
            return;
        }
        CountDownLatch latch = new CountDownLatch(1);
        handler.post(() -> {
            try {
                if (session != null) {
                    session.close();
                    session = null;
                }
                if (camera != null) {
                    camera.close();
                    camera = null;
                }
                if (imageReader != null) {
                    imageReader.close();
                    imageReader = null;
                }
            } finally {
                opening = false;
                busy = false;
                latch.countDown();
            }
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (previewSize == null || viewWidth == 0 || viewHeight == 0) {
            return;
        }
        // The activity is locked to portrait, so the display rotation is 0.
        // TextureView already shows this phone's preview buffer upright in that
        // orientation. Swapping the buffer sides and then rotating 270° turned
        // the live image another quarter-turn onto its side.
        // Still photos stay upright separately, via JPEG_ORIENTATION and EXIF.
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        boolean landscapeDisplay = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        float bufferWidth = landscapeDisplay ? previewSize.getHeight() : previewSize.getWidth();
        float bufferHeight = landscapeDisplay ? previewSize.getWidth() : previewSize.getHeight();
        RectF bufferRect = new RectF(0, 0, bufferWidth, bufferHeight);
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
        float scale = Math.max(viewHeight / bufferHeight, viewWidth / bufferWidth);
        matrix.postScale(scale, scale, centerX, centerY);
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            // Official Camera2 path: only rotate when the display itself is landscape.
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (rotation == Surface.ROTATION_180) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    private int jpegOrientation() {
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        int degrees;
        switch (rotation) {
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
            case Surface.ROTATION_0:
            default:
                degrees = 0;
                break;
        }
        return (sensorOrientation - degrees + 360) % 360;
    }

    private static String backCameraId(CameraManager manager) throws CameraAccessException {
        String fallback = null;
        for (String id : manager.getCameraIdList()) {
            fallback = id;
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        if (fallback == null) {
            throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "No camera");
        }
        return fallback;
    }

    private static Size chooseSize(Size[] choices, int targetLongEdge, int maxLongEdge) {
        Size best = null;
        Size smallest = choices[0];
        long bestPenalty = Long.MAX_VALUE;
        for (Size size : choices) {
            int longEdge = Math.max(size.getWidth(), size.getHeight());
            int smallestEdge = Math.max(smallest.getWidth(), smallest.getHeight());
            if (longEdge < smallestEdge) {
                smallest = size;
            }
            if (longEdge > maxLongEdge) {
                continue;
            }
            long penalty = Math.abs(longEdge - targetLongEdge);
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                best = size;
            }
        }
        return best != null ? best : smallest;
    }

    private static Bitmap decodeUpright(byte[] jpeg) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bitmap == null) {
            return null;
        }
        int degrees = 0;
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(jpeg));
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) {
                degrees = 90;
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) {
                degrees = 180;
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) {
                degrees = 270;
            }
        } catch (IOException ignored) {
            degrees = 0;
        }
        if (degrees == 0) {
            return bitmap;
        }
        Matrix matrix = new Matrix();
        matrix.postRotate(degrees);
        Bitmap rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (rotated != bitmap) {
            bitmap.recycle();
        }
        return rotated;
    }

    static Bitmap limitEdge(Bitmap source, int maxEdge) {
        int longEdge = Math.max(source.getWidth(), source.getHeight());
        if (longEdge <= maxEdge) {
            return source;
        }
        float scale = maxEdge / (float) longEdge;
        int width = Math.max(1, Math.round(source.getWidth() * scale));
        int height = Math.max(1, Math.round(source.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(source, width, height, true);
        if (scaled != source) {
            source.recycle();
        }
        return scaled;
    }
}
