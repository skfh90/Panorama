/**
 * Older flat strip viewer. The sphere viewer replaced it on the viewer screen.
 */
package com.panorama.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/** Horizontally wrapping viewer for a stitched panorama. */
public class PanoramaStripView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF dest = new RectF();
    private Bitmap bitmap;
    private float offset;
    private float lastX;
    private boolean dragging;

    public PanoramaStripView(Context context) {
        super(context);
    }

    public PanoramaStripView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PanoramaStripView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setBitmap(Bitmap bitmap) {
        this.bitmap = bitmap;
        offset = 0f;
        invalidate();
    }

    public Bitmap getBitmap() {
        return bitmap;
    }

    /** Positive degrees look to the right. */
    public void addLookDegrees(float deltaDegrees) {
        float width = displayedWidth();
        if (width <= 0f) {
            return;
        }
        offset += deltaDegrees / 360f * width;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) {
            return;
        }
        float destWidth = displayedWidth();
        float destHeight = getHeight();
        float start = -modulo(offset, destWidth);
        for (float x = start; x < getWidth(); x += destWidth) {
            dest.set(x, 0, x + destWidth, destHeight);
            canvas.drawBitmap(bitmap, null, dest, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragging = true;
                lastX = event.getX();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) {
                    return false;
                }
                float dx = event.getX() - lastX;
                lastX = event.getX();
                offset -= dx;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private float displayedWidth() {
        if (bitmap == null || bitmap.getHeight() == 0 || getHeight() == 0) {
            return 0f;
        }
        return bitmap.getWidth() * (getHeight() / (float) bitmap.getHeight());
    }

    private static float modulo(float value, float span) {
        if (span <= 0f) {
            return 0f;
        }
        float wrapped = value % span;
        if (wrapped < 0f) {
            wrapped += span;
        }
        return wrapped;
    }
}
