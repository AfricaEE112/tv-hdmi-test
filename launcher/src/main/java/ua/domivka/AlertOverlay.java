package ua.domivka;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Повідомлення про тривогу поверх будь-якого застосунку (YouTube, консоль, камін).
 * Великий банер на початку і при відбої, маленький значок у куті, поки тривога триває.
 * Вікно поверх інших: з дозволом «поверх застосунків» (видає tv-tools через ADB) або,
 * без нього, віконце типу «тост», яке Android 7.0 дозволяє будь-якому застосунку.
 */
final class AlertOverlay {

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static View banner;
    private static View badge;
    private static TextView badgeText;
    private static final Runnable hideBanner = AlertOverlay::removeBanner;

    private AlertOverlay() {
    }

    /** Початок або кінець тривоги. ongoing: тривога вже йшла, коли ми ввімкнулися. */
    static void announce(Context c, boolean alert, boolean ongoing, long startMs, long endMs) {
        int mode = Alerts.mode(c);
        if (mode == Alerts.MODE_OFF || (mode == Alerts.MODE_HOME_ONLY && !App.homeResumed)) {
            return;
        }
        String where = Alerts.where(Alerts.region);
        String title;
        String sub;
        int color;
        int icon;
        if (alert) {
            title = ongoing ? "Триває повітряна тривога" : "Повітряна тривога";
            sub = where + (ongoing ? " з " + Alerts.clock(startMs) : "") + "  ·  пройдіть в укриття";
            color = 0xF2C62828;
            icon = R.drawable.ic_alert;
        } else {
            title = "Відбій тривоги";
            sub = where + (startMs > 0 && endMs - startMs >= 60_000L
                    ? "  ·  тривала " + Alerts.span(endMs - startMs) : "");
            color = 0xF22E7D32;
            icon = R.drawable.ic_ok;
        }
        showBanner(c, title, sub, color, icon, alert ? 15_000 : 8_000);
    }

    /** Значок у куті: лише поки триває тривога, поза Домівкою і в режимі «банер і значок». */
    static void update(Context c) {
        boolean want = Alerts.active && !App.homeResumed && Alerts.mode(c) == Alerts.MODE_BANNER_BADGE;
        if (!want) {
            if (badge != null) {
                remove(c, badge);
                badge = null;
                badgeText = null;
            }
            return;
        }
        String text = "Тривога  ·  " + Alerts.duration(Alerts.since);
        if (badge != null) {
            badgeText.setText(text);
            return;
        }
        Context app = c.getApplicationContext();
        LinearLayout box = new LinearLayout(app);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(app, 12), dp(app, 6), dp(app, 16), dp(app, 6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD9B71C1C);
        bg.setCornerRadius(dp(app, 20));
        box.setBackground(bg);
        View dot = new View(app);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(Color.WHITE);
        dot.setBackground(d);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(dp(app, 10), dp(app, 10));
        dp.rightMargin = dp(app, 8);
        box.addView(dot, dp);
        TextView t = new TextView(app);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setText(text);
        box.addView(t);
        if (add(app, box, Gravity.TOP | Gravity.END, dp(app, 28), dp(app, 18))) {
            badge = box;
            badgeText = t;
            // Значок повільно «дихає», щоб було видно, що він живий, але не дратував
            dot.animate().alpha(0.25f).setDuration(1200).withEndAction(new Runnable() {
                @Override
                public void run() {
                    if (badge == null) {
                        return;
                    }
                    float to = dot.getAlpha() < 0.5f ? 1f : 0.25f;
                    dot.animate().alpha(to).setDuration(1200).withEndAction(this).start();
                }
            }).start();
        }
    }

    private static void showBanner(Context c, String title, String sub, int color, int icon, long ms) {
        Context app = c.getApplicationContext();
        removeBanner();

        LinearLayout box = new LinearLayout(app);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(app, 26), dp(app, 18), dp(app, 36), dp(app, 18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(app, 22));
        box.setBackground(bg);

        ImageView ic = new ImageView(app);
        ic.setImageResource(icon);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(app, 44), dp(app, 44));
        ip.rightMargin = dp(app, 20);
        box.addView(ic, ip);

        LinearLayout texts = new LinearLayout(app);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(app);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setText(title);
        texts.addView(t);
        TextView s = new TextView(app);
        s.setTextColor(0xEEFFFFFF);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        s.setText(sub);
        texts.addView(s);
        box.addView(texts);

        if (add(app, box, Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(app, 36))) {
            banner = box;
            box.setAlpha(0f);
            box.setTranslationY(-dp(app, 30));
            box.animate().alpha(1f).translationY(0).setDuration(350).start();
            main.removeCallbacks(hideBanner);
            main.postDelayed(hideBanner, ms);
        } else {
            // Запасний варіант: звичайний тост (двічі для тривоги, щоб довше було видно)
            Toast toast = new Toast(app);
            toast.setView(box);
            toast.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(app, 36));
            toast.setDuration(Toast.LENGTH_LONG);
            toast.show();
            if (ms > 10_000) {
                main.postDelayed(toast::show, 3600);
            }
        }
    }

    private static void removeBanner() {
        main.removeCallbacks(hideBanner);
        if (banner != null) {
            View b = banner;
            banner = null;
            b.animate().alpha(0f).setDuration(300).withEndAction(() -> remove(b.getContext(), b)).start();
        }
    }

    private static boolean add(Context app, View v, int gravity, int x, int y) {
        WindowManager wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        boolean overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(app);
        @SuppressWarnings("deprecation")
        int[] types = overlay
                ? new int[]{WindowManager.LayoutParams.TYPE_SYSTEM_ALERT, WindowManager.LayoutParams.TYPE_TOAST}
                : new int[]{WindowManager.LayoutParams.TYPE_TOAST};
        for (int type : types) {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = gravity;
            lp.x = x;
            lp.y = y;
            lp.setTitle("Domivka alert");
            try {
                wm.addView(v, lp);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static void remove(Context c, View v) {
        try {
            ((WindowManager) c.getApplicationContext().getSystemService(Context.WINDOW_SERVICE)).removeView(v);
        } catch (Throwable ignored) {
        }
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
