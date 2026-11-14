package ua.domivka;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;

/**
 * Режим каміну: справжнє відео (якщо завантажене) або намальований вогонь.
 * OK: звук; ◀ ▶: інший камін; ▲ ▼: годинник; Menu: таймер сну; «Назад»: додому.
 * Режим заставки: без звуку, будь-яка кнопка закриває.
 */
public class FireplaceActivity extends Activity {

    static final String EXTRA_SCREENSAVER = "screensaver";
    private static final String P_SOUND = "fire_sound";
    private static final String P_CLOCK = "fire_clock";

    private SharedPreferences prefs;
    private FireView fire;
    private VideoView video;
    private MediaPlayer player;
    private boolean videoHasAudio;
    private final FireSound sound = new FireSound();
    private TextClock clock;
    private TextView hint;
    private boolean screensaver;
    private boolean resumed;

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

        video = new VideoView(this);
        video.setVisibility(View.GONE);
        video.setFocusable(false);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        clock = new TextClock(this);
        clock.setFormat12Hour("HH:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        clock.setTextColor(Color.argb(150, 255, 214, 170));
        clock.setShadowLayer(12f, 0, 0, Color.BLACK);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        cp.setMargins(0, dp(20), dp(40), 0);
        root.addView(clock, cp);
        clock.setVisibility(prefs.getBoolean(P_CLOCK, true) ? View.VISIBLE : View.GONE);

        hint = new TextView(this);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        hint.setTextColor(Color.argb(200, 255, 225, 200));
        hint.setShadowLayer(10f, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hp.bottomMargin = dp(12);
        root.addView(hint, hp);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        startScene(screensaver ? "Натисніть будь-яку кнопку"
                : "OK: звук   ·   ◀ ▶: інший камін   ·   ▲▼: годинник   ·   Menu: таймер сну");
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        fire.stop();
        sound.stop();
        try {
            video.stopPlayback();
        } catch (Throwable ignored) {
        }
        player = null;
        if (screensaver) {
            finish();
        }
    }

    // ---------------------------------------------------------------- сцена

    private void startScene(String message) {
        String id = FireVideos.selected(this);
        if (FireVideos.isReady(this, id)) {
            playVideo(FireVideos.file(this, id));
            flashHint(message);
            return;
        }
        showDrawn();
        if (FireVideos.isVideo(id) && !screensaver) {
            String busy = FireVideos.downloading();
            if (busy != null && !busy.equals(id)) {
                flashHint("Зараз завантажується «" + FireVideos.title(busy) + "», поки що горить намальований");
                return;
            }
            if (busy != null) {
                flashHint("«" + FireVideos.title(id) + "» ще завантажується, поки що горить намальований");
                return;
            }
            flashHint("Завантажую «" + FireVideos.title(id) + "»...   Поки що горить намальований");
            FireVideos.download(this, id, new FireVideos.Listener() {
                @Override
                public void onProgress(int percent) {
                    if (resumed) {
                        hint.animate().cancel();
                        hint.setAlpha(1f);
                        hint.setText("Завантажую «" + FireVideos.title(id) + "»: " + percent + "%");
                    }
                }

                @Override
                public void onDone(boolean ok) {
                    if (!resumed) {
                        return;
                    }
                    if (ok && id.equals(FireVideos.selected(FireplaceActivity.this))) {
                        playVideo(FireVideos.file(FireplaceActivity.this, id));
                        flashHint("Готово! Камін завантажено, далі він працює і без інтернету");
                    } else if (!ok) {
                        flashHint("Не вдалося завантажити відео: перевірте інтернет");
                    }
                }
            });
        } else {
            flashHint(message);
        }
    }

    private void showDrawn() {
        try {
            video.stopPlayback();
        } catch (Throwable ignored) {
        }
        player = null;
        videoHasAudio = false;
        video.setVisibility(View.GONE);
        fire.setVisibility(View.VISIBLE);
        fire.start();
        applySound();
    }

    private void playVideo(File f) {
        fire.stop();
        fire.setVisibility(View.GONE);
        sound.stop();
        video.setVisibility(View.VISIBLE);
        video.setOnPreparedListener(mp -> {
            player = mp;
            mp.setLooping(true);
            videoHasAudio = hasAudio(mp);
            applySound();
        });
        video.setOnErrorListener((mp, what, extra) -> {
            // Відео не відтворилося (пошкоджене?): видаляємо, щоб наступного разу завантажити знову
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            showDrawn();
            flashHint("Не вдалося відтворити відео, показую намальований камін");
            return true;
        });
        video.setVideoPath(f.getPath());
        video.start();
    }

    private static boolean hasAudio(MediaPlayer mp) {
        try {
            for (MediaPlayer.TrackInfo t : mp.getTrackInfo()) {
                if (t.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Справжній звук відео, якщо він є; інакше синтезований тріск. */
    private void applySound() {
        boolean on = !screensaver && prefs.getBoolean(P_SOUND, true);
        if (player != null && videoHasAudio) {
            sound.stop();
            try {
                player.setVolume(on ? 1f : 0f, on ? 1f : 0f);
            } catch (Throwable ignored) {
            }
            return;
        }
        if (player != null) {
            try {
                player.setVolume(0f, 0f);
            } catch (Throwable ignored) {
            }
        }
        if (on) {
            sound.start();
        } else {
            sound.stop();
        }
    }

    private void switchFireplace(int step) {
        int i = FireVideos.index(FireVideos.selected(this));
        int n = FireVideos.IDS.length;
        String next = FireVideos.IDS[((i + step) % n + n) % n];
        FireVideos.select(this, next);
        startScene(FireVideos.title(next));
    }

    // ---------------------------------------------------------------- кнопки

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
                boolean on = !prefs.getBoolean(P_SOUND, true);
                prefs.edit().putBoolean(P_SOUND, on).apply();
                applySound();
                flashHint(on ? "Звук увімкнено" : "Звук вимкнено");
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                switchFireplace(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                switchFireplace(1);
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
        hint.animate().alpha(0f).setStartDelay(4000).setDuration(1000).start();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
