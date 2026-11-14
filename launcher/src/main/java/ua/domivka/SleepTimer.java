package ua.domivka;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Таймер сну через прошивку CVTE: телевізор вимикається сам через N хвилин. */
final class SleepTimer {

    static final String P_SLEEP_AT = "sleep_at";
    private static final int[] OPTIONS = {10, 20, 30, 60, 90, 120, 0};
    private static final Handler main = new Handler(Looper.getMainLooper());

    private SleepTimer() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    /** «вимкнення о 23:40», або null, якщо таймер не стоїть. */
    static String status(Context c) {
        long at = prefs(c).getLong(P_SLEEP_AT, 0);
        if (at <= System.currentTimeMillis()) {
            return null;
        }
        return "вимкнення о " + new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date(at));
    }

    static void clear(Context c) {
        prefs(c).edit().putLong(P_SLEEP_AT, 0).apply();
    }

    static void show(final Activity a, final Runnable changed) {
        String[] labels = new String[OPTIONS.length];
        for (int i = 0; i < OPTIONS.length; i++) {
            labels[i] = OPTIONS[i] == 0 ? "Скасувати таймер" : "Через " + OPTIONS[i] + " хв";
        }
        String st = status(a);
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(st == null ? "Таймер сну" : "Таймер сну: " + st)
                .setItems(labels, (d, which) -> set(a, OPTIONS[which], changed))
                .show();
    }

    private static void set(Activity a, final int minutes, final Runnable changed) {
        final Context app = a.getApplicationContext();
        CvteTv.connect(app, connected -> {
            if (!connected) {
                toast(app, "ТВ-сервіс недоступний");
                return;
            }
            new Thread(() -> {
                boolean ok = CvteTv.setSleepMinutes(minutes);
                Integer now = CvteTv.sleepMinutes();
                final boolean accepted = ok && (now == null || now == minutes);
                main.post(() -> {
                    if (accepted) {
                        long at = minutes == 0 ? 0 : System.currentTimeMillis() + minutes * 60_000L;
                        prefs(app).edit().putLong(P_SLEEP_AT, at).apply();
                        toast(app, minutes == 0 ? "Таймер сну скасовано"
                                : "Телевізор вимкнеться через " + minutes + " хв");
                    } else {
                        toast(app, "Прошивка не прийняла " + minutes + " хв, спробуйте інше значення");
                    }
                    if (changed != null) {
                        changed.run();
                    }
                });
            }, "sleep-timer").start();
        });
    }

    private static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_LONG).show();
    }
}
