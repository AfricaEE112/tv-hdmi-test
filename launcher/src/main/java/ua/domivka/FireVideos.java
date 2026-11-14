package ua.domivka;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import java.io.File;

/**
 * Справжні відео каміна. Вихідні відео — з Wikimedia Commons під вільними ліцензіями;
 * GitHub Actions (media.yml) робить з них безшовні петлі H.264 720p і викладає в реліз «media».
 * Домівка завантажує вибраний камін один раз і далі грає його без інтернету.
 */
final class FireVideos {

    static final String DRAWN = "drawn";
    static final String P_SELECTED = "fire_video";
    private static final String BASE = "https://github.com/AfricaEE112/tv-hdmi-test/releases/download/media/";

    static final String[] IDS = {"yule", "kamin", "brick", DRAWN};
    static final String[] TITLES = {
            "Справжній камін, 5 хв зі звуком",
            "Класичний камін за склом",
            "Цегляний камін",
            "Намальований (без інтернету)",
    };
    static final String[] SIZES = {"75 МБ", "3,5 МБ", "12 МБ", ""};
    static final String CREDITS =
            "Справжній камін: «Valley Forge Yule Log», Gregory Purifoy, National Park Service "
                    + "(суспільне надбання).\n"
                    + "Класичний камін: «Kaminfeuer», Larvis, CC BY-SA 4.0, Wikimedia Commons.\n"
                    + "Цегляний камін: «Fantastic fireplace», free video library, CC BY 3.0, Wikimedia Commons.\n"
                    + "Усі відео скорочено і зациклено для Домівки.";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static String downloading;

    interface Listener {
        void onProgress(int percent);

        void onDone(boolean ok);
    }

    private FireVideos() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    static String selected(Context c) {
        String id = prefs(c).getString(P_SELECTED, "yule");
        return index(id) >= 0 ? id : "yule";
    }

    static void select(Context c, String id) {
        prefs(c).edit().putString(P_SELECTED, id).apply();
    }

    static int index(String id) {
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equals(id)) {
                return i;
            }
        }
        return -1;
    }

    static String title(String id) {
        int i = index(id);
        return i >= 0 ? TITLES[i] : "";
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "fire");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static File file(Context c, String id) {
        return new File(dir(c), "fireplace-" + id + ".mp4");
    }

    static File poster(Context c, String id) {
        return new File(dir(c), "poster-" + id + ".jpg");
    }

    static boolean isVideo(String id) {
        return !DRAWN.equals(id);
    }

    static boolean isReady(Context c, String id) {
        return isVideo(id) && file(c, id).exists();
    }

    static boolean isDownloading(String id) {
        return id.equals(downloading);
    }

    /** Обкладинка для вітрини (може бути null). Невелика: читаємо в головному потоці. */
    static Bitmap posterBitmap(Context c, String id) {
        File p = poster(c, id);
        if (!p.exists()) {
            return null;
        }
        try {
            return BitmapFactory.decodeFile(p.getPath());
        } catch (Throwable t) {
            return null;
        }
    }

    /** Лише обкладинка (кілька десятків КБ), щоб вітрина одразу показувала справжній камін. */
    static void fetchPoster(Context context, String id, Runnable done) {
        if (!isVideo(id) || poster(context, id).exists()) {
            return;
        }
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            boolean ok = Updater.download(BASE + "poster-" + id + ".jpg", poster(app, id), p -> { });
            if (ok && done != null) {
                main.post(done);
            }
        }, "fire-poster").start();
    }

    static String downloading() {
        return downloading;
    }

    /**
     * Завантажити відео (одне за раз). Колбеки в головному потоці.
     * false, якщо зараз уже завантажується інший камін (тоді колбеків не буде).
     */
    static boolean download(Context context, String id, Listener listener) {
        if (!isVideo(id) || downloading != null) {
            return false;
        }
        downloading = id;
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            Updater.download(BASE + "poster-" + id + ".jpg", poster(app, id), p -> { });
            boolean ok = Updater.download(BASE + "fireplace-" + id + ".mp4", file(app, id),
                    p -> main.post(() -> listener.onProgress(p)));
            main.post(() -> {
                downloading = null;
                listener.onDone(ok);
            });
        }, "fire-download").start();
        return true;
    }

    /** Видалити завантажені відео, крім вибраного (звільнити місце). */
    static void deleteOthers(Context c) {
        String keep = selected(c);
        for (String id : IDS) {
            if (isVideo(id) && !id.equals(keep)) {
                //noinspection ResultOfMethodCallIgnored
                file(c, id).delete();
            }
        }
    }
}
