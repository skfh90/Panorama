/**
 * Small dot matrix. Top row is up, middle is level, bottom is down.
 */
package com.panorama.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.panorama.app.CaptureGeometry;

/** Dot matrix for a full sphere. Top row looks up, middle is level, bottom looks down. */
public class CaptureGridView extends View {
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean[][] captured = new boolean[CaptureGeometry.PITCH_ROWS][CaptureGeometry.SECTOR_COUNT];
    private final int[][] capturedColor = new int[CaptureGeometry.PITCH_ROWS][CaptureGeometry.SECTOR_COUNT];
    private final String[] rowLabels = new String[CaptureGeometry.PITCH_ROWS];

    private float azimuth;
    private float pitch;
    private int aimedSector = -1;
    private int aimedRow = 1;
    private boolean ready;
    private boolean absoluteNorth = true;

    public CaptureGridView(Context context) {
        super(context);
        init();
    }

    public CaptureGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public CaptureGridView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(2));
        labelPaint.setColor(0xFFB7C3CE);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(dp(10));
        rowLabels[0] = "Up";
        rowLabels[1] = "Level";
        rowLabels[2] = "Down";
    }

    public void setAbsoluteNorth(boolean absoluteNorth) {
        this.absoluteNorth = absoluteNorth;
        invalidate();
    }

    public void setThumbnail(int row, int sector, Bitmap bitmap) {
        if (row < 0 || row >= captured.length || sector < 0 || sector >= captured[row].length) {
            return;
        }
        captured[row][sector] = bitmap != null;
        capturedColor[row][sector] = bitmap == null ? 0 : averageColor(bitmap);
        invalidate();
    }

    public void setAim(float azimuthDegrees, float pitchDegrees, int sector, int row, boolean captureReady) {
        azimuth = CaptureGeometry.wrap(azimuthDegrees);
        pitch = pitchDegrees;
        aimedSector = sector;
        aimedRow = row;
        ready = captureReady;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float labelHeight = dp(14);
        float top = dp(4);
        float left = dp(36);
        float gridBottom = getHeight() - labelHeight;
        float colWidth = (getWidth() - left) / (float) CaptureGeometry.SECTOR_COUNT;
        float rowHeight = (gridBottom - top) / CaptureGeometry.PITCH_ROWS;
        if (colWidth <= 0 || rowHeight <= 0) {
            return;
        }
        float radius = Math.min(colWidth, rowHeight) * 0.32f;
        labelPaint.setTextAlign(Paint.Align.LEFT);

        for (int row = 0; row < CaptureGeometry.PITCH_ROWS; row++) {
            float cy = top + (row + 0.5f) * rowHeight;
            canvas.drawText(rowLabels[row], dp(4), cy + dp(3), labelPaint);
            for (int sector = 0; sector < CaptureGeometry.SECTOR_COUNT; sector++) {
                float center = CaptureGeometry.sectorCenter(sector);
                float turn = Math.abs(CaptureGeometry.signedDelta(azimuth, center));
                boolean inView = row == aimedRow && turn <= CaptureGeometry.HORIZONTAL_FOV / 2f;
                boolean aimed = row == aimedRow && sector == aimedSector;
                float cx = left + (sector + 0.5f) * colWidth;
                drawDot(canvas, cx, cy, radius, row, sector, inView, aimed);
            }
        }

        labelPaint.setTextAlign(Paint.Align.CENTER);
        float northX = left + 0.5f * colWidth;
        canvas.drawText(absoluteNorth ? "N" : "1", northX, getHeight() - dp(2), labelPaint);

        float markerX = left + azimuth / 360f * (getWidth() - left);
        float markerY = pitchToY(pitch, top, rowHeight);
        dotPaint.setColor(0xFFF2F5F7);
        canvas.drawCircle(markerX, markerY, radius * 0.38f, dotPaint);
    }

    private void drawDot(Canvas canvas, float cx, float cy, float radius, int row, int sector,
                          boolean inView, boolean aimed) {
        if (captured[row][sector]) {
            int color = capturedColor[row][sector];
            dotPaint.setColor(color != 0 ? color : 0xFF3DDC97);
            canvas.drawCircle(cx, cy, radius, dotPaint);
        } else if (aimed && ready) {
            dotPaint.setColor(0xFF3DDC97);
            canvas.drawCircle(cx, cy, radius, dotPaint);
        } else if (inView) {
            dotPaint.setColor(0xFF5E7384);
            canvas.drawCircle(cx, cy, radius, dotPaint);
        } else {
            dotPaint.setColor(0xFF2A343E);
            canvas.drawCircle(cx, cy, radius, dotPaint);
        }
        if (aimed) {
            ringPaint.setColor(ready ? 0xFF3DDC97 : 0xFFF2F5F7);
            canvas.drawCircle(cx, cy, radius + dp(2), ringPaint);
        }
    }

    private static float pitchToY(float pitch, float top, float rowHeight) {
        float high = CaptureGeometry.PITCH_TARGETS[0];
        float low = CaptureGeometry.PITCH_TARGETS[CaptureGeometry.PITCH_ROWS - 1];
        float span = high - low;
        float t = span == 0f ? 0.5f : (high - pitch) / span;
        if (t < -0.2f) {
            t = -0.2f;
        } else if (t > 1.2f) {
            t = 1.2f;
        }
        float first = top + 0.5f * rowHeight;
        float last = top + (CaptureGeometry.PITCH_ROWS - 0.5f) * rowHeight;
        return first + t * (last - first);
    }

    private static int averageColor(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) {
            return 0xFF3DDC97;
        }
        long red = 0;
        long green = 0;
        long blue = 0;
        int count = 0;
        int stepX = Math.max(1, width / 6);
        int stepY = Math.max(1, height / 6);
        for (int y = stepY / 2; y < height; y += stepY) {
            for (int x = stepX / 2; x < width; x += stepX) {
                int pixel = bitmap.getPixel(x, y);
                red += (pixel >> 16) & 0xFF;
                green += (pixel >> 8) & 0xFF;
                blue += pixel & 0xFF;
                count++;
            }
        }
        if (count == 0) {
            return 0xFF3DDC97;
        }
        return 0xFF000000
                | ((int) (red / count) << 16)
                | ((int) (green / count) << 8)
                | (int) (blue / count);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
