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
import android.graphics.Color;
import android.graphics.Typeface;
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
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
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
    private static final int SIDE = 56;
    private static final int GAP = 18;
    private static final int TILE_H = 150;
    private static final int CONSOLE_W = 330;
    private static final int PIN_W = 200;
    private static final int APP_W = 172;
    private static final int APP_H = 97;
    private static final int APP_COLUMNS = 6;

    // ---- кольори
    private static final int TEXT = Color.WHITE;
    private static final int TEXT_DIM = Color.rgb(169, 180, 208);
    private static final int CARD = Color.argb(225, 30, 38, 66);
    private static final int ACCENT = Color.rgb(124, 92, 255);
    private static final int OK_GREEN = Color.rgb(110, 240, 160);
    private static final int[][] PALETTE = {
            {0xFF3A7BFF, 0xFF6A3DFF}, {0xFF00B3A4, 0xFF006F8E}, {0xFFFF8A3D, 0xFFE0365E},
            {0xFF5B6CFF, 0xFF2F3A8F}, {0xFF2BB673, 0xFF16705B}, {0xFFB04BFF, 0xFF6A1FB0},
    };

    private static final String TAG_CONSOLE = "console";

    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TvInputManager tvInputs;
    private boolean resumed;

    private ScrollView scroll;
    private TextView greeting;
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

        AppEntry(String pkg, ComponentName component, String label, Drawable banner, Drawable icon, boolean leanback) {
            this.pkg = pkg;
            this.component = component;
            this.label = label;
            this.banner = banner;
            this.icon = icon;
            this.leanback = leanback;
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

        rebuildMainRow();
        rebuildSources();
        reloadApps();
        loadSources();
        main.post(this::focusConsole);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        App.homeResumed = true;
        updateGreeting();
        refreshSourceStates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        App.homeResumed = false;
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
        FrameLayout root = new FrameLayout(this);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(dp(SIDE), dp(36), dp(SIDE), dp(48));
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // ---- шапка: привітання, годинник, налаштування
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        greeting = text("", 30, TEXT, true);
        titles.addView(greeting);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE, d MMMM");
        date.setFormat24Hour("EEEE, d MMMM");
        style(date, 17, TEXT_DIM, false);
        titles.addView(date);
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("HH:mm");
        clock.setFormat24Hour("HH:mm");
        style(clock, 46, TEXT, false);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        header.addView(clock);

        View settings = iconButton(R.drawable.ic_tune, "Налаштування");
        settings.setOnClickListener(v -> showLauncherSettings());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(56), dp(56));
        sp.leftMargin = dp(28);
        header.addView(settings, sp);
        content.addView(header);

        // ---- Головне
        content.addView(sectionTitle("Головне"));
        mainRow = row();
        content.addView(horizontal(mainRow));

        // ---- Джерела
        content.addView(sectionTitle("Джерела"));
        sourcesRow = row();
        content.addView(horizontal(sourcesRow));

        // ---- Усі застосунки
        content.addView(sectionTitle("Застосунки"));
        appsGrid = new GridLayout(this);
        appsGrid.setColumnCount(APP_COLUMNS);
        appsGrid.setClipChildren(false);
        appsGrid.setClipToPadding(false);
        appsGrid.setPadding(0, dp(6), 0, 0);
        content.addView(appsGrid, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
    }

    private void updateGreeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g;
        if (h >= 5 && h < 12) {
            g = "Добрий ранок";
        } else if (h >= 12 && h < 18) {
            g = "Добрий день";
        } else if (h >= 18 && h < 23) {
            g = "Добрий вечір";
        } else {
            g = "Доброї ночі";
        }
        greeting.setText(g);
    }

    private void scrollToTop() {
        scroll.smoothScrollTo(0, 0);
        focusConsole();
    }

    private void focusConsole() {
        View console = mainRow.findViewWithTag(TAG_CONSOLE);
        if (console != null) {
            console.requestFocus();
        }
    }

    // ---------------------------------------------------------------- Головне

    private void rebuildMainRow() {
        String focusKey = focusedKey();
        mainRow.removeAllViews();

        mainRow.addView(consoleTile(), tileParams(CONSOLE_W, TILE_H));

        if (findYoutube() == null) {
            View yt = tile(PIN_W, new int[]{0xFFE53935, 0xFF8E1B2C}, R.drawable.ic_play, "YouTube",
                    "встановіть SmartTube");
            yt.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("YouTube без реклами")
                    .setMessage("Скачайте SmartTube з офіційного сайту розробника, скиньте APK на флешку "
                            + "і встановіть. Плитка з'явиться тут сама.")
                    .setPositiveButton("Зрозуміло", null)
                    .show());
            mainRow.addView(yt, tileParams(PIN_W, TILE_H));
        }

        for (String pkg : pinned()) {
            AppEntry app = findApp(pkg);
            if (app != null) {
                mainRow.addView(pinnedTile(app), tileParams(PIN_W, TILE_H));
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
        View t = tile(CONSOLE_W, new int[]{0xFFFF2E63, 0xFF6A1FB0}, R.drawable.ic_gamepad, "Консоль",
                consoleSubtitle());
        t.setTag(TAG_CONSOLE);
        t.setOnClickListener(v -> openConsole());
        t.setOnLongClickListener(v -> {
            showConsoleMenu();
            return true;
        });
        return t;
    }

    private View pinnedTile(AppEntry app) {
        View t;
        if (app.banner != null) {
            t = bannerCard(app, PIN_W, TILE_H);
        } else {
            int[] colors = PALETTE[Math.abs(app.pkg.hashCode()) % PALETTE.length];
            t = tile(PIN_W, colors, 0, app.label, null);
            ImageView icon = t.findViewWithTag("icon");
            if (icon != null) {
                icon.setImageDrawable(app.icon);
                icon.setColorFilter(null);
            }
        }
        t.setTag(app);
        t.setOnClickListener(v -> launch(app));
        t.setOnLongClickListener(v -> {
            showAppMenu(app);
            return true;
        });
        return t;
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
        for (CvteTv.Source s : sources) {
            boolean tv = isTvSource(s);
            if (!s.isHdmi() && !tv) {
                continue; // AV, VGA, YPbPr на цьому телевізорі не потрібні
            }
            String title = tv ? "Телебачення" : s.name;
            String sub = tv ? "антена" : stateText(s.id);
            if (s.id == consoleSourceId()) {
                sub = "консоль  ·  " + sub;
            }
            View chip = chip(tv ? R.drawable.ic_tv : R.drawable.ic_hdmi, title, sub,
                    !tv && isConnected(s.id));
            chip.setTag("src:" + s.id);
            final int id = s.id;
            chip.setOnClickListener(v -> openSource(id));
            chip.setOnLongClickListener(v -> {
                showConsoleMenu();
                return true;
            });
            sourcesRow.addView(chip, tileParams(210, 78));
        }
        if (sourcesRow.getChildCount() == 0) {
            View chip = chip(R.drawable.ic_tv, "ТВ-екран", "поточне джерело", false);
            chip.setOnClickListener(v -> openSource(-1));
            sourcesRow.addView(chip, tileParams(210, 78));
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
        View console = mainRow.findViewWithTag(TAG_CONSOLE);
        if (console != null) {
            View sub = console.findViewWithTag("sub");
            if (sub instanceof TextView) {
                ((TextView) sub).setText(consoleSubtitle());
            }
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
            out.put(ai.packageName, new AppEntry(ai.packageName,
                    new ComponentName(ai.packageName, ai.name), label, banner, icon, leanback));
        }
    }

    private void rebuildApps() {
        String focusKey = focusedKey();
        appsGrid.removeAllViews();
        Set<String> hidden = hidden();
        for (AppEntry app : apps) {
            if (hidden.contains(app.pkg)) {
                continue;
            }
            View card = appCard(app);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(APP_W);
            lp.height = dp(APP_H);
            lp.rightMargin = dp(GAP);
            lp.bottomMargin = dp(GAP);
            appsGrid.addView(card, lp);
        }
        restoreFocus(appsGrid, focusKey);
    }

    private View appCard(AppEntry app) {
        View card;
        if (app.banner != null) {
            card = bannerCard(app, APP_W, APP_H);
        } else {
            FrameLayout f = baseCard(new int[]{CARD, CARD});
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            col.setPadding(dp(8), dp(8), dp(8), dp(6));
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.icon);
            col.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
            TextView label = text(app.label, 13, TEXT, false);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setPadding(0, dp(6), 0, 0);
            col.addView(label, new LinearLayout.LayoutParams(-1, -2));
            f.addView(col, new FrameLayout.LayoutParams(-1, -1));
            card = f;
        }
        card.setTag(app);
        card.setOnClickListener(v -> launch(app));
        card.setOnLongClickListener(v -> {
            showAppMenu(app);
            return true;
        });
        return card;
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
        String[] items = {
                "Звільнити пам'ять  (вільно " + freeMemoryMb() + " МБ)",
                "Налаштування телевізора",
                "Автоперемикання на консоль: " + (auto ? "увімкнено" : "вимкнено"),
                "Приховані застосунки (" + hidden.size() + ")",
                "Після ввімкнення ТВ відкривати Домівку: " + (powerOn ? "так" : "ні"),
                "Про Домівку",
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Домівка")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            freeMemory();
                            break;
                        case 1:
                            openTvSettings();
                            break;
                        case 2:
                            prefs.edit().putBoolean(P_AUTO, !auto).apply();
                            toast(!auto ? "Автоперемикання увімкнено" : "Автоперемикання вимкнено");
                            break;
                        case 3:
                            showHiddenApps();
                            break;
                        case 4:
                            prefs.edit().putBoolean(App.P_POWER_ON_HOME, !powerOn).apply();
                            toast(!powerOn ? "Після ввімкнення відкриватиметься Домівка"
                                    : "Після ввімкнення: як вирішить прошивка");
                            break;
                        default:
                            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                    .setTitle("Домівка 1.2")
                                    .setMessage("Легкий головний екран для телевізора Bravis.\n\n"
                                            + "Menu або довге натискання на плитці: меню плитки.\n"
                                            + "Source: перейти до джерел.\n"
                                            + "Повернути старий лаунчер: натисніть «Додому» і виберіть його.")
                                    .setPositiveButton("Добре", null)
                                    .show();
                    }
                })
                .show();
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

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        r.setPadding(0, dp(8), dp(24), dp(8));
        return r;
    }

    private HorizontalScrollView horizontal(LinearLayout row) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipChildren(false);
        h.setClipToPadding(false);
        h.setFillViewport(false);
        h.addView(row);
        return h;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 19, TEXT_DIM, true);
        t.setPadding(dp(2), dp(26), 0, dp(4));
        return t;
    }

    private LinearLayout.LayoutParams tileParams(int wDp, int hDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(wDp), dp(hDp));
        lp.rightMargin = dp(GAP);
        return lp;
    }

    /** Картка з градієнтом, обведенням у фокусі та плавним збільшенням. */
    private FrameLayout baseCard(int[] colors) {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        bg.setCornerRadius(dp(16));
        f.setBackground(bg);
        f.setClipToOutline(true);
        f.setForeground(focusRing(16));
        f.setFocusable(true);
        f.setClickable(true);
        f.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.07f : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? dp(10) : 0).setDuration(140).start();
        });
        return f;
    }

    private Drawable focusRing(int radiusDp) {
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(radiusDp));
        ring.setColor(Color.TRANSPARENT);
        ring.setStroke(dp(3), Color.WHITE);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, ring);
        states.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return states;
    }

    /** Велика плитка: іконка зверху, назва і підпис знизу. */
    private FrameLayout tile(int wDp, int[] colors, int iconRes, String title, String subtitle) {
        FrameLayout f = baseCard(colors);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(18), dp(20), dp(16));

        ImageView icon = new ImageView(this);
        icon.setTag("icon");
        if (iconRes != 0) {
            icon.setImageResource(iconRes);
        }
        col.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        TextView t = text(title, 22, TEXT, true);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(t);
        if (subtitle != null) {
            TextView s = text(subtitle, 14, Color.argb(220, 255, 255, 255), false);
            s.setTag("sub");
            s.setSingleLine(true);
            s.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(s);
        }
        f.addView(col, new FrameLayout.LayoutParams(-1, -1));
        return f;
    }

    /** Картка з банером застосунку (для Android TV застосунків). */
    private FrameLayout bannerCard(AppEntry app, int wDp, int hDp) {
        FrameLayout f = baseCard(new int[]{CARD, CARD});
        ImageView banner = new ImageView(this);
        banner.setImageDrawable(app.banner);
        banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
        f.addView(banner, new FrameLayout.LayoutParams(-1, -1));
        return f;
    }

    /** Невелика плашка джерела сигналу. */
    private View chip(int iconRes, String title, String subtitle, boolean connected) {
        FrameLayout f = baseCard(new int[]{CARD, CARD});
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), 0, dp(12), 0);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        r.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
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
        return f;
    }

    private View iconButton(int iconRes, String description) {
        FrameLayout f = baseCard(new int[]{CARD, CARD});
        f.setContentDescription(description);
        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER);
        f.addView(icon, lp);
        return f;
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
        for (int i = 0; i < container.getChildCount(); i++) {
            View c = container.getChildAt(i);
            Object tag = c.getTag();
            String k = tag instanceof AppEntry ? "app:" + ((AppEntry) tag).pkg
                    : tag instanceof String ? (String) tag : null;
            if (key.equals(k)) {
                c.requestFocus();
                return;
            }
        }
        // Плитку з фокусом прибрали (наприклад, відкріпили): фокус на «Консоль»
        if (getCurrentFocus() == null) {
            focusConsole();
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
