package ua.domivka;

import android.app.AppOpsManager;
import android.app.Application;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

/**
 * Сторож увімкнення: прошивка CVTE після ввімкнення телевізора сама запускає свій
 * лаунчер (в обхід вибору головного екрана). Після ввімкнення ми перевіряємо, що на
 * екрані, і якщо там штатний лаунчер, відкриваємо Домівку.
 */
public class App extends Application {

    static final String P_POWER_ON_HOME = "power_on_home";

    /** Чи зараз на екрані Домівка (оновлює HomeActivity). */
    static volatile boolean homeResumed;

    private static final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        // Вихід телевізора з режиму очікування: екран вмикається знову.
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                schedule(context, null);
            }
        }, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    /**
     * Кілька перевірок протягом ~10 секунд після ввімкнення: штатний лаунчер прошивка
     * відкриває не одразу. [done] викликається після останньої перевірки.
     */
    static void schedule(Context context, final Runnable done) {
        final Context app = context.getApplicationContext();
        boolean enabled = app.getSharedPreferences("home", MODE_PRIVATE).getBoolean(P_POWER_ON_HOME, true);
        if (!enabled) {
            if (done != null) {
                done.run();
            }
            return;
        }
        final long[] delays = {2500, 6000, 10000};
        for (int i = 0; i < delays.length; i++) {
            final boolean last = i == delays.length - 1;
            final int attempt = i;
            main.postDelayed(() -> {
                check(app, attempt);
                if (last && done != null) {
                    done.run();
                }
            }, delays[i]);
        }
    }

    private static void check(Context app, int attempt) {
        if (homeResumed) {
            return;
        }
        String front = foregroundPackage(app);
        boolean bringHome;
        if (front != null && !front.isEmpty()) {
            // Точно знаємо, що на екрані: підміняємо лише штатний лаунчер
            bringHome = isStockLauncher(front);
        } else {
            // Без доступу до статистики: дві перші спроби відкривають Домівку,
            // третю пропускаємо, щоб не перебити те, що людина вже встигла відкрити
            bringHome = attempt < 2;
        }
        if (bringHome) {
            try {
                app.startActivity(new Intent(app, HomeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored) {
            }
        }
    }

    static boolean isStockLauncher(String pkg) {
        return pkg.equals("com.cvte.tv.launcher") || pkg.startsWith("com.cvte.tv.launcher.")
                || pkg.equals("com.example.user.myapplication");
    }

    /**
     * Який застосунок зараз на екрані. null: немає доступу до статистики (його видає
     * пункт 4 скрипта tv-tools через ADB). Порожній рядок: доступ є, але подій немає.
     */
    static String foregroundPackage(Context ctx) {
        try {
            AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(),
                    ctx.getPackageName());
            if (mode != AppOpsManager.MODE_ALLOWED) {
                return null;
            }
            UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(now - 60_000, now);
            UsageEvents.Event e = new UsageEvents.Event();
            String last = "";
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    last = e.getPackageName();
                }
            }
            return last;
        } catch (Throwable t) {
            return null;
        }
    }
}
