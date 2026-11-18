package fr.adrienby.silkwarp;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class SilkWarpService extends WallpaperService {

    private static final String TAG = "SilkWarp";

    // Tuning — matches the prototype at Twist / warp 1.0 / speed 0.5 / sheen 1.0
    private static final float SPEED = 0.5f;
    private static final float STRENGTH = 1.0f;
    private static final float SHEEN = 1.0f;

    @Override
    public Engine onCreateEngine() {
        return new SilkEngine();
    }

    class SilkEngine extends Engine implements GLSurfaceView.Renderer {

        /** GLSurfaceView that draws into the wallpaper's surface instead of a window. */
        class WallpaperGLView extends GLSurfaceView {
            WallpaperGLView(Context c) { super(c); }
            @Override public SurfaceHolder getHolder() { return getSurfaceHolder(); }
            void destroy() { super.onDetachedFromWindow(); }
        }

        private final Handler main = new Handler(Looper.getMainLooper());
        private WallpaperGLView view;
        private KeyguardManager keyguard;
        private Bitmap bitmap;

        // Written on the main thread, read on the GL thread
        private volatile float targetP = 1f;     // 0 = lock-screen state, 1 = home
        private volatile boolean snapToLock = false;
        private volatile float offset = 0.5f;    // launcher wallpaper offset
        private volatile boolean visible = false;
        private volatile int gen = 0;            // bumps on every kick, guards going idle
        private boolean receiverOn = false;

        // GL-thread state
        private float p = 1f, pv = 0f;
        private long lastNs = 0;
        private final long t0 = SystemClock.elapsedRealtime();
        private int prog = 0, tex = 0, vbo = 0;
        private int surfW = 1, surfH = 1, texW = 1, texH = 1;
        private int uTex, uRes, uTexSize, uImgA, uP, uOff, uT, uStr, uSheen;

        private final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                String a = i.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    lockNow();
                } else if (Intent.ACTION_USER_PRESENT.equals(a)) {
                    unlock();
                } else if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    if (keyguard != null && !keyguard.isKeyguardLocked()) unlock();
                    else kick();
                }
            }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            keyguard = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inScaled = false;
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.silk, o);

            if (isPreview()) {
                p = 0f; targetP = 0f;
                main.postDelayed(this::unlock, 800);   // show the morph once in the picker
            } else {
                boolean locked = keyguard != null && keyguard.isKeyguardLocked();
                p = locked ? 0f : 1f; targetP = p;
            }
            setTouchEventsEnabled(isPreview());

            view = new WallpaperGLView(SilkWarpService.this);
            view.setEGLContextClientVersion(3);
            view.setPreserveEGLContextOnPause(true);
            view.setRenderer(this);
            view.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

            if (!isPreview()) {
                IntentFilter f = new IntentFilter();
                f.addAction(Intent.ACTION_SCREEN_OFF);
                f.addAction(Intent.ACTION_SCREEN_ON);
                f.addAction(Intent.ACTION_USER_PRESENT);
                if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
                else registerReceiver(receiver, f);
                receiverOn = true;
            }
        }

        @Override
        public void onDestroy() {
            main.removeCallbacksAndMessages(null);
            if (receiverOn) {
                try { unregisterReceiver(receiver); } catch (Exception ignored) { }
                receiverOn = false;
            }
            if (view != null) view.destroy();
            super.onDestroy();
        }

        @Override
        public void onVisibilityChanged(boolean v) {
            super.onVisibilityChanged(v);
            visible = v;
            if (view == null) return;
            if (v) {
                view.onResume();
                if (!isPreview() && keyguard != null) {
                    if (keyguard.isKeyguardLocked()) {
                        snapToLock = true; targetP = 0f;
                    } else if (targetP < 0.5f) {
                        targetP = 1f;          // became visible on home while still in lock state
                    }
                }
                kick();
            } else {
                view.onPause();
            }
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xStep, float yStep, int xPx, int yPx) {
            offset = xOffset;
            if (view != null) view.requestRender();
        }

        @Override
        public void onTouchEvent(MotionEvent e) {
            // Preview only: tap to replay lock <-> unlock
            if (isPreview() && e.getActionMasked() == MotionEvent.ACTION_UP) {
                targetP = targetP > 0.5f ? 0f : 1f;
                kick();
            }
        }

        // ---- main-thread helpers ----
        private void kick() {
            gen++;
            if (view != null) view.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        }
        private void unlock() { targetP = 1f; kick(); }
        private void lockNow() {
            snapToLock = true; targetP = 0f;
            if (view != null) view.requestRender();
        }

        // ---- renderer (GL thread) ----
        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            prog = buildProgram(SilkShader.VERT, SilkShader.FRAG);
            uTex = GLES30.glGetUniformLocation(prog, "uTex");
            uRes = GLES30.glGetUniformLocation(prog, "uRes");
            uTexSize = GLES30.glGetUniformLocation(prog, "uTexSize");
            uImgA = GLES30.glGetUniformLocation(prog, "uImgA");
            uP = GLES30.glGetUniformLocation(prog, "uP");
            uOff = GLES30.glGetUniformLocation(prog, "uOff");
            uT = GLES30.glGetUniformLocation(prog, "uT");
            uStr = GLES30.glGetUniformLocation(prog, "uStr");
            uSheen = GLES30.glGetUniformLocation(prog, "uSheen");

            float[] quad = { -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f };
            FloatBuffer fb = ByteBuffer.allocateDirect(quad.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer();
            fb.put(quad).position(0);
            int[] ids = new int[1];
            GLES30.glGenBuffers(1, ids, 0);
            vbo = ids[0];
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.length * 4, fb, GLES30.GL_STATIC_DRAW);

            GLES30.glGenTextures(1, ids, 0);
            tex = ids[0];
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
            if (bitmap != null) {
                texW = bitmap.getWidth(); texH = bitmap.getHeight();
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
            }
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_MIRRORED_REPEAT);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_MIRRORED_REPEAT);
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int w, int h) {
            surfW = Math.max(1, w); surfH = Math.max(1, h);
            GLES30.glViewport(0, 0, surfW, surfH);
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            long now = System.nanoTime();
            float dt = lastNs == 0 ? 1f / 60f : Math.min(0.05f, (now - lastNs) / 1e9f);
            lastNs = now;

            if (snapToLock) { snapToLock = false; p = 0f; pv = 0f; }
            float tgt = targetP;

            // damped spring, same constants as the prototype
            float k = 95f * SPEED * SPEED, c = 2f * 0.68f * (float) Math.sqrt(k);
            int n = Math.max(1, (int) Math.ceil(dt / 0.004f));
            float hs = dt / n;
            for (int i = 0; i < n; i++) {
                float acc = -k * (p - tgt) - c * pv;
                pv += acc * hs;
                p += pv * hs;
            }
            boolean settled = Math.abs(p - tgt) < 0.0005f && Math.abs(pv) < 0.002f;
            if (settled) { p = tgt; pv = 0f; }

            float t = ((SystemClock.elapsedRealtime() - t0) % 600_000L) / 1000f;

            GLES30.glClearColor(0.6f, 0.6f, 0.62f, 1f);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            if (prog != 0) {
                GLES30.glUseProgram(prog);
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
                GLES30.glUniform1i(uTex, 0);
                GLES30.glUniform2f(uRes, surfW, surfH);
                GLES30.glUniform2f(uTexSize, texW, texH);
                GLES30.glUniform1f(uImgA, (float) texW / texH);
                GLES30.glUniform1f(uP, p);
                GLES30.glUniform1f(uOff, offset);
                GLES30.glUniform1f(uT, t);
                GLES30.glUniform1f(uStr, STRENGTH);
                GLES30.glUniform1f(uSheen, SHEEN);
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo);
                GLES30.glEnableVertexAttribArray(0);
                GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            }

            // Lock screen keeps a slow "breathing" motion; otherwise stop drawing once still.
            boolean breathing = visible && p < 0.999f;
            if (settled && !breathing) {
                lastNs = 0;
                final int g = gen;
                main.post(() -> {
                    if (g == gen && view != null) view.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
                });
            }
        }

        private int buildProgram(String vs, String fs) {
            int v = compile(GLES30.GL_VERTEX_SHADER, vs);
            int f = compile(GLES30.GL_FRAGMENT_SHADER, fs);
            if (v == 0 || f == 0) return 0;
            int pr = GLES30.glCreateProgram();
            GLES30.glAttachShader(pr, v);
            GLES30.glAttachShader(pr, f);
            GLES30.glLinkProgram(pr);
            int[] ok = new int[1];
            GLES30.glGetProgramiv(pr, GLES30.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                Log.e(TAG, "link: " + GLES30.glGetProgramInfoLog(pr));
                GLES30.glDeleteProgram(pr);
                return 0;
            }
            return pr;
        }

        private int compile(int type, String src) {
            int s = GLES30.glCreateShader(type);
            GLES30.glShaderSource(s, src);
            GLES30.glCompileShader(s);
            int[] ok = new int[1];
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) {
                Log.e(TAG, "compile: " + GLES30.glGetShaderInfoLog(s));
                GLES30.glDeleteShader(s);
                return 0;
            }
            return s;
        }
    }
}
