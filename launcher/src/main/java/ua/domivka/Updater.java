package ua.domivka;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Оновлення Домівки по повітрю: GitHub Actions викладає domivka.apk і version.json у реліз
 * «latest», а Домівка перевіряє його і пропонує встановити нову версію (через системний
 * встановлювач; APK віддається йому через UpdateProvider).
 */
final class Updater {

    private static final String BASE = "https://github.com/AfricaEE112/tv-hdmi-test/releases/download/latest/";
    private static final String P_LAST_CHECK = "update_last_check";
    private static final String P_AVAILABLE = "update_available_code";
    private static final String P_AVAILABLE_NAME = "update_available_name";
    private static final String P_AVAILABLE_NOTES = "update_available_notes";
    static final long CHECK_EVERY_MS = 6 * 60 * 60_000L;
    static final String APK_NAME = "update.apk";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean busy;
    private static int promptedCode;

    interface Result {
        void onResult(int code, String name);
    }

    private Updater() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    static int currentCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    static String currentName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Відома доступна нова версія (за останньою перевіркою), або null. */
    static String availableName(Context c) {
        int code = prefs(c).getInt(P_AVAILABLE, 0);
        return code > currentCode(c) ? prefs(c).getString(P_AVAILABLE_NAME, "?") : null;
    }

    static boolean dueForCheck(Context c) {
        return System.currentTimeMillis() - prefs(c).getLong(P_LAST_CHECK, 0) > CHECK_EVERY_MS;
    }

    /** Перевірити version.json. Результат у головному потоці: code=-1, якщо помилка. */
    static void check(Context context, Result result) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            int code = -1;
            String name = null;
            try {
                JSONObject j = new JSONObject(get(BASE + "version.json"));
                code = j.getInt("versionCode");
                name = j.getString("versionName");
                prefs(app).edit()
                        .putLong(P_LAST_CHECK, System.currentTimeMillis())
                        .putInt(P_AVAILABLE, code)
                        .putString(P_AVAILABLE_NAME, name)
                        .putString(P_AVAILABLE_NOTES, j.optString("notes", ""))
                        .apply();
            } catch (Throwable ignored) {
            }
            final int c = code;
            final String n = name;
            main.post(() -> result.onResult(c, n));
        }, "update-check").start();
    }

    /** Тиха перевірка: якщо є нова версія, один раз за сеанс пропонуємо оновитися. */
    static void checkAndPrompt(Activity activity, boolean manual) {
        if (!manual && !dueForCheck(activity)) {
            String avail = availableName(activity);
            int code = prefs(activity).getInt(P_AVAILABLE, 0);
            if (avail != null && promptedCode != code) {
                promptedCode = code;
                prompt(activity, avail);
            }
            return;
        }
        check(activity, (code, name) -> {
            if (activity.isFinishing()) {
                return;
            }
            if (code < 0) {
                if (manual) {
                    toast(activity, "Не вдалося перевірити оновлення: перевірте інтернет");
                }
                return;
            }
            if (code > currentCode(activity)) {
                if (manual || promptedCode != code) {
                    promptedCode = code;
                    prompt(activity, name);
                }
            } else if (manual) {
                toast(activity, "У вас найновіша версія Домівки (" + currentName(activity) + ")");
            }
        });
    }

    private static void prompt(Activity activity, String name) {
        String notes = prefs(activity).getString(P_AVAILABLE_NOTES, "");
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Нова версія Домівки: " + name)
                .setMessage((notes.isEmpty() ? "" : notes + "\n\n")
                        + "У вас версія " + currentName(activity) + ". "
                        + "Завантажити і встановити зараз? Займе менше хвилини.")
                .setPositiveButton("Оновити", (d, w) -> downloadAndInstall(activity))
                .setNegativeButton("Пізніше", null)
                .show();
    }

    /**
     * Після оновлення (по повітрю чи вручну) один раз показуємо, що нового.
     * Після першого встановлення нічого не показуємо.
     */
    static void showWhatsNewIfUpdated(Activity activity) {
        int code = currentCode(activity);
        int seen = prefs(activity).getInt("seen_version_code", 0);
        if (seen == code) {
            return;
        }
        prefs(activity).edit().putInt("seen_version_code", code).apply();
        boolean updated;
        try {
            android.content.pm.PackageInfo pi =
                    activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            updated = pi.lastUpdateTime - pi.firstInstallTime > 5_000L;
        } catch (Throwable t) {
            updated = seen != 0;
        }
        if (!updated) {
            return;
        }
        String notes;
        try (InputStream in = activity.getResources().openRawResource(R.raw.whatsnew)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            notes = out.toString("UTF-8").trim();
        } catch (Throwable t) {
            notes = "";
        }
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Домівку оновлено до версії " + currentName(activity))
                .setMessage(notes)
                .setPositiveButton("Чудово", null)
                .show();
    }

    static void downloadAndInstall(Activity activity) {
        if (busy) {
            return;
        }
        busy = true;
        final AlertDialog progress = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Оновлення Домівки")
                .setMessage("Завантажую...")
                .setCancelable(false)
                .show();
        final Context app = activity.getApplicationContext();
        new Thread(() -> {
            File out = new File(app.getCacheDir(), APK_NAME);
            boolean ok = download(BASE + "domivka.apk", out, percent ->
                    main.post(() -> progress.setMessage("Завантажую... " + percent + "%")));
            main.post(() -> {
                busy = false;
                try {
                    progress.dismiss();
                } catch (Throwable ignored) {
                }
                if (!ok) {
                    toast(app, "Не вдалося завантажити оновлення");
                    return;
                }
                Intent install = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Uri.parse("content://" + UpdateProvider.AUTHORITY + "/" + APK_NAME),
                                "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    activity.startActivity(install);
                } catch (Throwable t) {
                    toast(app, "Не вдалося відкрити встановлювач: " + t.getClass().getSimpleName());
                }
            });
        }, "update-download").start();
    }

    interface Progress {
        void onProgress(int percent);
    }

    static boolean download(String url, File out, Progress progress) {
        HttpURLConnection conn = null;
        try {
            conn = open(url);
            if (conn.getResponseCode() != 200) {
                return false;
            }
            long total = conn.getContentLength();
            File tmp = new File(out.getPath() + ".part");
            long done = 0;
            try (InputStream in = conn.getInputStream(); OutputStream os = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16 * 1024];
                int lastPercent = -1;
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int p = (int) (done * 100 / total);
                        if (p != lastPercent) {
                            lastPercent = p;
                            progress.onProgress(p);
                        }
                    }
                }
            }
            if (total > 0 && done != total) {
                // Зв'язок обірвався: недокачаний файл не вважаємо готовим
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return tmp.renameTo(out);
        } catch (Throwable t) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection conn = open(url);
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

    /** GitHub віддає файли релізу через перенаправлення на інший домен: ідемо за ним вручну. */
    private static HttpURLConnection open(String url) throws Exception {
        for (int i = 0; i < 5; i++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(20_000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "Domivka");
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String next = conn.getHeaderField("Location");
                conn.disconnect();
                url = new URL(new URL(url), next).toString();
                continue;
            }
            return conn;
        }
        throw new Exception("too many redirects");
    }

    private static void toast(Context c, String s) {
        Toast.makeText(c, s, Toast.LENGTH_LONG).show();
    }
}
