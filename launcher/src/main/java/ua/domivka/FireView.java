package ua.domivka;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * Камін, намальований кодом. Вогонь: класичний демосценовий алгоритм (кожна клітинка
 * усереднює сусідів знизу і охолоджується за картою згладженого шуму, яка повзе вгору),
 * у низькій роздільності, розтягнутий зі згладжуванням. Плюс дрова, жаринки, світло, віньєтка.
 * Близько 30 кадрів на секунду, навантаження мале.
 */
final class FireView extends View {

    private static final int W = 160;
    private static final int H = 100;
    private static final int MAX_HEAT = 255;
    private static final long FRAME_MS = 33;
    /** Висота карти охолодження (прокручується по колу). */
    private static final int CH = H * 2;
    /** Сила охолодження: підібрано так, щоб полум'я займало ~60% топки. */
    private static final float COOL_SCALE = 14f;
    private static final int COOL_BASE = 1;

    private final int[] heat = new int[W * H];
    private final int[] cooling = new int[CH * W];
    private int coolOffset;
    private final int[] pixels = new int[W * H];
    private final int[] palette = new int[MAX_HEAT + 1];
    private final float[] shape = new float[W];
    private final Bitmap frame = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Paint framePaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private int seed = 0x2545F491;
    private boolean running;
    private long lastStep;
    private float glow = 1f;

    // жаринки
    private static final int EMBERS = 48;
    private final float[] ex = new float[EMBERS];
    private final float[] ey = new float[EMBERS];
    private final float[] evx = new float[EMBERS];
    private final float[] evy = new float[EMBERS];
    private final float[] elife = new float[EMBERS];
    private final float[] emax = new float[EMBERS];
    private final float[] esize = new float[EMBERS];
    private final Paint emberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // сцена
    private final Paint wallPaint = new Paint();
    private final Paint mantelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint roomGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint logPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint logEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hearthPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vignettePaint = new Paint();

    private final RectF mantelRect = new RectF();
    private final RectF stoneRect = new RectF();
    private final RectF boxRect = new RectF();
    private final RectF fireRect = new RectF();
    private final RectF hearthRect = new RectF();
    private final RectF[] logs = {new RectF(), new RectF(), new RectF()};
    private final float[] logAngles = {-7f, 6f, 0f};
    private float corner;
    /** Лише вогонь і дрова, без каміна і фону: для превʼю на головному екрані. */
    private boolean bare;

    FireView(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        buildPalette();
        buildShape();
        buildCooling();
        for (int i = 0; i < EMBERS; i++) {
            elife[i] = emax[i] = 1; // неактивні
        }
        logEdgePaint.setStyle(Paint.Style.STROKE);
        logEdgePaint.setStrokeCap(Paint.Cap.ROUND);
    }

    void setBare(boolean bare) {
        this.bare = bare;
        setBackgroundColor(bare ? Color.TRANSPARENT : Color.BLACK);
        buildPalette();
        if (getWidth() > 0) {
            onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
        }
    }

    void start() {
        running = true;
        lastStep = 0;
        postInvalidateOnAnimation();
    }

    void stop() {
        running = false;
    }

    // ---------------------------------------------------------------- вогонь

    private void buildPalette() {
        // позиція, r, g, b, a: від прозорого чорного до білого жару
        // На світлому фоні вітрини темно-червоні напівпрозорі краї виглядали як дим,
        // тому там холодні ділянки просто прозорі, а колір лише яскравий.
        float[][] stops = bare ? new float[][]{
                {0.00f, 255, 70, 10, 0},
                {0.30f, 255, 80, 12, 0},
                {0.45f, 255, 100, 18, 120},
                {0.65f, 255, 140, 35, 220},
                {0.82f, 255, 190, 70, 250},
                {0.93f, 255, 228, 140, 255},
                {1.00f, 255, 250, 215, 255},
        } : new float[][]{
                {0.00f, 0, 0, 0, 0},
                {0.08f, 40, 8, 4, 90},
                {0.25f, 120, 20, 6, 200},
                {0.45f, 205, 60, 10, 235},
                {0.65f, 245, 125, 25, 250},
                {0.82f, 252, 185, 60, 255},
                {0.93f, 255, 225, 130, 255},
                {1.00f, 255, 248, 210, 255},
        };
        for (int i = 0; i <= MAX_HEAT; i++) {
            float t = i / (float) MAX_HEAT;
            int s = 0;
            while (s < stops.length - 2 && t > stops[s + 1][0]) {
                s++;
            }
            float[] a = stops[s];
            float[] b = stops[s + 1];
            float k = (t - a[0]) / (b[0] - a[0]);
            k = Math.max(0f, Math.min(1f, k));
            palette[i] = Color.argb(
                    Math.round(a[4] + (b[4] - a[4]) * k),
                    Math.round(a[1] + (b[1] - a[1]) * k),
                    Math.round(a[2] + (b[2] - a[2]) * k),
                    Math.round(a[3] + (b[3] - a[3]) * k));
        }
    }

    /** Профіль джерела вогню: найсильніше в центрі, над дровами. */
    private void buildShape() {
        float max = 0;
        for (int x = 0; x < W; x++) {
            float t = x / (float) (W - 1);
            float g = gauss(t, 0.5f, 0.17f) + 0.55f * gauss(t, 0.36f, 0.08f) + 0.55f * gauss(t, 0.64f, 0.08f);
            shape[x] = g;
            max = Math.max(max, g);
        }
        for (int x = 0; x < W; x++) {
            shape[x] = Math.min(1f, shape[x] / max * 1.1f);
        }
    }

    /** Згладжений шум: здебільшого слабке охолодження, зрідка сильні «плями», що рвуть полум'я на язики. */
    private void buildCooling() {
        float[] a = new float[CH * W];
        for (int i = 0; i < a.length; i++) {
            a[i] = nextFloat();
        }
        float[] b = new float[a.length];
        for (int pass = 0; pass < 6; pass++) {
            for (int y = 0; y < CH; y++) {
                int up = ((y + CH - 1) % CH) * W;
                int down = ((y + 1) % CH) * W;
                int row = y * W;
                for (int x = 0; x < W; x++) {
                    int l = (x + W - 1) % W;
                    int r = (x + 1) % W;
                    b[row + x] = (a[row + x] + a[row + l] + a[row + r] + a[up + x] + a[down + x]) / 5f;
                }
            }
            float[] t = a;
            a = b;
            b = t;
        }
        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        for (float v : a) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        float range = Math.max(1e-6f, max - min);
        for (int i = 0; i < a.length; i++) {
            float v = (a[i] - min) / range;
            cooling[i] = (int) (Math.pow(v, 2.2) * COOL_SCALE) + COOL_BASE;
        }
    }

    private static float gauss(float t, float m, float s) {
        float d = t - m;
        return (float) Math.exp(-(d * d) / (2 * s * s));
    }

    private int next() {
        int s = seed;
        s ^= s << 13;
        s ^= s >>> 17;
        s ^= s << 5;
        seed = s;
        return s;
    }

    private float nextFloat() {
        return (next() >>> 8) / 16777216f;
    }

    private void step() {
        glow += ((0.8f + 0.35f * nextFloat()) - glow) * 0.12f;

        // Джерело: два нижні рядки, найгарячіше над дровами, з мерехтінням
        int r1 = (H - 1) * W;
        int r2 = (H - 2) * W;
        for (int x = 0; x < W; x++) {
            int v = (int) (MAX_HEAT * shape[x] * (0.6f + 0.4f * nextFloat()));
            heat[r1 + x] = v;
            heat[r2 + x] = v;
        }

        // Кожна клітинка = середнє трьох знизу і однієї ще нижче, мінус охолодження
        for (int y = 0; y < H - 2; y++) {
            int row = y * W;
            int below = row + W;
            int below2 = below + W;
            int coolRow = ((y + coolOffset) % CH) * W;
            for (int x = 0; x < W; x++) {
                int xl = x > 0 ? x - 1 : 0;
                int xr = x < W - 1 ? x + 1 : W - 1;
                int v = ((heat[below + xl] + heat[below + x] + heat[below + xr] + heat[below2 + x]) >> 2)
                        - cooling[coolRow + x];
                heat[row + x] = v > 0 ? v : 0;
            }
        }
        coolOffset = (coolOffset + 1) % CH;

        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = palette[heat[i]];
        }
        frame.setPixels(pixels, 0, W, 0, 0, W, H);
    }

    // ---------------------------------------------------------------- жаринки

    private void updateEmbers(float dt) {
        if (nextFloat() < 0.13f) {
            for (int i = 0; i < EMBERS; i++) {
                if (elife[i] >= emax[i]) {
                    ex[i] = fireRect.centerX() + (nextFloat() - 0.5f) * fireRect.width() * 0.5f;
                    ey[i] = fireRect.bottom - fireRect.height() * 0.12f;
                    evx[i] = (nextFloat() - 0.5f) * getWidth() * 0.03f;
                    evy[i] = -(0.07f + 0.12f * nextFloat()) * getHeight();
                    elife[i] = 0;
                    emax[i] = 1.6f + 2f * nextFloat();
                    esize[i] = (1.2f + 2.2f * nextFloat()) * getResources().getDisplayMetrics().density;
                    break;
                }
            }
        }
        for (int i = 0; i < EMBERS; i++) {
            if (elife[i] >= emax[i]) {
                continue;
            }
            elife[i] += dt;
            ex[i] += evx[i] * dt + (float) Math.sin(elife[i] * 3f + i) * 0.4f;
            ey[i] += evy[i] * dt;
            evy[i] *= 0.996f;
        }
    }

    // ---------------------------------------------------------------- малювання

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        corner = w * 0.03f;
        wallPaint.setShader(new LinearGradient(0, 0, 0, h, 0xFF0E0806, 0xFF1C0F08, Shader.TileMode.CLAMP));

        mantelRect.set(w * 0.06f, h * 0.08f, w * 0.94f, h * 0.15f);
        mantelPaint.setShader(new LinearGradient(0, mantelRect.top, 0, mantelRect.bottom,
                0xFF4A3424, 0xFF2A1D14, Shader.TileMode.CLAMP));

        stoneRect.set(w * 0.10f, h * 0.14f, w * 0.90f, h * 0.97f);
        stonePaint.setShader(new LinearGradient(0, stoneRect.top, 0, stoneRect.bottom,
                0xFF35271D, 0xFF1A130E, Shader.TileMode.CLAMP));

        boxRect.set(w * 0.17f, h * 0.23f, w * 0.83f, h * 0.93f);
        boxPaint.setColor(0xFF070403);
        innerGlowPaint.setShader(new RadialGradient(w * 0.5f, h * 0.86f, w * 0.42f,
                new int[]{0xCCB0500F, 0x55601E06, 0x00000000}, new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));

        fireRect.set(w * 0.21f, h * 0.25f, w * 0.79f, h * 0.9f);

        float logH = h * 0.055f;
        setLog(logs[0], w * 0.42f, h * 0.875f, w * 0.27f, logH);
        setLog(logs[1], w * 0.58f, h * 0.875f, w * 0.27f, logH);
        setLog(logs[2], w * 0.50f, h * 0.9f, w * 0.36f, logH * 1.1f);
        logPaint.setShader(new LinearGradient(0, h * 0.85f, 0, h * 0.93f,
                0xFF3E2414, 0xFF140A05, Shader.TileMode.CLAMP));
        logEdgePaint.setStrokeWidth(h * 0.006f);

        hearthRect.set(w * 0.14f, h * 0.925f, w * 0.86f, h * 0.975f);
        hearthPaint.setColor(0xFF1E1510);

        roomGlowPaint.setShader(new RadialGradient(w * 0.5f, h * 0.9f, w * 0.8f,
                new int[]{0x55FF7A2E, 0x00000000}, null, Shader.TileMode.CLAMP));

        vignettePaint.setShader(new RadialGradient(w * 0.5f, h * 0.55f, Math.max(w, h) * 0.78f,
                new int[]{0x00000000, 0x00000000, 0xD0000000}, new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));

        if (bare) {
            boxRect.set(0, 0, w, h);
            fireRect.set(w * 0.08f, h * 0.0f, w * 0.92f, h * 0.9f);
            float bh = h * 0.075f;
            setLog(logs[0], w * 0.40f, h * 0.885f, w * 0.36f, bh);
            setLog(logs[1], w * 0.60f, h * 0.885f, w * 0.36f, bh);
            setLog(logs[2], w * 0.50f, h * 0.925f, w * 0.48f, bh * 1.1f);
            logPaint.setShader(new LinearGradient(0, h * 0.84f, 0, h, 0xFF3E2414, 0xFF140A05, Shader.TileMode.CLAMP));
            logEdgePaint.setStrokeWidth(h * 0.008f);
            innerGlowPaint.setShader(new RadialGradient(w * 0.5f, h * 0.9f, w * 0.5f,
                    new int[]{0xAAB0500F, 0x44601E06, 0x00000000}, new float[]{0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
        }
    }

    private static void setLog(RectF r, float cx, float cy, float width, float height) {
        r.set(cx - width / 2, cy - height / 2, cx + width / 2, cy + height / 2);
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        if (running && now - lastStep >= FRAME_MS) {
            float dt = lastStep == 0 ? FRAME_MS / 1000f : Math.min(0.1f, (now - lastStep) / 1000f);
            lastStep = now;
            step();
            updateEmbers(dt);
        }

        int w = getWidth();
        int h = getHeight();
        if (!bare) {
            c.drawRect(0, 0, w, h, wallPaint);
            c.drawRoundRect(stoneRect, corner, corner, stonePaint);
            c.drawRoundRect(mantelRect, corner * 0.4f, corner * 0.4f, mantelPaint);
        }

        // Топка: темна, з теплим світлом від вогню
        c.save();
        c.clipRect(boxRect);
        if (!bare) {
            c.drawRoundRect(boxRect, corner, corner, boxPaint);
        }
        innerGlowPaint.setAlpha(Math.min(255, (int) (200 * glow)));
        c.drawRect(boxRect, innerGlowPaint);
        c.drawBitmap(frame, null, fireRect, framePaint);

        // Дрова з жаром на верхньому краї
        for (int i = 0; i < logs.length; i++) {
            RectF r = logs[i];
            c.save();
            c.rotate(logAngles[i], r.centerX(), r.centerY());
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, logPaint);
            logEdgePaint.setColor(Color.argb(Math.min(255, (int) (150 * glow)), 255, 120, 30));
            c.drawLine(r.left + r.height() * 0.6f, r.top + logEdgePaint.getStrokeWidth(),
                    r.right - r.height() * 0.6f, r.top + logEdgePaint.getStrokeWidth(), logEdgePaint);
            c.restore();
        }

        // Жаринки
        for (int i = 0; i < EMBERS; i++) {
            if (elife[i] >= emax[i]) {
                continue;
            }
            float a = 1f - elife[i] / emax[i];
            emberPaint.setColor(Color.argb((int) (60 * a), 255, 140, 40));
            c.drawCircle(ex[i], ey[i], esize[i] * 3f, emberPaint);
            emberPaint.setColor(Color.argb((int) (255 * a), 255, 180 + (int) (60 * a), 70));
            c.drawCircle(ex[i], ey[i], esize[i], emberPaint);
        }
        c.restore();

        if (!bare) {
            c.drawRoundRect(hearthRect, corner * 0.3f, corner * 0.3f, hearthPaint);
            roomGlowPaint.setAlpha(Math.min(255, (int) (255 * glow)));
            c.drawRect(0, 0, w, h, roomGlowPaint);
            c.drawRect(0, 0, w, h, vignettePaint);
        }

        if (running) {
            postInvalidateOnAnimation();
        }
    }
}
