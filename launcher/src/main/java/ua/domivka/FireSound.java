package ua.domivka;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;

/**
 * Звук каміна, синтезований на льоту: тихий гул полум'я (низькочастотний «коричневий» шум)
 * і тріск дров (короткі клацання, часто групками). Жодних звукових файлів.
 */
final class FireSound {

    private static final int RATE = 22050;

    private volatile boolean running;
    private Thread thread;
    private int seed = 0x6D2B79F5;

    synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "fire-sound");
        thread.start();
    }

    synchronized void stop() {
        running = false;
        if (thread != null) {
            try {
                thread.join(400);
            } catch (InterruptedException ignored) {
            }
            thread = null;
        }
    }

    boolean isRunning() {
        return running;
    }

    private float rnd() {
        int s = seed;
        s ^= s << 13;
        s ^= s >>> 17;
        s ^= s << 5;
        seed = s;
        return (s >>> 8) / 16777216f;
    }

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        AudioTrack track = null;
        try {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192) * 2, AudioTrack.MODE_STREAM);
            track.play();

            short[] buf = new short[1024];
            float brown = 0f;
            float rumble = 0f;
            float gain = 0.8f;
            float gainTarget = 0.8f;
            int gainCountdown = 0;

            float env = 0f;          // огинаюча поточного клацання
            float decay = 0f;
            float amp = 0f;
            float prevNoise = 0f;
            int clusterLeft = 0;     // скільки ще клацань у групці
            int clusterGap = 0;      // через скільки семплів наступне

            float fadeIn = 0f;       // плавний старт, щоб не клацнуло

            while (running) {
                for (int i = 0; i < buf.length; i++) {
                    // Гул: коричневий шум, ще раз згладжений, з повільною зміною гучності
                    float white = rnd() * 2f - 1f;
                    brown = 0.995f * brown + 0.05f * white;
                    rumble += 0.04f * (brown - rumble);
                    if (--gainCountdown <= 0) {
                        gainTarget = 0.55f + 0.6f * rnd();
                        gainCountdown = RATE / 2 + (int) (rnd() * RATE);
                    }
                    gain += (gainTarget - gain) * 0.00005f;
                    float roar = rumble * 0.9f * gain;

                    // Тріск: випадкові групки коротких клацань
                    if (clusterLeft == 0 && rnd() < 7f / RATE) {
                        clusterLeft = 1 + (int) (rnd() * 4);
                        clusterGap = 0;
                    }
                    if (clusterLeft > 0 && --clusterGap <= 0) {
                        env = 1f;
                        float r = rnd();
                        amp = 0.08f + 0.55f * r * r;
                        float lengthSec = 0.0015f + 0.008f * rnd();
                        decay = (float) Math.exp(-1.0 / (RATE * lengthSec));
                        clusterLeft--;
                        clusterGap = (int) (RATE * (0.004f + 0.03f * rnd()));
                    }
                    float noise = rnd() * 2f - 1f;
                    float crackle = (noise - 0.9f * prevNoise) * env * amp; // високі частоти: чіткий тріск
                    prevNoise = noise;
                    env *= decay;

                    float s = roar + crackle;
                    s = s / (1f + Math.abs(s));            // м'яке обмеження
                    if (fadeIn < 1f) {
                        fadeIn += 1f / RATE;
                        s *= fadeIn;
                    }
                    buf[i] = (short) (s * 0.85f * 32767);
                }
                track.write(buf, 0, buf.length);
            }
            track.pause();
            track.flush();
        } catch (Throwable ignored) {
            // Звук — не головне: якщо аудіо недоступне, камін працює без нього
        } finally {
            if (track != null) {
                try {
                    track.stop();
                } catch (Throwable ignored) {
                }
                track.release();
            }
        }
    }
}
