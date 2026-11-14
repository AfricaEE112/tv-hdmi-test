package ua.domivka;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.util.Calendar;
import java.util.Random;

/**
 * Фон у стилі «м'яких кольорових плям»: малюється один раз у малій роздільності,
 * а розтягується з згладжуванням, тому виходить дуже плавним. Кольори залежать
 * від часу доби, розташування плям трохи змінюється щодня.
 */
final class Wallpaper {

    private Wallpaper() {
    }

    static Bitmap make(int width, int height) {
        int w = Math.max(64, width / 4);
        int h = Math.max(36, height / 4);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        int[] colors;
        int base;
        if (hour >= 6 && hour < 11) {          // ранок: персик і бірюза
            base = 0xFF101A2A;
            colors = new int[]{0xFFFF9E7A, 0xFF38B8B0, 0xFF5B6CFF, 0xFFFFC66E};
        } else if (hour >= 11 && hour < 17) {  // день: синє небо
            base = 0xFF0C1830;
            colors = new int[]{0xFF2F8CFF, 0xFF21C7B7, 0xFF7A5CFF, 0xFF4FA3FF};
        } else if (hour >= 17 && hour < 22) {  // вечір: пурпур і помаранч
            base = 0xFF140C24;
            colors = new int[]{0xFFFF6A5B, 0xFF8E44FF, 0xFFFF9F43, 0xFF3D5AFE};
        } else {                               // ніч: глибокий синій
            base = 0xFF060A18;
            colors = new int[]{0xFF2B3AFF, 0xFF6A2BD9, 0xFF0F8DAA, 0xFF3A1F8F};
        }
        c.drawColor(base);

        Random rnd = new Random(now.get(Calendar.DAY_OF_YEAR) * 31L + now.get(Calendar.YEAR));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < 6; i++) {
            int col = colors[i % colors.length];
            float cx = w * (0.1f + 0.8f * rnd.nextFloat());
            float cy = h * (0.05f + 0.9f * rnd.nextFloat());
            float r = w * (0.35f + 0.35f * rnd.nextFloat());
            int alpha = 70 + rnd.nextInt(60);
            p.setShader(new RadialGradient(cx, cy, r,
                    Color.argb(alpha, Color.red(col), Color.green(col), Color.blue(col)),
                    Color.argb(0, Color.red(col), Color.green(col), Color.blue(col)),
                    Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
        }

        // Затемнення донизу: на ньому краще читаються плитки і підписи
        p.setShader(new LinearGradient(0, 0, 0, h, 0x00000000, 0xB0000000, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        return bmp;
    }
}
