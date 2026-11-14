package ua.tvtest.hdmi;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvView;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Тестовий застосунок: показує, які входи (HDMI тощо) бачить Android,
 * і пробує вивести картинку з HDMI у власне вікно через TvView.
 * Якщо це працює, свій лаунчер зможе відкривати HDMI без штатного.
 */
public class MainActivity extends Activity {

    private static final String TAG = "HdmiTest";
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MAX_ACTIVITIES_PER_APP = 30;

    private static final String[] PACKAGE_KEYWORDS = {
            "tv", "launcher", "home", "hdmi", "source", "input", "live",
            "mtk", "mediatek", "realtek", "rtk", "player", "settings"
    };
    private static final String[] ACTIVITY_KEYWORDS = {
            "tv", "launcher", "home", "hdmi", "source", "input", "live",
            "main", "play", "passthrough", "signal", "setting"
    };

    private TvInputManager tvInputManager;
    private TvView tvView;
    private LinearLayout panel;
    private LinearLayout cvteButtons;
    private LinearLayout buttons;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ScrollView logScroll;
    private TextView logView;
    private TextView statusView;
    private TextView keyView;
    private boolean fullscreen = false;
    private final StringBuilder logText = new StringBuilder();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        logDeviceInfo();

        try {
            tvInputManager = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        } catch (Throwable t) {
            log("ПОМИЛКА: TvInputManager: " + t);
        }
        if (tvInputManager == null) {
            log("ПОМИЛКА: TvInputManager недоступний. Прошивка не дає стандартного доступу до входів.");
        } else {
            try {
                tvInputManager.registerCallback(inputCallback, new Handler(Looper.getMainLooper()));
            } catch (Throwable t) {
                log("Не вдалося підписатися на зміни входів: " + t);
            }
        }
        refreshInputs();
        connectCvte();
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            tvView.reset();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
        if (tvInputManager != null) {
            try {
                tvInputManager.unregisterCallback(inputCallback);
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(Color.rgb(16, 18, 24));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.addView(panel, new LinearLayout.LayoutParams(0, MATCH, 2f));

        panel.addView(text("HDMI тест", 22, Color.WHITE));
        keyView = text("Остання кнопка пульта: -", 13, Color.rgb(150, 160, 180));
        panel.addView(keyView);

        ScrollView buttonScroll = new ScrollView(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        cvteButtons = new LinearLayout(this);
        cvteButtons.setOrientation(LinearLayout.VERTICAL);
        column.addView(cvteButtons, new LinearLayout.LayoutParams(MATCH, WRAP));
        buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        column.addView(buttons, new LinearLayout.LayoutParams(MATCH, WRAP));
        buttonScroll.addView(column, new FrameLayout.LayoutParams(MATCH, WRAP));
        panel.addView(buttonScroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        TextView logTitle = text("Журнал (виберіть його і гортайте вгору/вниз):", 13,
                Color.rgb(150, 160, 180));
        logTitle.setPadding(0, dp(8), 0, dp(4));
        panel.addView(logTitle);

        logScroll = new ScrollView(this);
        logScroll.setFocusable(true);
        logScroll.setBackground(focusBackground(Color.rgb(24, 27, 36), Color.rgb(40, 60, 110)));
        logScroll.setPadding(dp(8), dp(6), dp(8), dp(6));
        logView = text("", 12, Color.rgb(210, 215, 225));
        logScroll.addView(logView, new FrameLayout.LayoutParams(MATCH, WRAP));
        panel.addView(logScroll, new LinearLayout.LayoutParams(MATCH, 0, 1.3f));

        FrameLayout videoFrame = new FrameLayout(this);
        videoFrame.setBackgroundColor(Color.BLACK);
        tvView = new TvView(this);
        tvView.setCallback(viewCallback);
        videoFrame.addView(tvView, new FrameLayout.LayoutParams(MATCH, MATCH));

        statusView = text("Виберіть вхід зліва", 16, Color.WHITE);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(24), dp(24), dp(24), dp(24));
        videoFrame.addView(statusView, new FrameLayout.LayoutParams(MATCH, MATCH));

        root.addView(videoFrame, new LinearLayout.LayoutParams(0, MATCH, 3f));
        setContentView(root);
    }

    private void refreshInputs() {
        buttons.removeAllViews();

        List<TvInputInfo> inputs = new ArrayList<>();
        if (tvInputManager != null) {
            try {
                inputs = tvInputManager.getTvInputList();
            } catch (Throwable t) {
                log("ПОМИЛКА: getTvInputList: " + t);
            }
        }
        log("Знайдено входів: " + inputs.size());

        for (final TvInputInfo info : inputs) {
            final String id = info.getId();
            String label = safeLabel(info);
            String type = typeName(info.getType());
            String state = stateName(safeState(id));
            String pkg = info.getServiceInfo() != null ? info.getServiceInfo().packageName : "?";
            log("- " + label + " | тип " + type + " | passthrough=" + info.isPassthroughInput()
                    + " | " + state + "\n    id: " + id + "\n    сервіс: " + pkg);

            addButton(label + "  (" + type + ", " + state + ")", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    tuneInView(id);
                }
            });
            addButton("    -> відкрити системою", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openViaSystem(id);
                }
            });
        }

        addButton("На весь екран (Назад - повернутись)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setFullscreen(true);
            }
        });
        addButton("Діагностика: лаунчери і ТВ-застосунки", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runDiagnostics();
            }
        });
        addButton("Знайти заводське меню", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runFactorySearch();
            }
        });
        addButton("Оновити список входів", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshInputs();
            }
        });

        focusFirstButton();
    }

    private void setFullscreen(boolean on) {
        fullscreen = on;
        panel.setVisibility(on ? View.GONE : View.VISIBLE);
        if (!on) {
            focusFirstButton();
        }
    }

    private void focusFirstButton() {
        for (LinearLayout container : new LinearLayout[]{cvteButtons, buttons}) {
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);
                if (child.isFocusable()) {
                    child.requestFocus();
                    return;
                }
            }
        }
    }

    // ---------------------------------------------------------------- CVTE (спосіб штатного лаунчера)

    private void connectCvte() {
        log("CVTE: підключаюсь до сервісу прошивки...");
        CvteTv.connect(this, new CvteTv.Ready() {
            @Override
            public void onReady(boolean connected) {
                if (!connected) {
                    log("ПОМИЛКА CVTE: " + CvteTv.lastError);
                    return;
                }
                log("CVTE: підключено.");
                worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        final List<CvteTv.Source> sources = CvteTv.sources();
                        final Integer current = CvteTv.currentId();
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                showCvteSources(sources, current);
                            }
                        });
                    }
                });
            }
        });
    }

    private void showCvteSources(List<CvteTv.Source> sources, Integer currentId) {
        cvteButtons.removeAllViews();
        log("CVTE: джерел " + sources.size() + ", поточне id=" + currentId
                + (sources.isEmpty() && !CvteTv.lastError.isEmpty() ? " (" + CvteTv.lastError + ")" : ""));

        TextView header = text("Спосіб CVTE (як у штатному лаунчері):", 14, Color.rgb(120, 255, 170));
        header.setPadding(0, dp(6), 0, 0);
        cvteButtons.addView(header);

        for (final CvteTv.Source s : sources) {
            log("  CVTE id=" + s.id + " | " + s.name + " | " + s.category + " | " + s.status);
            if (!s.isHdmi()) {
                continue;
            }
            String mark = currentId != null && currentId == s.id ? " - зараз" : "";
            addButtonTo(cvteButtons, "CVTE: " + s.name + " на весь екран  (" + s.status + mark + ")",
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            openCvteSource(s.id, s.name);
                        }
                    });
        }
        addButtonTo(cvteButtons, "CVTE: відкрити ТВ-екран (поточне джерело)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                log("CVTE: відкриваю ТВ-екран...");
                if (!CvteTv.openTvScreen(MainActivity.this)) {
                    log("ПОМИЛКА CVTE: " + CvteTv.lastError);
                }
            }
        });

        TextView other = text("Стандартний спосіб Android (для порівняння):", 14, Color.rgb(150, 160, 180));
        other.setPadding(0, dp(12), 0, 0);
        cvteButtons.addView(other);

        focusFirstButton();
    }

    private void openCvteSource(final int id, final String name) {
        log("CVTE: перемикаю на " + name + " (id=" + id + ")...");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final boolean switched = CvteTv.setSource(id);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        log("CVTE: перемкнуто = " + switched
                                + (switched ? "" : " (" + CvteTv.lastError + ")"));
                        if (!CvteTv.openTvScreen(MainActivity.this)) {
                            log("ПОМИЛКА CVTE: " + CvteTv.lastError);
                        }
                    }
                });
            }
        });
    }

    // ---------------------------------------------------------------- HDMI

    private void tuneInView(String inputId) {
        statusView.setVisibility(View.VISIBLE);
        statusView.setText("Підключаюсь до входу...");
        Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
        log("TvView.tune -> " + inputId);
        try {
            tvView.tune(inputId, uri);
        } catch (Throwable t) {
            showStatus("Помилка: " + t.getClass().getSimpleName());
            log("ПОМИЛКА: tune: " + t);
        }
    }

    private void openViaSystem(String inputId) {
        Intent intent = new Intent(Intent.ACTION_VIEW,
                TvContract.buildChannelUriForPassthroughInput(inputId));
        log("Система відкриє через: " + describe(getPackageManager().resolveActivity(intent, 0)));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            log("Немає застосунку, який відкриває цей вхід.");
        } catch (Throwable t) {
            log("ПОМИЛКА: startActivity: " + t);
        }
    }

    private final TvView.TvInputCallback viewCallback = new TvView.TvInputCallback() {
        @Override
        public void onConnectionFailed(String inputId) {
            showStatus("Не вдалося під'єднатися до входу");
            log("ПОМИЛКА: onConnectionFailed " + inputId);
        }

        @Override
        public void onDisconnected(String inputId) {
            showStatus("Вхід від'єднався");
            log("onDisconnected " + inputId);
        }

        @Override
        public void onVideoAvailable(String inputId) {
            statusView.setVisibility(View.GONE);
            log("OK: є відео з " + inputId);
        }

        @Override
        public void onVideoUnavailable(String inputId, int reason) {
            showStatus("Немає відео: " + videoReason(reason));
            log("onVideoUnavailable: " + videoReason(reason));
        }

        @Override
        public void onChannelRetuned(String inputId, Uri channelUri) {
            log("onChannelRetuned " + channelUri);
        }
    };

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String inputId, int state) {
            log("Стан входу " + inputId + " -> " + stateName(state));
        }

        @Override
        public void onInputAdded(String inputId) {
            log("Додано вхід " + inputId);
        }

        @Override
        public void onInputRemoved(String inputId) {
            log("Прибрано вхід " + inputId);
        }
    };

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            keyView.setText("Остання кнопка пульта: " + KeyEvent.keyCodeToString(code) + " (" + code + ")");

            if (fullscreen && code == KeyEvent.KEYCODE_BACK) {
                setFullscreen(false);
                return true;
            }
            if (code == KeyEvent.KEYCODE_TV_INPUT) {
                log("Натиснуто Source: наш застосунок отримав цю кнопку.");
                setFullscreen(false);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ---------------------------------------------------------------- diagnostics

    private void runDiagnostics() {
        PackageManager pm = getPackageManager();
        log("===== ДІАГНОСТИКА =====");

        log("--- Головні екрани (HOME):");
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        try {
            for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
                log("  " + describe(ri));
            }
            log("  Зараз головний: " + describe(pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)));
        } catch (Throwable t) {
            log("  помилка: " + t);
        }

        if (tvInputManager != null) {
            log("--- Хто відкриває входи на запит системи:");
            try {
                for (TvInputInfo info : tvInputManager.getTvInputList()) {
                    Intent view = new Intent(Intent.ACTION_VIEW,
                            TvContract.buildChannelUriForPassthroughInput(info.getId()));
                    log("  " + safeLabel(info) + " -> " + describe(pm.resolveActivity(view, 0)));
                }
            } catch (Throwable t) {
                log("  помилка: " + t);
            }
        }

        log("--- Застосунки, пов'язані з ТВ, і їхні екрани:");
        List<PackageInfo> packages;
        try {
            packages = pm.getInstalledPackages(0);
        } catch (Throwable t) {
            log("  помилка: " + t);
            packages = new ArrayList<>();
        }
        for (PackageInfo p : packages) {
            String pkgLower = p.packageName.toLowerCase(Locale.ROOT);
            if (pkgLower.contains("inputmethod") || !containsAny(pkgLower, PACKAGE_KEYWORDS)) {
                continue;
            }
            String appLabel = p.applicationInfo != null
                    ? String.valueOf(p.applicationInfo.loadLabel(pm)) : "?";
            log("[" + appLabel + "] " + p.packageName);
            try {
                PackageInfo full = pm.getPackageInfo(p.packageName, PackageManager.GET_ACTIVITIES);
                if (full.activities == null) {
                    continue;
                }
                int shown = 0;
                for (ActivityInfo a : full.activities) {
                    if (!containsAny(a.name.toLowerCase(Locale.ROOT), ACTIVITY_KEYWORDS)) {
                        continue;
                    }
                    if (shown++ >= MAX_ACTIVITIES_PER_APP) {
                        log("    ... (ще є)");
                        break;
                    }
                    String shortName = a.name.startsWith(p.packageName)
                            ? a.name.substring(p.packageName.length()) : a.name;
                    log("    " + shortName + (a.exported ? "" : "  (прихована)"));
                }
            } catch (Throwable t) {
                log("    не вдалося прочитати: " + t.getClass().getSimpleName());
            }
        }
        log("===== КІНЕЦЬ ДІАГНОСТИКИ =====");
    }

    /** Усі екрани пакетів CVTE / MediaTek і всього, що схоже на заводське меню або налагодження. */
    private void runFactorySearch() {
        PackageManager pm = getPackageManager();
        String[] keywords = {"cvte", "fac", "factory", "debug", "adb", "mediatek", "mtk", "service", "menu"};
        log("===== ПОШУК ЗАВОДСЬКОГО МЕНЮ =====");
        List<PackageInfo> packages;
        try {
            packages = pm.getInstalledPackages(0);
        } catch (Throwable t) {
            log("помилка: " + t);
            return;
        }
        for (PackageInfo p : packages) {
            String pkgLower = p.packageName.toLowerCase(Locale.ROOT);
            if (!containsAny(pkgLower, keywords)) {
                continue;
            }
            String appLabel = p.applicationInfo != null ? String.valueOf(p.applicationInfo.loadLabel(pm)) : "?";
            try {
                PackageInfo full = pm.getPackageInfo(p.packageName, PackageManager.GET_ACTIVITIES);
                int count = full.activities == null ? 0 : full.activities.length;
                log("[" + appLabel + "] " + p.packageName + "  (екранів: " + count + ")");
                if (full.activities == null) {
                    continue;
                }
                int shown = 0;
                for (ActivityInfo a : full.activities) {
                    if (shown++ >= 40) {
                        log("    ... (ще є)");
                        break;
                    }
                    String shortName = a.name.startsWith(p.packageName)
                            ? a.name.substring(p.packageName.length()) : a.name;
                    log("    " + shortName + (a.exported ? "" : "  (прихована)"));
                }
            } catch (Throwable t) {
                log("[" + appLabel + "] " + p.packageName + "  не вдалося прочитати: " + t.getClass().getSimpleName());
            }
        }
        log("===== КІНЕЦЬ ПОШУКУ =====");
    }

    private void logDeviceInfo() {
        log("Пристрій: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.BRAND + ")");
        log("Android " + Build.VERSION.RELEASE + ", API " + Build.VERSION.SDK_INT
                + ", плата " + Build.BOARD + " / " + Build.HARDWARE);
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            log("Пам'ять: всього " + (mi.totalMem / (1024 * 1024)) + " МБ, вільно "
                    + (mi.availMem / (1024 * 1024)) + " МБ");
        } catch (Throwable ignored) {
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        log("Екран: " + dm.widthPixels + "x" + dm.heightPixels + ", density " + dm.densityDpi);
        PackageManager pm = getPackageManager();
        log("live_tv=" + pm.hasSystemFeature("android.software.live_tv")
                + ", leanback=" + pm.hasSystemFeature("android.software.leanback"));
    }

    // ---------------------------------------------------------------- helpers

    private void showStatus(String message) {
        statusView.setVisibility(View.VISIBLE);
        statusView.setText(message);
    }

    private void log(String message) {
        Log.i(TAG, message);
        logText.append(timeFormat.format(new Date())).append("  ").append(message).append('\n');
        if (logView != null) {
            logView.setText(logText);
            logScroll.post(new Runnable() {
                @Override
                public void run() {
                    logScroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    private int safeState(String inputId) {
        try {
            return tvInputManager.getInputState(inputId);
        } catch (Throwable t) {
            return -1;
        }
    }

    private String safeLabel(TvInputInfo info) {
        try {
            CharSequence label = info.loadLabel(this);
            return label != null ? label.toString() : info.getId();
        } catch (Throwable t) {
            return info.getId();
        }
    }

    private static String describe(ResolveInfo ri) {
        if (ri == null || ri.activityInfo == null) {
            return "нікого";
        }
        return ri.activityInfo.packageName + "/" + ri.activityInfo.name;
    }

    private static boolean containsAny(String s, String[] words) {
        for (String w : words) {
            if (s.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private static String typeName(int type) {
        switch (type) {
            case 0: return "тюнер";
            case 1000: return "інше";
            case 1001: return "композит";
            case 1002: return "S-Video";
            case 1003: return "SCART";
            case 1004: return "компонент";
            case 1005: return "VGA";
            case 1006: return "DVI";
            case 1007: return "HDMI";
            case 1008: return "DisplayPort";
            default: return "тип " + type;
        }
    }

    private static String stateName(int state) {
        switch (state) {
            case 0: return "підключено";
            case 1: return "підключено, сплячий";
            case 2: return "не підключено";
            default: return "стан невідомий";
        }
    }

    private static String videoReason(int reason) {
        switch (reason) {
            case 0: return "невідома причина";
            case 1: return "налаштування";
            case 2: return "слабкий сигнал";
            case 3: return "буферизація";
            case 4: return "лише звук";
            default: return "код " + reason;
        }
    }

    private TextView text(String value, float sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        return tv;
    }

    private void addButton(String label, View.OnClickListener listener) {
        addButtonTo(buttons, label, listener);
    }

    private void addButtonTo(LinearLayout container, String label, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setBackground(focusBackground(Color.rgb(40, 44, 56), Color.rgb(64, 120, 255)));
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setFocusable(true);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(6);
        container.addView(b, lp);
    }

    private StateListDrawable focusBackground(int normal, int focused) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, rounded(focused));
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(focused));
        states.addState(new int[]{}, rounded(normal));
        return states;
    }

    private GradientDrawable rounded(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(8));
        return d;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
