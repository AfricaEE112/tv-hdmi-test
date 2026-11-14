package ua.domivka;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Доступ до ТВ-частини прошивки CVTE (джерела сигналу, HDMI).
 *
 * Бібліотека прошивки (com.cvte.tv.api) лежить у системному завантажувачі класів, тому її
 * класи доступні через рефлексію без вбудовування в APK. Підключаємося до сервісу
 * com.cvte.tv.api.impl (action com.cvte.tv.api.TV_API_SERVICE) і викликаємо методи AIDL.
 *
 * Підхід узято з MIT-проєкту https://github.com/Koqpe/cvte-android-tv-launcher
 * (docs/cvte-firmware.md, CvteTv.kt).
 */
final class CvteTv {

    private static final String TAG = "Domivka";

    /** Джерело сигналу за даними прошивки CVTE. */
    static final class Source {
        final int id;
        final String category;
        final String name;
        final String status;

        Source(int id, String category, String name, String status) {
            this.id = id;
            this.category = category;
            this.name = name;
            this.status = status;
        }

        boolean isHdmi() {
            return "INPUTSOURCE_HDMI".equals(category);
        }
    }

    interface Ready {
        void onReady(boolean connected);
    }

    private static Object api;
    private static boolean binding;
    private static final List<Ready> waiting = new ArrayList<>();
    static String lastError = "";

    private static final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            try {
                api = Class.forName("com.cvte.tv.api.aidl.ITvApiManager$Stub")
                        .getMethod("asInterface", IBinder.class)
                        .invoke(null, binder);
            } catch (Throwable t) {
                lastError = "бібліотека CVTE недоступна: " + t;
                Log.w(TAG, lastError, t);
                api = null;
            }
            finish(api != null);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            api = null;
        }
    };

    private CvteTv() {
    }

    static boolean isReady() {
        return api != null;
    }

    /** Підключитися до сервісу (один раз на процес). Колбек приходить у головному потоці. */
    static void connect(Context context, Ready ready) {
        if (api != null) {
            ready.onReady(true);
            return;
        }
        waiting.add(ready);
        if (binding) {
            return;
        }
        binding = true;
        Intent intent = new Intent("com.cvte.tv.api.TV_API_SERVICE").setPackage("com.cvte.tv.api.impl");
        boolean ok;
        try {
            ok = context.getApplicationContext().bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            lastError = "bindService: " + t;
            ok = false;
        }
        if (!ok) {
            if (lastError.isEmpty()) {
                lastError = "сервіс com.cvte.tv.api.impl не знайдено або доступ заборонено";
            }
            finish(false);
        }
    }

    private static void finish(final boolean connected) {
        binding = false;
        final List<Ready> callbacks = new ArrayList<>(waiting);
        waiting.clear();
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                for (Ready r : callbacks) {
                    r.onReady(connected);
                }
            }
        });
    }

    private static Object sourceApi() {
        return call(api, "getTVApiSystemInputSource");
    }

    /** Список джерел. Порожній, якщо сервіс недоступний. */
    static List<Source> sources() {
        List<Source> result = new ArrayList<>();
        Object list = call(sourceApi(), "eventSystemInputSourceGetList");
        if (!(list instanceof List)) {
            return result;
        }
        for (Object e : (List<?>) list) {
            if (e == null) {
                continue;
            }
            try {
                result.add(new Source(
                        (Integer) field(e, "id"),
                        enumName(field(e, "category")),
                        String.valueOf(field(e, "name")),
                        enumName(field(e, "status"))));
            } catch (Throwable t) {
                lastError = "розбір джерела: " + t + " / " + e;
            }
        }
        return result;
    }

    /** Поточне джерело, або null. */
    static Integer currentId() {
        Object current = call(sourceApi(), "eventSystemInputSourceGetInputSource");
        if (current == null) {
            return null;
        }
        try {
            return (Integer) field(current, "id");
        } catch (Throwable t) {
            return null;
        }
    }

    /** Переключити джерело. Викликати не в головному потоці. */
    static boolean setSource(int id) {
        Object r = call(sourceApi(), "eventSystemInputSourceSetInputSource", id);
        return r instanceof Boolean && (Boolean) r;
    }

    /**
     * Відкрити рідний повноекранний ТВ-екран CVTE з поточним джерелом.
     * Action MAIN обов'язковий: без нього екран падає в onNewIntent.
     */
    static boolean openTvScreen(Context context) {
        try {
            context.startActivity(new Intent(Intent.ACTION_MAIN)
                    .setClassName("com.cvte.tv.androidsetting",
                            "com.cvte.tv.androidsetting.TvSettingActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Throwable t) {
            lastError = "TvSettingActivity: " + t;
            Log.w(TAG, lastError, t);
            return false;
        }
    }

    /** Виклик методу за назвою (аргументи лише int). Будь-яка помилка: null. */
    private static Object call(Object target, String method, Object... intArgs) {
        if (target == null) {
            return null;
        }
        try {
            Class<?>[] types = new Class<?>[intArgs.length];
            for (int i = 0; i < intArgs.length; i++) {
                types[i] = int.class;
            }
            return target.getClass().getMethod(method, types).invoke(target, intArgs);
        } catch (Throwable t) {
            lastError = method + ": " + t;
            Log.w(TAG, "CVTE: " + lastError, t);
            return null;
        }
    }

    private static Object field(Object target, String name) throws Exception {
        return target.getClass().getField(name).get(target);
    }

    private static String enumName(Object value) {
        if (value instanceof Enum) {
            return ((Enum<?>) value).name();
        }
        return value == null ? "" : String.valueOf(value);
    }
}
