package ua.domivka;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Повітряна тривога: стан для Києва або Київської області (за містом погоди).
 * Дані: відкритий JSON ubilling.net.ua/aerialalerts (без ключа), опитуємо раз на 30 секунд.
 * Про початок і відбій повідомляє AlertOverlay поверх будь-якого застосунку.
 */
final class Alerts {

    static final String P_MODE = "alert_mode";
    static final String[] MODES = {
            "банер і значок у куті поверх усього",
            "лише банер поверх усього",
            "лише на Домівці",
            "вимкнено",
    };
    static final int MODE_BANNER_BADGE = 0;
    static final int MODE_BANNER = 1;
    static final int MODE_HOME_ONLY = 2;
    static final int MODE_OFF = 3;

    private static final String URL = "https://ubilling.net.ua/aerialalerts/";
    private static final long POLL_MS = 30_000L;

    interface Listener {
        void onAlertChanged();
    }

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context app;
    private static boolean started;
    private static boolean fetching;

    /** Чи вже отримували стан для поточного регіону. */
    static boolean known;
    static boolean active;
    /** Коли почалась (або закінчилась) тривога, мс. */
    static long since;
    static String region;
    static Listener listener;

    private Alerts() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    static int mode(Context c) {
        int m = prefs(c).getInt(P_MODE, MODE_BANNER_BADGE);
        return m >= 0 && m < MODES.length ? m : MODE_BANNER_BADGE;
    }

    static void setMode(Context c, int m) {
        prefs(c).edit().putInt(P_MODE, m).apply();
        AlertOverlay.update(c);
        if (listener != null) {
            listener.onAlertChanged();
        }
        if (m != MODE_OFF) {
            poll();
        }
    }

    /** Регіон для тривог: Київ або область (за вибраним містом погоди). */
    static String regionFor(Context c) {
        return Weather.cityIndex(c) == 0 ? "м. Київ" : "Київська область";
    }

    /** Коротка назва регіону для тексту: «у Києві» / «у Київській області». */
    static String where(String region) {
        return "м. Київ".equals(region) ? "у Києві" : "у Київській області";
    }

    static void start(Context context) {
        app = context.getApplicationContext();
        if (started) {
            return;
        }
        started = true;
        main.post(loop);
    }

    private static final Runnable loop = new Runnable() {
        @Override
        public void run() {
            poll();
            main.postDelayed(this, POLL_MS);
        }
    };

    /** Негайна перевірка (наприклад, після зміни міста). */
    static void poll() {
        if (app == null || fetching || mode(app) == MODE_OFF) {
            return;
        }
        fetching = true;
        final String want = regionFor(app);
        new Thread(() -> {
            Boolean now = null;
            long changed = 0;
            try {
                JSONObject states = new JSONObject(get()).getJSONObject("states");
                JSONObject st = states.getJSONObject(want);
                now = st.getBoolean("alertnow");
                changed = parseTime(st.optString("changed", ""));
            } catch (Throwable ignored) {
            }
            final Boolean result = now;
            final long when = changed;
            main.post(() -> {
                fetching = false;
                if (result != null) {
                    apply(want, result, when);
                }
            });
        }, "alerts").start();
    }

    private static void apply(String r, boolean now, long changed) {
        boolean wasKnown = known && r.equals(region);
        boolean was = active;
        long prevSince = since;
        region = r;
        known = true;
        if (now != was || !wasKnown || since == 0) {
            since = changed > 0 ? changed : System.currentTimeMillis();
        }
        active = now;
        if (!wasKnown) {
            // Перший стан після ввімкнення: нагадуємо лише про тривогу, що вже триває
            if (now) {
                AlertOverlay.announce(app, true, true, since, 0);
            }
        } else if (now != was) {
            AlertOverlay.announce(app, now, false, now ? since : prevSince, since);
        }
        AlertOverlay.update(app);
        // Домівку смикаємо лише при зміні (тривалість у позначці оновлюється раз на 30 с сама)
        if (listener != null && (!wasKnown || now != was)) {
            listener.onAlertChanged();
        }
    }

    /** «25 хв» / «1 год 10 хв» від початку тривоги. */
    static String duration(long fromMs) {
        return span(System.currentTimeMillis() - fromMs);
    }

    static String span(long ms) {
        long min = Math.max(0, ms / 60_000L);
        if (min < 60) {
            return min + " хв";
        }
        return (min / 60) + " год" + (min % 60 > 0 ? " " + (min % 60) + " хв" : "");
    }

    static String clock(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.ROOT);
        return f.format(new java.util.Date(ms));
    }

    private static long parseTime(String s) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
            f.setTimeZone(TimeZone.getTimeZone("Europe/Kyiv"));
            long t = f.parse(s).getTime();
            // Захист від неточного годинника телевізора: не в майбутньому
            return Math.min(t, System.currentTimeMillis());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String get() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(URL).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "Domivka");
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString("UTF-8");
        } finally {
            conn.disconnect();
        }
    }
}
