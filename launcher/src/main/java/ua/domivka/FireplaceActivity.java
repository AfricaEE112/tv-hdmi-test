package ua.domivka;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextClock;
import android.widget.TextView;

/**
 * Режим каміну. Звичайний режим: OK вмикає/вимикає звук, ▲/▼ показують/ховають годинник,
 * Menu відкриває таймер сну, «Назад» повертає додому.
 * Режим заставки: без звуку, будь-яка кнопка закриває.
 */
public class FireplaceActivity extends Activity {

    static final String EXTRA_SCREENSAVER = "screensaver";
    private static final String P_SOUND = "fire_sound";
    private static final String P_CLOCK = "fire_clock";

    private SharedPreferences prefs;
    private FireView fire;
    private final FireSound sound = new FireSound();
    private TextClock clock;
    private TextView hint;
    private boolean screensaver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        screensaver = getIntent().getBooleanExtra(EXTRA_SCREENSAVER, false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        fire = new FireView(this);
        root.addView(fire, new FrameLayout.LayoutParams(-1, -1));

        clock = new TextClock(this);
        clock.setFormat12Hour("HH:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        clock.setTextColor(Color.argb(150, 255, 214, 170));
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        cp.setMargins(0, dp(20), dp(40), 0);
        root.addView(clock, cp);
        clock.setVisibility(prefs.getBoolean(P_CLOCK, true) ? View.VISIBLE : View.GONE);

        hint = new TextView(this);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hint.setTextColor(Color.argb(170, 255, 225, 200));
        hint.setText(screensaver ? "Натисніть будь-яку кнопку"
                : "OK: звук   ·   ▲▼: годинник   ·   Menu: таймер сну   ·   Назад: додому");
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hp.bottomMargin = dp(10);
        root.addView(hint, hp);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        fire.start();
        if (!screensaver && prefs.getBoolean(P_SOUND, true)) {
            sound.start();
        }
        hint.setAlpha(1f);
        hint.animate().alpha(0f).setStartDelay(5000).setDuration(1200).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        fire.stop();
        sound.stop();
        if (screensaver) {
            finish();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (screensaver) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                finish();
            }
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                boolean on = !sound.isRunning();
                if (on) {
                    sound.start();
                } else {
                    sound.stop();
                }
                prefs.edit().putBoolean(P_SOUND, on).apply();
                flashHint(on ? "Звук увімкнено" : "Звук вимкнено");
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                boolean show = clock.getVisibility() != View.VISIBLE;
                clock.setVisibility(show ? View.VISIBLE : View.GONE);
                prefs.edit().putBoolean(P_CLOCK, show).apply();
                return true;
            case KeyEvent.KEYCODE_MENU:
                SleepTimer.show(this, () -> {
                    String st = SleepTimer.status(this);
                    flashHint(st == null ? "Таймер сну вимкнено" : "Таймер сну: " + st);
                });
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    private void flashHint(String text) {
        hint.animate().cancel();
        hint.setText(text);
        hint.setAlpha(1f);
        hint.animate().alpha(0f).setStartDelay(2500).setDuration(800).start();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
