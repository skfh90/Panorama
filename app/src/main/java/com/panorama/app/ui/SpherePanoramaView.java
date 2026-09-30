/**
 * OpenGL view from inside the stitched picture. Drag adds an offset on top of phone look.
 */
package com.panorama.app.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.util.AttributeSet;
import android.view.MotionEvent;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Inside-out view of an equirectangular stitch. Drag to look around.
 * Phone yaw and pitch are added on top of the drag offset.
 */
public class SpherePanoramaView extends GLSurfaceView {
    private static final int STACKS = 32;
    private static final int SLICES = 64;

    private final SphereRenderer renderer = new SphereRenderer();
    private float deviceYaw;
    private float devicePitch;
    private float touchYaw;
    private float touchPitch;
    private float lastX;
    private float lastY;
    private boolean dragging;
    private volatile float renderYaw;
    private volatile float renderPitch = 0f;
    private volatile float latitudeSpan = 1f;

    public SpherePanoramaView(Context context) {
        super(context);
        init();
    }

    public SpherePanoramaView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setEGLContextClientVersion(2);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = event.getX();
                    lastY = event.getY();
                    dragging = true;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    lastX = event.getX();
                    lastY = event.getY();
                    touchYaw -= dx * 0.15f;
                    touchPitch = clamp(touchPitch - dy * 0.12f, -80f, 80f);
                    publishLook();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    return true;
                default:
                    return false;
            }
        });
    }

    public void setPanorama(Bitmap bitmap) {
        latitudeSpan = 1f;
        queueEvent(() -> renderer.setBitmap(bitmap));
    }

    public void setDeviceLook(float yawDegrees, float pitchDegrees) {
        if (dragging) {
            return;
        }
        deviceYaw = yawDegrees;
        devicePitch = pitchDegrees;
        publishLook();
    }

    private void publishLook() {
        renderYaw = deviceYaw + touchYaw;
        renderPitch = clamp(devicePitch + touchPitch, -85f, 85f);
        requestRender();
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private class SphereRenderer implements Renderer {
        private final float[] projection = new float[16];
        private final float[] view = new float[16];
        private final float[] mvp = new float[16];
        private FloatBuffer vertices;
        private ShortBuffer indices;
        private int indexCount;
        private int arrayBuffer;
        private int indexBuffer;
        private int program;
        private int aPosition;
        private int aTexCoord;
        private int uMvp;
        private int uTex;
        private int uV0;
        private int uV1;
        private int textureId;
        private Bitmap pendingBitmap;
        private boolean textureDirty;
        private int uploadAttempts;

        void setBitmap(Bitmap bitmap) {
            if (pendingBitmap != null && pendingBitmap != bitmap && !pendingBitmap.isRecycled()) {
                pendingBitmap.recycle();
            }
            pendingBitmap = bitmap;
            textureDirty = true;
        }

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            GLES20.glClearColor(0.04f, 0.05f, 0.06f, 1f);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glDisable(GLES20.GL_CULL_FACE);
            buildSphere();
            program = buildProgram(VERTEX, FRAGMENT);
            if (program != 0) {
                aPosition = GLES20.glGetAttribLocation(program, "aPosition");
                aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
                uMvp = GLES20.glGetUniformLocation(program, "uMvp");
                uTex = GLES20.glGetUniformLocation(program, "uTex");
                uV0 = GLES20.glGetUniformLocation(program, "uV0");
                uV1 = GLES20.glGetUniformLocation(program, "uV1");
            }
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            textureId = textures[0];
            textureDirty = pendingBitmap != null;
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            GLES20.glViewport(0, 0, width, Math.max(1, height));
            float aspect = width / (float) Math.max(1, height);
            Matrix.perspectiveM(projection, 0, 70f, aspect, 0.05f, 10f);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (program == 0 || arrayBuffer == 0) {
                return;
            }
            if (textureDirty) {
                uploadTexture();
            }
            if (textureId == 0) {
                return;
            }
            float span = latitudeSpan;
            float v0 = 0.5f - span / 2f;
            float v1 = 0.5f + span / 2f;

            Matrix.setIdentityM(view, 0);
            Matrix.rotateM(view, 0, -renderPitch, 1f, 0f, 0f);
            Matrix.rotateM(view, 0, -renderYaw, 0f, 1f, 0f);
            Matrix.multiplyMM(mvp, 0, projection, 0, view, 0);

            GLES20.glUseProgram(program);
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
            GLES20.glUniform1f(uV0, v0);
            GLES20.glUniform1f(uV1, v1);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            GLES20.glUniform1i(uTex, 0);

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, arrayBuffer);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            GLES20.glEnableVertexAttribArray(aPosition);
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 5 * 4, 0);
            GLES20.glEnableVertexAttribArray(aTexCoord);
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 5 * 4, 3 * 4);
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, indexCount, GLES20.GL_UNSIGNED_SHORT, 0);
            GLES20.glDisableVertexAttribArray(aPosition);
            GLES20.glDisableVertexAttribArray(aTexCoord);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
        }

        private void uploadTexture() {
            textureDirty = false;
            Bitmap bitmap = pendingBitmap;
            if (bitmap == null || bitmap.isRecycled()) {
                return;
            }
            Bitmap upload = bitmap.getConfig() == Bitmap.Config.ARGB_8888
                    ? bitmap
                    : bitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (upload == null) {
                return;
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            try {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, upload, 0);
            } catch (RuntimeException ignored) {
                if (++uploadAttempts < 3) {
                    textureDirty = true;
                }
            }
            if (upload != bitmap) {
                upload.recycle();
            }
        }

        private void buildSphere() {
            int rows = STACKS + 1;
            int cols = SLICES + 1;
            float[] data = new float[rows * cols * 5];
            int offset = 0;
            for (int y = 0; y <= STACKS; y++) {
                float v = y / (float) STACKS;
                float phi = v * (float) Math.PI;
                for (int x = 0; x <= SLICES; x++) {
                    float u = x / (float) SLICES;
                    float theta = u * (float) (Math.PI * 2.0);
                    data[offset++] = (float) (Math.sin(phi) * Math.sin(theta));
                    data[offset++] = (float) Math.cos(phi);
                    data[offset++] = (float) (Math.sin(phi) * Math.cos(theta));
                    data[offset++] = u;
                    data[offset++] = v;
                }
            }
            short[] tris = new short[STACKS * SLICES * 6];
            int t = 0;
            for (int y = 0; y < STACKS; y++) {
                for (int x = 0; x < SLICES; x++) {
                    int i = y * cols + x;
                    tris[t++] = (short) i;
                    tris[t++] = (short) (i + cols);
                    tris[t++] = (short) (i + 1);
                    tris[t++] = (short) (i + 1);
                    tris[t++] = (short) (i + cols);
                    tris[t++] = (short) (i + cols + 1);
                }
            }
            indexCount = tris.length;
            vertices = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            vertices.put(data).position(0);
            indices = ByteBuffer.allocateDirect(tris.length * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
            indices.put(tris).position(0);

            int[] buffers = new int[2];
            GLES20.glGenBuffers(2, buffers, 0);
            arrayBuffer = buffers[0];
            indexBuffer = buffers[1];
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, arrayBuffer);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertices.capacity() * 4, vertices, GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, indices.capacity() * 2, indices, GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0);
        }
    }

    private static int buildProgram(String vertex, String fragment) {
        int vs = compile(GLES20.GL_VERTEX_SHADER, vertex);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, fragment);
        if (vs == 0 || fs == 0) {
            return 0;
        }
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        int[] linked = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        if (linked[0] == 0) {
            GLES20.glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private static final String VERTEX =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "uniform mat4 uMvp;\n" +
            "varying vec2 vTex;\n" +
            "void main() {\n" +
            "  gl_Position = uMvp * aPosition;\n" +
            "  vTex = aTexCoord;\n" +
            "}\n";

    private static final String FRAGMENT =
            "precision mediump float;\n" +
            "varying vec2 vTex;\n" +
            "uniform sampler2D uTex;\n" +
            "uniform float uV0;\n" +
            "uniform float uV1;\n" +
            "void main() {\n" +
            "  float span = max(uV1 - uV0, 0.0001);\n" +
            "  float tv = (vTex.y - uV0) / span;\n" +
            "  if (tv < 0.0 || tv > 1.0) {\n" +
            "    gl_FragColor = vec4(0.04, 0.05, 0.06, 1.0);\n" +
            "  } else {\n" +
            "    gl_FragColor = texture2D(uTex, vec2(fract(vTex.x), tv));\n" +
            "  }\n" +
            "}\n";
}
