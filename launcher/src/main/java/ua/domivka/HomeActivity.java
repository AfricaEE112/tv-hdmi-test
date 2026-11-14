package ua.domivka;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * «Домівка»: легкий головний екран для телевізора Bravis (CVTE / MediaTek, Android 7).
 *
 * Консоль і інші HDMI відкриваються через сервіс прошивки CVTE та рідний повноекранний
 * ТВ-екран (див. CvteTv), бо стандартний TvView на цій прошивці відео не показує.
 */
public class HomeActivity extends Activity {

    // ---- налаштування
    private static final String P_PINNED = "pinned";
    private static final String P_HIDDEN = "hidden";
    private static final String P_CONSOLE = "console_source";
    private static final String P_AUTO = "auto_switch";
    private static final String P_DEFAULTS_DONE = "defaults_done";
    private static final String P_LAUNCH_PREFIX = "launch_";

    // ---- розміри (dp); екран телевізора 1280x720 dp
    private static final int SIDE = 64;
    private static final int TILE_W = 200;
    private static final int TILE_H = 120;
    private static final int TILE_GAP = 8;      // між обгортками (підпис ширший за плитку на 20dp)
    private static final int HERO_H = 300;
    private static final int APP_COLUMNS = 5;

    // ---- кольори
    private static final int TEXT = Color.WHITE;
    private static final int TEXT_DIM = Color.argb(190, 225, 232, 245);
    private static final int TEXT_HINT = Color.argb(150, 225, 232, 245);
    private static final int CARD = Color.argb(110, 255, 255, 255);
    private static final int GLASS = Color.argb(70, 255, 255, 255);
    private static final int OK_GREEN = Color.rgb(110, 240, 160);
    private static final int WARM = Color.rgb(255, 196, 120);
    private static final int ALERT_RED = Color.rgb(255, 92, 92);

    private static final String TAG_CONSOLE = "console";
    private static final String TAG_FIRE = "fire";
    private static final String TAG_WEATHER = "weather";
    private static final String TAG_YOUTUBE = "youtube";
    private static final String P_SAVER = "saver_minutes";
    private static final int[] SAVER_OPTIONS = {5, 10, 20, 30, 0};

    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TvInputManager tvInputs;
    private boolean resumed;

    private FrameLayout rootView;
    private int wallpaperBucket = -1;
    private ScrollView scroll;
    private TextView sleepInfo;
    private TextView alertPill;
    private LinearLayout heroPanel;

    // вітрина (як «Top Shelf» на Apple TV): показує те, на чому зараз фокус
    private FrameLayout hero;
    private View heroGlow;
    private FireView heroFire;
    private ImageView heroArt;
    private LinearLayout heroText;
    private TextView heroTitle;
    private TextView heroSub;
    private LinearLayout heroForecast;
    private TextView heroHint;
    private final Map<View, Runnable> heroActions = new HashMap<>();

    private ImageView weatherMiniIcon;
    private TextView weatherMiniText;
    private Weather.Data weather;
    private boolean weatherLoading;
    private long lastInteraction;

    /** Заставка: якщо на Домівці довго нічого не натискали, запускаємо тихий камін. */
    private final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            int minutes = prefs.getInt(P_SAVER, 10);
            if (resumed && minutes > 0
                    && System.currentTimeMillis() - lastInteraction >= minutes * 60_000L) {
                startActivity(new Intent(HomeActivity.this, FireplaceActivity.class)
                        .putExtra(FireplaceActivity.EXTRA_SCREENSAVER, true));
                return;
            }
            updateSleepInfo();
            updateAlertPill();
            refreshWeather(false);
            if (resumed) {
                Updater.checkAndPrompt(HomeActivity.this, false);
            }
            main.postDelayed(this, 30_000);
        }
    };
    private LinearLayout mainRow;
    private LinearLayout sourcesRow;
    private GridLayout appsGrid;

    private List<AppEntry> apps = new ArrayList<>();
    private List<CvteTv.Source> sources = new ArrayList<>();
    /** id джерела CVTE -> id входу Android (для стану «підключено»). */
    private final Map<Integer, String> sourceInputs = new HashMap<>();

    static final class AppEntry {
        final String pkg;
        final ComponentName component;
        final String label;
        final Drawable banner;
        final Drawable icon;
        final boolean leanback;
        /** Основний колір іконки: фон плитки для застосунків без банера. */
        final int color;

        AppEntry(String pkg, ComponentName component, String label, Drawable banner, Drawable icon,
                 boolean leanback, int color) {
            this.pkg = pkg;
            this.component = component;
            this.label = label;
            this.banner = banner;
            this.icon = icon;
            this.leanback = leanback;
            this.color = color;
        }
    }

    // ================================================================ життєвий цикл

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        buildUi();

        try {
            tvInputs = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tvInputs != null) {
                tvInputs.registerCallback(inputCallback, main);
            }
        } catch (Throwable ignored) {
            tvInputs = null;
        }

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packageReceiver, f);

        weather = Weather.cached(this);
        updateWeatherMini();
        rebuildMainRow();
        rebuildSources();
        reloadApps();
        loadSources();
        refreshWeather(false);
        main.post(this::focusConsole);
        Alerts.listener = this::onAlertChanged;
        Alerts.start(this);
        main.postDelayed(() -> Updater.showWhatsNewIfUpdated(this), 1500);
        main.postDelayed(() -> Updater.checkAndPrompt(this, false), 8000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        App.homeResumed = true;
        AlertOverlay.update(this);
        updateAlertPill();
        updateWallpaper();
        refreshSourceStates();
        refreshWeather(false);
        View focused = getCurrentFocus();
        if (focused != null && heroActions.containsKey(focused)) {
            heroActions.get(focused).run();
        }
        updateSleepInfo();
        lastInteraction = System.currentTimeMillis();
        main.removeCallbacks(idleCheck);
        main.postDelayed(idleCheck, 30_000);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        lastInteraction = System.currentTimeMillis();
        return super.dispatchKeyEvent(event);
    }

    private void updateSleepInfo() {
        if (sleepInfo == null) {
            return;
        }
        String st = SleepTimer.status(this);
        sleepInfo.setText(st == null ? "" : st);
        sleepInfo.setVisibility(st == null ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        App.homeResumed = false;
        AlertOverlay.update(this);
        main.removeCallbacks(idleCheck);
        if (heroFire != null) {
            heroFire.stop();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Натиснули «Додому», коли вже вдома: повертаємось нагору
        scrollToTop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (Alerts.listener != null) {
            Alerts.listener = null;
        }
        try {
            unregisterReceiver(packageReceiver);
        } catch (Throwable ignored) {
        }
        if (tvInputs != null) {
            try {
                tvInputs.unregisterCallback(inputCallback);
            } catch (Throwable ignored) {
            }
        }
        worker.shutdown();
    }

    @Override
    public void onBackPressed() {
        // Головний екран нікуди не закривається: «Назад» просто повертає нагору
        scrollToTop();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showMenuFor(getCurrentFocus());
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_TV_INPUT) {
            if (sourcesRow.getChildCount() > 0) {
                sourcesRow.getChildAt(0).requestFocus();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reloadApps();
        }
    };

    // ================================================================ інтерфейс

    private void buildUi() {
        rootView = new FrameLayout(this);
        updateWallpaper();

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        rootView.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(dp(SIDE), dp(26), dp(SIDE), dp(70));
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // ---- верхня смужка: дата | погода, годинник, таймер сну, налаштування
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        style(date, 18, TEXT_DIM, false);
        top.addView(date, new LinearLayout.LayoutParams(0, -2, 1f));

        alertPill = text("", 15, TEXT, true);
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0xE6C62828);
        pillBg.setCornerRadius(dp(16));
        alertPill.setBackground(pillBg);
        alertPill.setPadding(dp(14), dp(4), dp(14), dp(5));
        alertPill.setVisibility(View.GONE);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
        ap.rightMargin = dp(22);
        top.addView(alertPill, ap);

        weatherMiniIcon = new ImageView(this);
        top.addView(weatherMiniIcon, new LinearLayout.LayoutParams(dp(26), dp(26)));
        weatherMiniText = text("", 18, TEXT, false);
        weatherMiniText.setPadding(dp(8), 0, dp(24), 0);
        top.addView(weatherMiniText);

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("HH:mm");
        clock.setFormat24Hour("HH:mm");
        style(clock, 24, TEXT, false);
        top.addView(clock);
        sleepInfo = text("", 14, WARM, false);
        sleepInfo.setPadding(dp(14), 0, 0, 0);
        sleepInfo.setVisibility(View.GONE);
        top.addView(sleepInfo);

        View sleep = iconButton(R.drawable.ic_moon, "Таймер сну", () -> {
            String st = SleepTimer.status(this);
            setHero("Таймер сну", st != null ? "Телевізор: " + st
                    : "Телевізор вимкнеться сам через 10–120 хвилин", "OK: вибрати час");
            setHeroArt(getDrawable(R.drawable.ic_moon), 150, 150, false);
            setHeroGlow(0x553D5AFE);
        });
        sleep.setOnClickListener(v -> SleepTimer.show(this, this::updateSleepInfo));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(46), dp(46));
        mp.leftMargin = dp(24);
        top.addView(sleep, mp);

        View settings = iconButton(R.drawable.ic_tune, "Налаштування", () -> {
            setHero("Налаштування", "Пам'ять, заставка, погода, приховані застосунки, таймер сну",
                    "OK: відкрити");
            setHeroArt(getDrawable(R.drawable.ic_tune), 150, 150, false);
            setHeroGlow(0x557C5CFF);
        });
        settings.setOnClickListener(v -> showLauncherSettings());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(46), dp(46));
        sp.leftMargin = dp(12);
        top.addView(settings, sp);
        content.addView(top);

        // ---- вітрина
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        heroGlow = new View(this);
        hero.addView(heroGlow, new FrameLayout.LayoutParams(-1, -1));

        heroFire = new FireView(this);
        heroFire.setBare(true);
        heroFire.setVisibility(View.GONE);
        hero.addView(heroFire, new FrameLayout.LayoutParams(dp(520), -1, Gravity.END));

        heroArt = new ImageView(this);
        heroArt.setVisibility(View.GONE);
        hero.addView(heroArt, new FrameLayout.LayoutParams(dp(384), dp(216), Gravity.END | Gravity.CENTER_VERTICAL));

        heroPanel = new LinearLayout(this);
        heroPanel.setOrientation(LinearLayout.VERTICAL);
        heroPanel.setPadding(dp(24), dp(18), dp(24), dp(20));
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(Color.argb(46, 255, 255, 255));
        panelBg.setStroke(dp(1), Color.argb(40, 255, 255, 255));
        panelBg.setCornerRadius(dp(22));
        heroPanel.setBackground(panelBg);
        heroPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL);
        pp.rightMargin = dp(24);
        hero.addView(heroPanel, pp);

        heroText = new LinearLayout(this);
        heroText.setOrientation(LinearLayout.VERTICAL);
        heroText.setGravity(Gravity.CENTER_VERTICAL);
        heroTitle = text("", 46, TEXT, true);
        heroTitle.setSingleLine(true);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroText.addView(heroTitle);
        heroSub = text("", 19, TEXT_DIM, false);
        heroSub.setMaxLines(2);
        heroSub.setPadding(0, dp(6), 0, 0);
        heroText.addView(heroSub);
        heroForecast = new LinearLayout(this);
        heroForecast.setOrientation(LinearLayout.HORIZONTAL);
        heroForecast.setPadding(0, dp(18), 0, 0);
        heroForecast.setVisibility(View.GONE);
        heroText.addView(heroForecast);
        heroHint = text("", 14, TEXT_HINT, false);
        heroHint.setPadding(0, dp(18), 0, 0);
        heroText.addView(heroHint);
        hero.addView(heroText, new FrameLayout.LayoutParams(dp(660), -1, Gravity.START));
        content.addView(hero, new LinearLayout.LayoutParams(-1, dp(HERO_H)));

        // ---- головний ряд плиток
        mainRow = row();
        content.addView(horizontal(mainRow));

        // ---- усі застосунки
        content.addView(sectionTitle("Застосунки"));
        appsGrid = new GridLayout(this);
        appsGrid.setColumnCount(APP_COLUMNS);
        appsGrid.setClipChildren(false);
        appsGrid.setClipToPadding(false);
        content.addView(appsGrid, new LinearLayout.LayoutParams(-1, -2));

        // ---- джерела сигналу
        content.addView(sectionTitle("Джерела"));
        sourcesRow = row();
        content.addView(horizontal(sourcesRow));

        setContentView(rootView);
    }

    private void scrollToTop() {
        scroll.smoothScrollTo(0, 0);
        focusConsole();
    }

    private void focusConsole() {
        View console = findByKey(mainRow, TAG_CONSOLE);
        if (console != null) {
            console.requestFocus();
        }
    }

    // ---------------------------------------------------------------- Головне

    private void rebuildMainRow() {
        String focusKey = focusedKey();
        mainRow.removeAllViews();
        clearHeroActions(mainRow);

        mainRow.addView(consoleTile(), wrapParams());

        FrameLayout fire = iconCard(new int[]{0xFFFF9A3C, 0xFFB3261E}, R.drawable.ic_fire, null);
        fire.setTag(TAG_FIRE);
        fire.setOnClickListener(v -> startActivity(new Intent(this, FireplaceActivity.class)));
        mainRow.addView(tileWrap(fire, "Камін", this::heroFireplace), wrapParams());

        FrameLayout w = iconCard(new int[]{0xFF4FA3FF, 0xFF2747B8},
                weather != null ? Weather.icon(weather.code, weather.isDay) : R.drawable.ic_w_partly,
                weather != null ? Weather.temp(weather.temp) : null);
        w.setTag(TAG_WEATHER);
        w.setOnClickListener(v -> chooseCity());
        mainRow.addView(tileWrap(w, "Погода", this::heroWeather), wrapParams());

        if (findYoutube() == null) {
            FrameLayout yt = iconCard(new int[]{0xFFE53935, 0xFF8E1B2C}, R.drawable.ic_play, null);
            yt.setTag(TAG_YOUTUBE);
            yt.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("YouTube без реклами")
                    .setMessage("Скачайте SmartTube з офіційного сайту розробника, скиньте APK на флешку "
                            + "і встановіть. Плитка з'явиться тут сама.")
                    .setPositiveButton("Зрозуміло", null)
                    .show());
            mainRow.addView(tileWrap(yt, "YouTube", () -> {
                setHero("YouTube", "Встановіть SmartTube: YouTube без реклами, зручний для пульта",
                        "OK: як встановити");
                setHeroArt(getDrawable(R.drawable.ic_play), 150, 150, false);
                setHeroGlow(0x66E53935);
            }), wrapParams());
        }

        for (String pkg : pinned()) {
            AppEntry app = findApp(pkg);
            if (app != null) {
                mainRow.addView(appTile(app), wrapParams());
            }
        }
        restoreFocus(mainRow, focusKey);
    }

    private String consoleSubtitle() {
        int id = consoleSourceId();
        CvteTv.Source src = findSource(id);
        if (src == null) {
            return sources.isEmpty() ? "натисніть, щоб відкрити" : "виберіть HDMI в меню";
        }
        return src.name + "  ·  " + stateText(id);
    }

    private View consoleTile() {
        FrameLayout card = iconCard(new int[]{0xFFFF2E63, 0xFF6A1FB0}, R.drawable.ic_gamepad, null);
        card.setTag(TAG_CONSOLE);
        card.setOnClickListener(v -> openConsole());
        card.setOnLongClickListener(v -> {
            showConsoleMenu();
            return true;
        });
        return tileWrap(card, "Консоль", this::heroConsole);
    }

    /** Плитка застосунку: банер (для Android TV застосунків) або іконка на фоні її кольору. */
    private View appTile(AppEntry app) {
        FrameLayout card;
        if (app.banner != null) {
            card = baseCard(new int[]{0xFF222833, 0xFF222833});
            ImageView banner = new ImageView(this);
            banner.setImageDrawable(app.banner);
            banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(banner, new FrameLayout.LayoutParams(-1, -1));
        } else {
            card = baseCard(new int[]{shade(app.color, 0.62f), shade(app.color, 0.38f)});
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.icon);
            card.addView(icon, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));
        }
        card.setTag(app);
        card.setOnClickListener(v -> launch(app));
        card.setOnLongClickListener(v -> {
            showAppMenu(app);
            return true;
        });
        return tileWrap(card, app.label, () -> heroApp(app));
    }

    // ---------------------------------------------------------------- Джерела

    private void loadSources() {
        CvteTv.connect(this, connected -> {
            if (!connected) {
                rebuildSources();
                return;
            }
            worker.execute(() -> {
                List<CvteTv.Source> list = CvteTv.sources();
                main.post(() -> {
                    sources = list;
                    mapSourcesToInputs();
                    if (consoleSourceId() < 0) {
                        guessConsole();
                    }
                    rebuildSources();
                    rebuildMainRow();
                });
            });
        });
    }

    private void rebuildSources() {
        String focusKey = focusedKey();
        sourcesRow.removeAllViews();
        clearHeroActions(sourcesRow);
        for (CvteTv.Source s : sources) {
            boolean tv = isTvSource(s);
            if (!s.isHdmi() && !tv) {
                continue; // AV, VGA, YPbPr на цьому телевізорі не потрібні
            }
            final String title = tv ? "Телебачення" : s.name;
            String state = tv ? "антена" : stateText(s.id);
            final String sub = s.id == consoleSourceId() ? "консоль  ·  " + state : state;
            final int iconRes = tv ? R.drawable.ic_tv : R.drawable.ic_hdmi;
            View chip = chip(iconRes, title, sub, !tv && isConnected(s.id), () -> {
                setHero(title, sub.substring(0, 1).toUpperCase(Locale.ROOT) + sub.substring(1),
                        "OK: перейти на цей вхід   ·   Menu: вибрати HDMI для консолі");
                setHeroArt(getDrawable(iconRes), 150, 150, false);
                setHeroGlow(0x5538B8B0);
            });
            chip.setTag("src:" + s.id);
            final int id = s.id;
            chip.setOnClickListener(v -> openSource(id));
            chip.setOnLongClickListener(v -> {
                showConsoleMenu();
                return true;
            });
            sourcesRow.addView(chip, chipParams());
        }
        if (sourcesRow.getChildCount() == 0) {
            View chip = chip(R.drawable.ic_tv, "ТВ-екран", "поточне джерело", false, () -> {
                setHero("ТВ-екран", "Відкрити поточне джерело сигналу", "OK: відкрити");
                setHeroArt(getDrawable(R.drawable.ic_tv), 150, 150, false);
                setHeroGlow(0x5538B8B0);
            });
            chip.setOnClickListener(v -> openSource(-1));
            sourcesRow.addView(chip, chipParams());
        }
        restoreFocus(sourcesRow, focusKey);
    }

    private static boolean isTvSource(CvteTv.Source s) {
        String c = s.category;
        return c.equals("INPUTSOURCE_TV") || c.equals("INPUTSOURCE_ATV") || c.startsWith("INPUTSOURCE_DVB")
                || c.equals("INPUTSOURCE_DTMB") || c.equals("INPUTSOURCE_ATSC");
    }

    /** HDMI з прошивки CVTE зіставляємо з HDMI-входами Android: за назвою, а якщо ні, то за порядком. */
    private void mapSourcesToInputs() {
        sourceInputs.clear();
        if (tvInputs == null) {
            return;
        }
        List<TvInputInfo> hdmiInputs = new ArrayList<>();
        try {
            for (TvInputInfo info : tvInputs.getTvInputList()) {
                if (info.getType() == TvInputInfo.TYPE_HDMI) {
                    hdmiInputs.add(info);
                }
            }
        } catch (Throwable ignored) {
            return;
        }
        Collections.sort(hdmiInputs, (a, b) -> a.getId().compareTo(b.getId()));
        List<CvteTv.Source> hdmiSources = new ArrayList<>();
        for (CvteTv.Source s : sources) {
            if (s.isHdmi()) {
                hdmiSources.add(s);
            }
        }
        Collections.sort(hdmiSources, (a, b) -> Integer.compare(a.id, b.id));

        for (int i = 0; i < hdmiSources.size(); i++) {
            CvteTv.Source s = hdmiSources.get(i);
            String match = null;
            for (TvInputInfo info : hdmiInputs) {
                String label = String.valueOf(info.loadLabel(this));
                if (normalize(label).equals(normalize(s.name))) {
                    match = info.getId();
                    break;
                }
            }
            if (match == null && i < hdmiInputs.size()) {
                match = hdmiInputs.get(i).getId();
            }
            if (match != null) {
                sourceInputs.put(s.id, match);
            }
        }
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private boolean isConnected(int sourceId) {
        String input = sourceInputs.get(sourceId);
        if (input == null || tvInputs == null) {
            return false;
        }
        try {
            return tvInputs.getInputState(input) == TvInputManager.INPUT_STATE_CONNECTED;
        } catch (Throwable t) {
            return false;
        }
    }

    private String stateText(int sourceId) {
        if (!sourceInputs.containsKey(sourceId)) {
            return "HDMI";
        }
        return isConnected(sourceId) ? "підключено" : "немає сигналу";
    }

    /** Оновити стан «підключено» без перебудови головного ряду (щоб не смикався фокус). */
    private void refreshSourceStates() {
        if (sources.isEmpty()) {
            return;
        }
        rebuildSources();
        View f = getCurrentFocus();
        if (f != null && TAG_CONSOLE.equals(f.getTag())) {
            heroConsole();
        }
    }

    /** Консолью вважаємо HDMI, до якого зараз щось підключено. */
    private void guessConsole() {
        for (CvteTv.Source s : sources) {
            if (s.isHdmi() && isConnected(s.id)) {
                prefs.edit().putInt(P_CONSOLE, s.id).apply();
                return;
            }
        }
    }

    private int consoleSourceId() {
        return prefs.getInt(P_CONSOLE, -1);
    }

    private CvteTv.Source findSource(int id) {
        for (CvteTv.Source s : sources) {
            if (s.id == id) {
                return s;
            }
        }
        return null;
    }

    private Integer sourceForInput(String inputId) {
        for (Map.Entry<Integer, String> e : sourceInputs.entrySet()) {
            if (e.getValue().equals(inputId)) {
                return e.getKey();
            }
        }
        return null;
    }

    private final TvInputManager.TvInputCallback inputCallback = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String inputId, int state) {
            refreshSourceStates();
            if (state != TvInputManager.INPUT_STATE_CONNECTED || !resumed
                    || !prefs.getBoolean(P_AUTO, true)) {
                return;
            }
            Integer src = sourceForInput(inputId);
            if (src == null) {
                return;
            }
            if (consoleSourceId() < 0) {
                prefs.edit().putInt(P_CONSOLE, src).apply();
            }
            if (src == consoleSourceId()) {
                toast("Консоль увімкнено, перемикаю");
                openSource(src);
            }
        }
    };

    private void openConsole() {
        int id = consoleSourceId();
        if (id < 0) {
            guessConsole();
            id = consoleSourceId();
        }
        if (id < 0 && !sources.isEmpty()) {
            showConsoleMenu();
            return;
        }
        openSource(id);
    }

    /** Перемкнути джерело через прошивку CVTE і відкрити рідний повноекранний ТВ-екран. */
    private void openSource(int id) {
        CvteTv.connect(this, connected -> {
            if (!connected) {
                // Сервіс недоступний: хоча б відкриваємо ТВ-екран з поточним джерелом
                if (!CvteTv.openTvScreen(this)) {
                    toast("Не вдалося відкрити ТВ-екран");
                }
                return;
            }
            worker.execute(() -> {
                if (id >= 0) {
                    CvteTv.setSource(id);
                }
                main.post(() -> {
                    if (!CvteTv.openTvScreen(this)) {
                        toast("Не вдалося відкрити ТВ-екран");
                    }
                });
            });
        });
    }

    private void showConsoleMenu() {
        final List<CvteTv.Source> hdmi = new ArrayList<>();
        for (CvteTv.Source s : sources) {
            if (s.isHdmi()) {
                hdmi.add(s);
            }
        }
        boolean auto = prefs.getBoolean(P_AUTO, true);
        List<String> items = new ArrayList<>();
        for (CvteTv.Source s : hdmi) {
            String mark = s.id == consoleSourceId() ? "  ✓" : "";
            items.add("Консоль на " + s.name + "  (" + stateText(s.id) + ")" + mark);
        }
        items.add("Автоперемикання на консоль: " + (auto ? "увімкнено" : "вимкнено"));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Консоль")
                .setItems(items.toArray(new String[0]), (d, which) -> {
                    if (which < hdmi.size()) {
                        prefs.edit().putInt(P_CONSOLE, hdmi.get(which).id).apply();
                    } else {
                        prefs.edit().putBoolean(P_AUTO, !auto).apply();
                        toast(!auto ? "Автоперемикання увімкнено" : "Автоперемикання вимкнено");
                    }
                    rebuildMainRow();
                    rebuildSources();
                })
                .show();
    }

    // ---------------------------------------------------------------- Застосунки

    private void reloadApps() {
        worker.execute(() -> {
            final List<AppEntry> list = queryApps();
            main.post(() -> {
                apps = list;
                applyDefaultPins();
                rebuildMainRow();
                rebuildApps();
            });
        });
    }

    private List<AppEntry> queryApps() {
        PackageManager pm = getPackageManager();
        Map<String, AppEntry> map = new LinkedHashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, true, map);
        collect(pm, Intent.CATEGORY_LAUNCHER, false, map);
        List<AppEntry> list = new ArrayList<>(map.values());
        final Collator collator = Collator.getInstance(new Locale("uk"));
        final Map<String, Integer> counts = new HashMap<>();
        for (AppEntry a : list) {
            counts.put(a.pkg, prefs.getInt(P_LAUNCH_PREFIX + a.pkg, 0));
        }
        Collections.sort(list, (a, b) -> {
            int c = Integer.compare(counts.get(b.pkg), counts.get(a.pkg));
            return c != 0 ? c : collator.compare(a.label, b.label);
        });
        return list;
    }

    private void collect(PackageManager pm, String category, boolean leanback, Map<String, AppEntry> out) {
        List<ResolveInfo> found;
        try {
            found = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0);
        } catch (Throwable t) {
            return;
        }
        for (ResolveInfo ri : found) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ai.packageName.equals(getPackageName()) || out.containsKey(ai.packageName)) {
                continue;
            }
            Drawable banner = null;
            try {
                banner = ai.loadBanner(pm);
                if (banner == null && ai.applicationInfo != null) {
                    banner = ai.applicationInfo.loadBanner(pm);
                }
            } catch (Throwable ignored) {
            }
            Drawable icon;
            try {
                icon = ri.loadIcon(pm);
            } catch (Throwable t) {
                icon = null;
            }
            String label = String.valueOf(ri.loadLabel(pm));
            int color = banner == null && icon != null ? iconColor(icon) : 0xFF2A3350;
            out.put(ai.packageName, new AppEntry(ai.packageName,
                    new ComponentName(ai.packageName, ai.name), label, banner, icon, leanback, color));
        }
    }

    private void rebuildApps() {
        String focusKey = focusedKey();
        appsGrid.removeAllViews();
        clearHeroActions(appsGrid);
        Set<String> hidden = hidden();
        for (AppEntry app : apps) {
            if (hidden.contains(app.pkg)) {
                continue;
            }
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.rightMargin = dp(TILE_GAP);
            lp.bottomMargin = dp(6);
            appsGrid.addView(appTile(app), lp);
        }
        restoreFocus(appsGrid, focusKey);
    }

    private void launch(AppEntry app) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(app.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
            String key = P_LAUNCH_PREFIX + app.pkg;
            prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply();
        } catch (Throwable t) {
            toast("Не вдалося відкрити «" + app.label + "»");
        }
    }

    private void showAppMenu(final AppEntry app) {
        final List<String> pins = pinned();
        final boolean isPinned = pins.contains(app.pkg);
        final List<String> items = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        items.add(isPinned ? "Прибрати з «Головне»" : "Додати в «Головне»");
        actions.add(() -> {
            if (isPinned) {
                pins.remove(app.pkg);
            } else {
                pins.add(app.pkg);
            }
            savePinned(pins);
            rebuildMainRow();
        });
        if (isPinned) {
            final int index = pins.indexOf(app.pkg);
            if (index > 0) {
                items.add("Посунути ліворуч");
                actions.add(() -> {
                    Collections.swap(pins, index, index - 1);
                    savePinned(pins);
                    rebuildMainRow();
                });
            }
            if (index < pins.size() - 1) {
                items.add("Посунути праворуч");
                actions.add(() -> {
                    Collections.swap(pins, index, index + 1);
                    savePinned(pins);
                    rebuildMainRow();
                });
            }
        }
        items.add("Сховати");
        actions.add(() -> {
            Set<String> h = hidden();
            h.add(app.pkg);
            prefs.edit().putStringSet(P_HIDDEN, h).apply();
            pins.remove(app.pkg);
            savePinned(pins);
            rebuildMainRow();
            rebuildApps();
            toast("Сховано. Повернути можна в налаштуваннях Домівки");
        });
        items.add("Видалити застосунок");
        actions.add(() -> {
            try {
                startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg)));
            } catch (Throwable t) {
                toast("Цей застосунок видалити не можна");
            }
        });

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(app.label)
                .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void showMenuFor(View v) {
        if (v == null) {
            return;
        }
        Object tag = v.getTag();
        if (tag instanceof AppEntry) {
            showAppMenu((AppEntry) tag);
        } else if (TAG_FIRE.equals(tag)) {
            chooseFireplace();
        } else if (TAG_WEATHER.equals(tag)) {
            chooseAlertMode();
        } else if (TAG_CONSOLE.equals(tag) || (tag instanceof String && ((String) tag).startsWith("src:"))) {
            showConsoleMenu();
        } else {
            showLauncherSettings();
        }
    }

    private void showLauncherSettings() {
        final Set<String> hidden = hidden();
        final boolean auto = prefs.getBoolean(P_AUTO, true);
        final boolean powerOn = prefs.getBoolean(App.P_POWER_ON_HOME, true);
        final int saver = prefs.getInt(P_SAVER, 10);
        final List<String> items = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        items.add("Звільнити пам'ять  (вільно " + freeMemoryMb() + " МБ)");
        actions.add(this::freeMemory);

        items.add("Камін: " + FireVideos.title(FireVideos.selected(this)));
        actions.add(this::chooseFireplace);

        items.add("Таймер сну" + (SleepTimer.status(this) != null ? ": " + SleepTimer.status(this) : ""));
        actions.add(() -> SleepTimer.show(this, this::updateSleepInfo));

        items.add("Заставка-камін: " + (saver > 0 ? "через " + saver + " хв бездіяльності" : "вимкнено"));
        actions.add(() -> {
            int next = SAVER_OPTIONS[0];
            for (int i = 0; i < SAVER_OPTIONS.length; i++) {
                if (SAVER_OPTIONS[i] == saver) {
                    next = SAVER_OPTIONS[(i + 1) % SAVER_OPTIONS.length];
                    break;
                }
            }
            prefs.edit().putInt(P_SAVER, next).apply();
            toast(next > 0 ? "Заставка: через " + next + " хв" : "Заставку вимкнено");
        });

        items.add("Автоперемикання на консоль: " + (auto ? "увімкнено" : "вимкнено"));
        actions.add(() -> {
            prefs.edit().putBoolean(P_AUTO, !auto).apply();
            toast(!auto ? "Автоперемикання увімкнено" : "Автоперемикання вимкнено");
        });

        items.add("Після ввімкнення ТВ відкривати Домівку: " + (powerOn ? "так" : "ні"));
        actions.add(() -> {
            prefs.edit().putBoolean(App.P_POWER_ON_HOME, !powerOn).apply();
            toast(!powerOn ? "Після ввімкнення відкриватиметься Домівка"
                    : "Після ввімкнення: як вирішить прошивка");
        });

        items.add("Місто для погоди: " + Weather.CITY_NAMES[Weather.cityIndex(this)]);
        actions.add(this::chooseCity);

        items.add("Повітряна тривога: " + Alerts.MODES[Alerts.mode(this)]);
        actions.add(this::chooseAlertMode);

        items.add("Приховані застосунки (" + hidden.size() + ")");
        actions.add(this::showHiddenApps);

        items.add("Налаштування телевізора");
        actions.add(this::openTvSettings);

        final String newVersion = Updater.availableName(this);
        items.add(newVersion != null ? "Оновити Домівку до версії " + newVersion
                : "Перевірити оновлення (у вас " + Updater.currentName(this) + ")");
        actions.add(() -> {
            if (newVersion != null) {
                Updater.downloadAndInstall(this);
            } else {
                Updater.checkAndPrompt(this, true);
            }
        });

        items.add("Заводське меню (увімкнути ADB)");
        actions.add(this::confirmFactoryMenu);

        items.add("Про Домівку");
        actions.add(() -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Домівка " + Updater.currentName(this))
                .setMessage("Легкий головний екран для телевізора Bravis.\n\n"
                        + "Menu або довге натискання на плитці: меню плитки.\n"
                        + "Source: перейти до джерел.\n"
                        + "Місяць угорі: таймер сну.\n"
                        + "Камін: OK вмикає звук, ▲▼ годинник, Menu таймер сну.\n"
                        + "Повернути старий лаунчер: натисніть «Додому» і виберіть його.\n\n"
                        + FireVideos.CREDITS + "\n"
                        + "Погода: Open-Meteo.com (CC BY 4.0).")
                .setPositiveButton("Добре", null)
                .show());

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Домівка")
                .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    /** Заводське меню CVTE (там вмикається ADB). Спершу попереджаємо про небезпечні пункти. */
    private void confirmFactoryMenu() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Заводське меню")
                .setMessage("Потрібно лише: DEBUG → ADB ENABLE = ON.\n\n"
                        + "НЕ натискайте пункти зі знаком >>> (SHIPPING INIT, BOARD INIT, "
                        + "AGING MODE тощо): вони виконуються одразу і можуть скинути телевізор.\n\n"
                        + "Нічого іншого не змінюйте. Вийти: кнопка «Назад».\n"
                        + "Після цього вимкніть телевізор з розетки на 10 секунд.")
                .setPositiveButton("Відкрити", (d, w) -> openFactoryMenu())
                .setNegativeButton("Скасувати", null)
                .show();
    }

    private void openFactoryMenu() {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .setClassName("cn.cvte.tv.factory.autotest", "com.cvte.tv.at.menu.CVTFactory_DialogMenuActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Throwable t) {
            toast("Не вдалося відкрити заводське меню: " + t.getClass().getSimpleName());
        }
    }

    private void showHiddenApps() {
        final List<AppEntry> list = new ArrayList<>();
        for (AppEntry a : apps) {
            if (hidden().contains(a.pkg)) {
                list.add(a);
            }
        }
        if (list.isEmpty()) {
            toast("Прихованих застосунків немає");
            return;
        }
        String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            names[i] = "Показати «" + list.get(i).label + "»";
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Приховані застосунки")
                .setItems(names, (d, which) -> {
                    Set<String> h = hidden();
                    h.remove(list.get(which).pkg);
                    prefs.edit().putStringSet(P_HIDDEN, h).apply();
                    rebuildApps();
                })
                .show();
    }

    // ---------------------------------------------------------------- пам'ять

    /** Пакети, які не чіпаємо: ТВ-частина прошивки, система, клавіатура. */
    private static boolean isEssential(String pkg) {
        return pkg.equals("android") || pkg.startsWith("com.android.") || pkg.startsWith("com.mediatek.")
                || pkg.equals("com.cvte.tv.api.impl") || pkg.equals("com.cvte.tvservice")
                || pkg.equals("com.cvte.tv.androidsetting") || pkg.equals("com.example.user.myapplication");
    }

    private long freeMemoryMb() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem / (1024 * 1024);
    }

    /** Вивантажити фонові процеси інших застосунків. Ефект тимчасовий: сервіси можуть піднятися знову. */
    private void freeMemory() {
        toast("Звільняю пам'ять...");
        final long before = freeMemoryMb();
        worker.execute(() -> {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            int count = 0;
            try {
                for (ApplicationInfo ai : getPackageManager().getInstalledApplications(0)) {
                    String pkg = ai.packageName;
                    if (pkg.equals(getPackageName()) || isEssential(pkg)) {
                        continue;
                    }
                    am.killBackgroundProcesses(pkg);
                    count++;
                }
                Thread.sleep(1500);
            } catch (Throwable ignored) {
            }
            final long after = freeMemoryMb();
            main.post(() -> toast("Вільно " + after + " МБ (було " + before + " МБ)"));
        });
    }

    private void openTvSettings() {
        Intent i = getPackageManager().getLaunchIntentForPackage("com.cvte.tv.androidsetting");
        if (i == null) {
            i = new Intent(Settings.ACTION_SETTINGS);
        }
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            toast("Не вдалося відкрити налаштування");
        }
    }

    // ---------------------------------------------------------------- закріплені і приховані

    private List<String> pinned() {
        String raw = prefs.getString(P_PINNED, "");
        List<String> list = new ArrayList<>();
        if (raw != null && !raw.isEmpty()) {
            list.addAll(Arrays.asList(raw.split(",")));
        }
        return list;
    }

    private void savePinned(List<String> list) {
        prefs.edit().putString(P_PINNED, TextUtils.join(",", list)).apply();
    }

    private Set<String> hidden() {
        return new HashSet<>(prefs.getStringSet(P_HIDDEN, new HashSet<String>()));
    }

    /** Перший запуск: закріплюємо YouTube/SmartTube, браузер, Jellyfin і Мультимедіа, якщо вони є. */
    private void applyDefaultPins() {
        if (apps.isEmpty()) {
            return;
        }
        // SmartTube/YouTube, встановлений пізніше, один раз сам стає першим у «Головне»
        AppEntry ytLater = findYoutube();
        if (prefs.getBoolean(P_DEFAULTS_DONE, false) && ytLater != null
                && !prefs.getBoolean("auto_pinned_" + ytLater.pkg, false)) {
            List<String> pins = pinned();
            if (!pins.contains(ytLater.pkg)) {
                pins.add(0, ytLater.pkg);
                savePinned(pins);
            }
            prefs.edit().putBoolean("auto_pinned_" + ytLater.pkg, true).apply();
        }
        if (prefs.getBoolean(P_DEFAULTS_DONE, false)) {
            return;
        }
        List<String> pins = new ArrayList<>();
        AppEntry yt = findYoutube();
        if (yt != null) {
            pins.add(yt.pkg);
            prefs.edit().putBoolean("auto_pinned_" + yt.pkg, true).apply();
        }
        String browser = findBrowserPackage();
        if (browser != null && findApp(browser) != null && !pins.contains(browser)) {
            pins.add(browser);
        }
        for (String pkg : new String[]{"org.jellyfin.androidtv", "com.cvte.tv.media"}) {
            if (findApp(pkg) != null && !pins.contains(pkg)) {
                pins.add(pkg);
            }
        }
        savePinned(pins);
        prefs.edit().putBoolean(P_DEFAULTS_DONE, true).apply();
    }

    private AppEntry findApp(String pkg) {
        for (AppEntry a : apps) {
            if (a.pkg.equals(pkg)) {
                return a;
            }
        }
        return null;
    }

    /** SmartTube має перевагу над офіційним YouTube. */
    private AppEntry findYoutube() {
        AppEntry official = null;
        for (AppEntry a : apps) {
            String s = (a.pkg + " " + a.label).toLowerCase(Locale.ROOT);
            if (s.contains("smarttube")) {
                return a;
            }
            if (official == null && s.contains("youtube")) {
                official = a;
            }
        }
        return official;
    }

    private String findBrowserPackage() {
        Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"));
        try {
            ResolveInfo ri = getPackageManager().resolveActivity(web, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null && !"android".equals(ri.activityInfo.packageName)) {
                return ri.activityInfo.packageName;
            }
            for (ResolveInfo r : getPackageManager().queryIntentActivities(web, 0)) {
                if (r.activityInfo != null) {
                    return r.activityInfo.packageName;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ================================================================ «цеглинки» інтерфейсу

    /** Картка з градієнтом, обведенням у фокусі та плавним збільшенням. */
    /** Велика плитка: іконка зверху, назва і підпис знизу. */
    /** Картка з банером застосунку (для Android TV застосунків). */
    /** Невелика плашка джерела сигналу. */
    // ================================================================ вітрина

    private void setHero(String title, CharSequence sub, String hint) {
        setHeroFire(false);
        heroPanel.setVisibility(View.GONE);
        heroTitle.setText(title);
        heroSub.setText(sub == null ? "" : sub);
        heroSub.setVisibility(sub == null ? View.GONE : View.VISIBLE);
        heroHint.setText(hint == null ? "" : hint);
        heroHint.setVisibility(hint == null ? View.GONE : View.VISIBLE);
        heroForecast.setVisibility(View.GONE);
        heroText.animate().cancel();
        heroText.setAlpha(0f);
        heroText.setTranslationX(-dp(14));
        heroText.animate().alpha(1f).translationX(0).setDuration(220).start();
    }

    /** Картинка праворуч у вітрині: банер застосунку (картка) або велика іконка. */
    private void setHeroArt(Drawable d, int wDp, int hDp, boolean card) {
        heroArt.animate().cancel();
        if (d == null) {
            heroArt.setVisibility(View.GONE);
            return;
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) heroArt.getLayoutParams();
        lp.width = dp(wDp);
        lp.height = dp(hDp);
        lp.rightMargin = dp(card ? 24 : 80);
        heroArt.setLayoutParams(lp);
        heroArt.setImageDrawable(d);
        if (card) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            bg.setColor(0xFF222833);
            heroArt.setBackground(bg);
            heroArt.setClipToOutline(true);
            heroArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
            heroArt.setElevation(dp(14));
        } else {
            heroArt.setBackground(null);
            heroArt.setClipToOutline(false);
            heroArt.setScaleType(ImageView.ScaleType.FIT_CENTER);
            heroArt.setElevation(0);
        }
        heroArt.setVisibility(View.VISIBLE);
        heroArt.setAlpha(0f);
        heroArt.setScaleX(0.94f);
        heroArt.setScaleY(0.94f);
        heroArt.animate().alpha(card ? 1f : 0.92f).scaleX(1f).scaleY(1f).setDuration(280).start();
    }

    /** М'яке кольорове сяйво за картинкою вітрини. */
    private void setHeroGlow(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[]{color, color & 0x00FFFFFF});
        g.setGradientCenter(0.8f, 0.5f);
        g.setGradientRadius(dp(430));
        heroGlow.setBackground(g);
    }

    private void setHeroFire(boolean on) {
        if (heroFire == null) {
            return;
        }
        if (on) {
            heroFire.setVisibility(View.VISIBLE);
            heroFire.start();
        } else {
            heroFire.stop();
            heroFire.setVisibility(View.GONE);
        }
    }

    private void heroConsole() {
        setHero("Консоль", consoleSubtitle(), "OK: грати   ·   Menu: вибрати HDMI і автоперемикання");
        setHeroArt(getDrawable(R.drawable.ic_gamepad), 190, 190, false);
        setHeroGlow(0x66FF2E63);
    }

    private void heroFireplace() {
        int saver = prefs.getInt(P_SAVER, 10);
        String id = FireVideos.selected(this);
        String state = !FireVideos.isVideo(id) ? ""
                : FireVideos.isReady(this, id) ? ""
                : FireVideos.isDownloading(id) ? "  ·  завантажується" : "  ·  завантажиться при першому запуску";
        setHero("Камін", FireVideos.title(id) + state,
                "OK: запалити   ·   Menu: вибрати камін"
                        + (saver > 0 ? "   ·   заставка через " + saver + " хв" : ""));
        setHeroGlow(0x66FF7A2E);
        Bitmap poster = FireVideos.isVideo(id) ? FireVideos.posterBitmap(this, id) : null;
        if (poster != null) {
            BitmapDrawable d = new BitmapDrawable(getResources(), poster);
            setHeroArt(d, 448, 252, true);
            // Повільне «дихання» обкладинки, щоб вона не здавалася застиглою
            heroArt.animate().scaleX(1.04f).scaleY(1.04f).setStartDelay(300).setDuration(6000).start();
        } else {
            setHeroArt(null, 0, 0, false);
            setHeroFire(true);
            if (FireVideos.isVideo(id)) {
                FireVideos.fetchPoster(this, id, () -> {
                    View f = getCurrentFocus();
                    if (f != null && TAG_FIRE.equals(f.getTag())) {
                        heroFireplace();
                    }
                });
            }
        }
    }

    /** Вибір каміна: справжні відео (завантажуються один раз) або намальований. */
    private void chooseFireplace() {
        String current = FireVideos.selected(this);
        String[] items = new String[FireVideos.IDS.length];
        for (int i = 0; i < items.length; i++) {
            String id = FireVideos.IDS[i];
            String status = !FireVideos.isVideo(id) ? ""
                    : FireVideos.isReady(this, id) ? "  ✓ завантажено"
                    : "  (" + FireVideos.SIZES[i] + ")";
            items[i] = FireVideos.TITLES[i] + status;
        }
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Камін")
                .setSingleChoiceItems(items, FireVideos.index(current), (d, which) -> {
                    d.dismiss();
                    String id = FireVideos.IDS[which];
                    FireVideos.select(this, id);
                    if (FireVideos.isVideo(id) && !FireVideos.isReady(this, id)) {
                        downloadFireplace(id);
                    } else {
                        toast("Камін: " + FireVideos.title(id));
                    }
                })
                .show();
    }

    private void downloadFireplace(String id) {
        String busy = FireVideos.downloading();
        if (busy != null) {
            toast(busy.equals(id) ? "Цей камін уже завантажується"
                    : "Зараз завантажується «" + FireVideos.title(busy) + "», спробуйте трохи згодом");
            return;
        }
        final AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(FireVideos.title(id))
                .setMessage("Завантажую... Можна закрити це вікно: завантаження продовжиться.")
                .setPositiveButton("Сховати", null)
                .show();
        FireVideos.download(this, id, new FireVideos.Listener() {
            @Override
            public void onProgress(int percent) {
                if (dlg.isShowing()) {
                    dlg.setMessage("Завантажую... " + percent + "%\nМожна закрити це вікно: завантаження продовжиться.");
                }
            }

            @Override
            public void onDone(boolean ok) {
                if (dlg.isShowing()) {
                    dlg.dismiss();
                }
                toast(ok ? "Камін завантажено: «" + FireVideos.title(id) + "»"
                        : "Не вдалося завантажити камін: перевірте інтернет");
                View f = getCurrentFocus();
                if (f != null && TAG_FIRE.equals(f.getTag())) {
                    heroFireplace();
                }
            }
        });
    }

    private void heroWeather() {
        setHeroGlow(0x553D8BFF);
        if (weather == null) {
            setHero("Погода", weatherLoading ? "Завантажую прогноз..." : "Немає даних: потрібен інтернет",
                    "OK: вибрати місто");
            setHeroArt(getDrawable(R.drawable.ic_w_partly), 160, 160, false);
            return;
        }
        SpannableStringBuilder sub = new SpannableStringBuilder(
                Weather.describe(weather.code) + ", відчувається як " + Weather.temp(weather.feels)
                        + ", вітер " + weather.wind + " м/с");
        if (Alerts.known && Alerts.mode(this) != Alerts.MODE_OFF) {
            sub.append("\n");
            int from = sub.length();
            if (Alerts.active) {
                sub.append("Повітряна тривога ").append(Alerts.where(Alerts.region))
                        .append(" · ").append(Alerts.duration(Alerts.since));
                sub.setSpan(new ForegroundColorSpan(ALERT_RED), from, sub.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                sub.append("Тривоги немає ").append(Alerts.where(Alerts.region));
                sub.setSpan(new ForegroundColorSpan(OK_GREEN), from, sub.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        setHero(weather.city + "   " + Weather.temp(weather.temp), sub,
                "OK: вибрати місто   ·   Menu: сповіщення про тривогу");
        heroForecast.removeAllViews();
        int n = Math.min(5, weather.days.length);
        for (int k = 0; k < n; k++) {
            Weather.Day d = weather.days[k];
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding(0, 0, dp(30), 0);
            col.addView(text(k == 0 ? "Сьогодні" : Weather.dayName(d.dayOfWeek), 14, TEXT_DIM, true));
            ImageView ic = new ImageView(this);
            ic.setImageResource(Weather.icon(d.code, true));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(34), dp(34));
            ip.topMargin = dp(4);
            ip.bottomMargin = dp(4);
            col.addView(ic, ip);
            col.addView(text(Weather.temp(d.max) + " / " + Weather.temp(d.min), 14, TEXT, false));
            heroForecast.addView(col);
        }
        heroForecast.setVisibility(n > 0 ? View.VISIBLE : View.GONE);

        Weather.Hour[] hours = Weather.upcoming(weather, 6, 2);
        if (hours.length >= 3) {
            setHeroArt(null, 0, 0, false);
            heroPanel.removeAllViews();
            heroPanel.addView(text("Найближчі години", 14, TEXT_DIM, true));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(10), 0, 0);
            for (Weather.Hour h : hours) {
                LinearLayout col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setGravity(Gravity.CENTER_HORIZONTAL);
                col.addView(text(String.format(Locale.ROOT, "%02d:00", h.hour), 14, TEXT_DIM, false));
                ImageView ic = new ImageView(this);
                ic.setImageResource(Weather.icon(h.code, h.isDay));
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(36), dp(36));
                ip.topMargin = dp(6);
                ip.bottomMargin = dp(6);
                col.addView(ic, ip);
                col.addView(text(Weather.temp(h.temp), 18, TEXT, true));
                row.addView(col, new LinearLayout.LayoutParams(dp(66), -2));
            }
            heroPanel.addView(row);
            heroPanel.setVisibility(View.VISIBLE);
            heroPanel.setAlpha(0f);
            heroPanel.setTranslationX(dp(16));
            heroPanel.animate().alpha(1f).translationX(0).setDuration(260).start();
        } else {
            setHeroArt(getDrawable(Weather.icon(weather.code, weather.isDay)), 170, 170, false);
        }
    }

    // ================================================================ тривога

    private void onAlertChanged() {
        updateAlertPill();
        View f = getCurrentFocus();
        if (f != null && TAG_WEATHER.equals(f.getTag())) {
            heroWeather();
        }
    }

    private void updateAlertPill() {
        if (alertPill == null) {
            return;
        }
        boolean show = Alerts.active && Alerts.mode(this) != Alerts.MODE_OFF;
        if (show) {
            alertPill.setText("Тривога  ·  " + Alerts.duration(Alerts.since));
        }
        alertPill.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void chooseAlertMode() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Повітряна тривога (" + (Weather.cityIndex(this) == 0 ? "Київ" : "Київська область") + ")")
                .setSingleChoiceItems(Alerts.MODES, Alerts.mode(this), (d, which) -> {
                    d.dismiss();
                    Alerts.setMode(this, which);
                    updateAlertPill();
                    toast("Тривога: " + Alerts.MODES[which]);
                })
                .show();
    }

    private void heroApp(AppEntry app) {
        boolean pinned = pinned().contains(app.pkg);
        int launches = prefs.getInt(P_LAUNCH_PREFIX + app.pkg, 0);
        String sub = (pinned ? "У головному ряду" : "Застосунок")
                + (launches > 0 ? "  ·  відкривали " + launches + " " + times(launches) : "");
        setHero(app.label, sub, "OK: відкрити   ·   Menu: закріпити, посунути, сховати, видалити");
        if (app.banner != null) {
            setHeroArt(copy(app.banner), 384, 216, true);
        } else {
            setHeroArt(copy(app.icon), 150, 150, false);
        }
        setHeroGlow((app.color & 0x00FFFFFF) | 0x77000000);
    }

    private static String times(int n) {
        int m100 = n % 100;
        int m10 = n % 10;
        if (m100 >= 11 && m100 <= 14) {
            return "разів";
        }
        if (m10 == 1) {
            return "раз";
        }
        return m10 >= 2 && m10 <= 4 ? "рази" : "разів";
    }

    // ================================================================ погода

    private long lastWeatherAttempt;

    private void refreshWeather(boolean force) {
        long now = System.currentTimeMillis();
        if (weatherLoading) {
            return;
        }
        if (!force && (!Weather.isStale(this) || now - lastWeatherAttempt < 5 * 60_000L)) {
            return;
        }
        lastWeatherAttempt = now;
        weatherLoading = true;
        final Context app = getApplicationContext();
        new Thread(() -> {
            final Weather.Data d = Weather.fetch(app);
            main.post(() -> {
                weatherLoading = false;
                if (d != null) {
                    weather = d;
                } else if (force) {
                    toast("Не вдалося оновити погоду: перевірте інтернет");
                }
                onWeatherChanged();
            });
        }, "weather").start();
    }

    private void updateWeatherMini() {
        if (weather == null) {
            weatherMiniIcon.setVisibility(View.GONE);
            weatherMiniText.setText("");
            return;
        }
        weatherMiniIcon.setImageResource(Weather.icon(weather.code, weather.isDay));
        weatherMiniIcon.setVisibility(View.VISIBLE);
        weatherMiniText.setText(Weather.temp(weather.temp) + "  " + weather.city);
    }

    private void onWeatherChanged() {
        updateWeatherMini();
        rebuildMainRow();
        View f = getCurrentFocus();
        if (f != null && heroActions.containsKey(f)) {
            heroActions.get(f).run();
        }
    }

    private void chooseCity() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Місто для погоди")
                .setSingleChoiceItems(Weather.CITY_NAMES, Weather.cityIndex(this), (d, which) -> {
                    d.dismiss();
                    Weather.setCity(this, which);
                    weather = null;
                    onWeatherChanged();
                    refreshWeather(true);
                    Alerts.poll();
                })
                .show();
    }

    // ================================================================ фон

    private void updateWallpaper() {
        if (rootView == null) {
            return;
        }
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int bucket = h >= 6 && h < 11 ? 0 : h >= 11 && h < 17 ? 1 : h >= 17 && h < 22 ? 2 : 3;
        if (bucket == wallpaperBucket) {
            return;
        }
        wallpaperBucket = bucket;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        BitmapDrawable wall = new BitmapDrawable(getResources(), Wallpaper.make(dm.widthPixels, dm.heightPixels));
        wall.setFilterBitmap(true);
        rootView.setBackground(wall);
    }

    // ================================================================ «цеглинки» інтерфейсу

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        r.setPadding(0, dp(14), dp(40), dp(8));
        return r;
    }

    private HorizontalScrollView horizontal(LinearLayout row) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipChildren(false);
        h.setClipToPadding(false);
        h.addView(row);
        return h;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 18, TEXT_DIM, true);
        t.setPadding(dp(2), dp(30), 0, dp(4));
        return t;
    }

    private LinearLayout.LayoutParams wrapParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = dp(TILE_GAP);
        return lp;
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(220), dp(66));
        lp.rightMargin = dp(20);
        return lp;
    }

    private FrameLayout baseCard(int[] colors) {
        return baseCard(colors, 16);
    }

    /** Картка: заокруглена, з тінню; у фокусі — відблиск і тонка світла рамка. */
    private FrameLayout baseCard(int[] colors, int radiusDp) {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        bg.setCornerRadius(dp(radiusDp));
        f.setBackground(bg);
        f.setClipToOutline(true);
        f.setElevation(dp(3));
        f.setForeground(shine(radiusDp));
        f.setFocusable(true);
        f.setClickable(true);
        return f;
    }

    private Drawable shine(int radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x50FFFFFF, 0x08FFFFFF, 0x00FFFFFF});
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(2), 0x90FFFFFF);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, g);
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return states;
    }

    /** Плитка з великою іконкою (і, за бажання, підписом поруч, наприклад температурою). */
    private FrameLayout iconCard(int[] colors, int iconRes, String badge) {
        FrameLayout f = baseCard(colors);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        r.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));
        if (badge != null) {
            TextView b = text(badge, 30, TEXT, false);
            b.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            b.setPadding(dp(10), 0, 0, 0);
            r.addView(b);
        }
        f.addView(r, new FrameLayout.LayoutParams(-1, -1));
        return f;
    }

    /**
     * Плитка у стилі Apple TV: картка піднімається, збільшується і відкидає тінь,
     * а підпис під нею видно лише у фокусі. Вітрина при цьому показує цю плитку.
     */
    private LinearLayout tileWrap(FrameLayout card, String label, Runnable heroAction) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        wrap.setClipChildren(false);
        wrap.setClipToPadding(false);
        wrap.addView(card, new LinearLayout.LayoutParams(dp(TILE_W), dp(TILE_H)));
        final TextView t = text(label, 15, TEXT, true);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        t.setAlpha(0f);
        t.setPadding(0, dp(12), 0, dp(4));
        wrap.addView(t, new LinearLayout.LayoutParams(dp(TILE_W + 20), -2));
        heroActions.put(card, heroAction);
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.12f : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(20) : 0).setDuration(170).start();
            t.animate().alpha(has ? 1f : 0f).translationY(has ? dp(7) : 0).setDuration(170).start();
            if (has && heroAction != null) {
                heroAction.run();
            }
        });
        return wrap;
    }

    private void liftOnFocus(View v, float scale, Runnable heroAction) {
        heroActions.put(v, heroAction);
        v.setOnFocusChangeListener((x, has) -> {
            float s = has ? scale : 1f;
            x.animate().scaleX(s).scaleY(s).translationZ(has ? dp(14) : 0).setDuration(160).start();
            if (has && heroAction != null) {
                heroAction.run();
            }
        });
    }

    /** Плашка джерела сигналу (скляна, заокруглена). */
    private View chip(int iconRes, String title, String subtitle, boolean connected, Runnable heroAction) {
        FrameLayout f = baseCard(new int[]{GLASS, GLASS}, 20);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(18), 0, dp(12), 0);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        r.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        TextView t = text(title, 17, TEXT, true);
        t.setSingleLine(true);
        col.addView(t);
        TextView s = text((connected ? "● " : "") + subtitle, 13, connected ? OK_GREEN : TEXT_DIM, false);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(s);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        f.addView(r, new FrameLayout.LayoutParams(-1, -1));
        liftOnFocus(f, 1.08f, heroAction);
        return f;
    }

    /** Кругла скляна кнопка у верхній смужці. */
    private View iconButton(int iconRes, String description, Runnable heroAction) {
        FrameLayout f = baseCard(new int[]{GLASS, GLASS}, 23);
        f.setContentDescription(description);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        f.addView(icon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        liftOnFocus(f, 1.15f, heroAction);
        return f;
    }

    /** Прибрати дії вітрини для плиток, яких уже немає на екрані. */
    private void clearHeroActions(ViewGroup ignored) {
        Iterator<View> it = heroActions.keySet().iterator();
        while (it.hasNext()) {
            if (!inTree(it.next())) {
                it.remove();
            }
        }
    }

    private boolean inTree(View v) {
        ViewParent p = v.getParent();
        while (p != null) {
            if (p == rootView) {
                return true;
            }
            p = p.getParent();
        }
        return false;
    }

    /** Окрема копія Drawable: один і той самий не можна показувати у двох місцях одночасно. */
    private Drawable copy(Drawable d) {
        if (d == null) {
            return null;
        }
        Drawable.ConstantState cs = d.getConstantState();
        return cs != null ? cs.newDrawable(getResources()).mutate() : d;
    }

    private static int shade(int color, float f) {
        return Color.rgb((int) (Color.red(color) * f), (int) (Color.green(color) * f), (int) (Color.blue(color) * f));
    }

    /** Основний колір іконки: середній відтінок насичених пікселів. */
    private static int iconColor(Drawable d) {
        try {
            Bitmap b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            Drawable.ConstantState cs = d.getConstantState();
            Drawable dd = cs != null ? cs.newDrawable().mutate() : d;
            dd.setBounds(0, 0, 16, 16);
            dd.draw(c);
            float[] hsv = new float[3];
            double hx = 0;
            double hy = 0;
            double satSum = 0;
            double wSum = 0;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int px = b.getPixel(x, y);
                    if (Color.alpha(px) < 128) {
                        continue;
                    }
                    Color.colorToHSV(px, hsv);
                    double w = hsv[1] * hsv[2] + 0.02;
                    double rad = Math.toRadians(hsv[0]);
                    hx += Math.cos(rad) * w;
                    hy += Math.sin(rad) * w;
                    satSum += hsv[1] * w;
                    wSum += w;
                }
            }
            b.recycle();
            if (wSum <= 0) {
                return 0xFF2A3350;
            }
            float hue = (float) Math.toDegrees(Math.atan2(hy, hx));
            if (hue < 0) {
                hue += 360;
            }
            float sat = (float) Math.max(0.2, Math.min(0.85, satSum / wSum * 1.1));
            return Color.HSVToColor(new float[]{hue, sat, 0.78f});
        } catch (Throwable t) {
            return 0xFF2A3350;
        }
    }

    private TextView text(String value, float sp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setText(value);
        style(t, sp, color, medium);
        return t;
    }

    private void style(TextView t, float sp, int color, boolean medium) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        t.setIncludeFontPadding(true);
    }

    // ---------------------------------------------------------------- фокус після перебудови

    private String focusedKey() {
        View f = getCurrentFocus();
        if (f == null) {
            return null;
        }
        Object tag = f.getTag();
        if (tag instanceof AppEntry) {
            return "app:" + ((AppEntry) tag).pkg;
        }
        return tag instanceof String ? (String) tag : null;
    }

    private void restoreFocus(ViewGroup container, String key) {
        if (key == null) {
            return;
        }
        View v = findByKey(container, key);
        if (v != null) {
            v.requestFocus();
            return;
        }
        // Плитку з фокусом прибрали (наприклад, відкріпили): фокус на «Консоль»
        if (getCurrentFocus() == null) {
            focusConsole();
        }
    }

    private static String keyOf(View v) {
        Object tag = v.getTag();
        if (tag instanceof AppEntry) {
            return "app:" + ((AppEntry) tag).pkg;
        }
        return tag instanceof String ? (String) tag : null;
    }

    private static View findByKey(ViewGroup root, String key) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (key.equals(keyOf(c))) {
                return c;
            }
            if (c instanceof ViewGroup) {
                View found = findByKey((ViewGroup) c, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
