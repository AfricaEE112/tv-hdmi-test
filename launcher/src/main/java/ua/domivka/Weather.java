package ua.domivka;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Locale;

/**
 * Погода з Open-Meteo (безкоштовно, без ключа). Місто: Київ або інше місто Київської області.
 * Останній результат зберігається, тож після ввімкнення погода видна одразу, навіть без інтернету.
 * Android 7.0 не знає кореневих сертифікатів Let's Encrypt, тому їх додано в network_security_config.
 */
final class Weather {

    static final String P_CITY = "weather_city";
    private static final String P_JSON = "weather_json";
    private static final String P_TIME = "weather_time";
    static final long REFRESH_MS = 30 * 60_000L;

    /** Міста Київської області: назва, широта, довгота. */
    static final String[] CITY_NAMES = {
            "Київ", "Бровари", "Бориспіль", "Ірпінь", "Буча", "Вишгород", "Боярка",
            "Васильків", "Обухів", "Фастів", "Біла Церква", "Переяслав", "Славутич",
            "Вороньків",
    };
    private static final double[][] CITY_COORDS = {
            {50.4501, 30.5234}, {50.5110, 30.7909}, {50.3527, 30.9550}, {50.5218, 30.2506},
            {50.5435, 30.2120}, {50.5848, 30.4890}, {50.3290, 30.2880}, {50.1773, 30.3178},
            {50.1069, 30.6176}, {50.0780, 29.9177}, {49.7968, 30.1311}, {50.0650, 31.4450},
            {51.5223, 30.7202}, {50.2223, 30.9011},
    };

    static final class Day {
        int dayOfWeek; // Calendar.MONDAY...
        int code;
        int max;
        int min;
    }

    static final class Hour {
        int hour; // 0..23
        int code;
        int temp;
        boolean isDay;
        String key; // «2026-11-14T18:00»
    }

    static final class Data {
        String city;
        int temp;
        int feels;
        int code;
        boolean isDay;
        int wind;
        Day[] days = new Day[0];
        Hour[] hours = new Hour[0];
        long time;
    }

    private Weather() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    static int cityIndex(Context c) {
        int i = prefs(c).getInt(P_CITY, 0);
        return i >= 0 && i < CITY_NAMES.length ? i : 0;
    }

    static void setCity(Context c, int index) {
        prefs(c).edit().putInt(P_CITY, index).putLong(P_TIME, 0).apply();
    }

    static boolean isStale(Context c) {
        return System.currentTimeMillis() - prefs(c).getLong(P_TIME, 0) > REFRESH_MS;
    }

    /** Збережена погода (або null). */
    static Data cached(Context c) {
        String json = prefs(c).getString(P_JSON, null);
        if (json == null) {
            return null;
        }
        try {
            Data d = parse(json);
            d.city = CITY_NAMES[cityIndex(c)];
            d.time = prefs(c).getLong(P_TIME, 0);
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Завантажити свіжу погоду. Викликати не в головному потоці. Помилка: null. */
    static Data fetch(Context c) {
        int i = cityIndex(c);
        String url = String.format(Locale.ROOT,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,apparent_temperature,weather_code,is_day,wind_speed_10m"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                        + "&hourly=temperature_2m,weather_code,is_day&forecast_hours=24"
                        + "&wind_speed_unit=ms&timezone=Europe%%2FKyiv&forecast_days=6",
                CITY_COORDS[i][0], CITY_COORDS[i][1]);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() != 200) {
                return null;
            }
            String json = read(conn.getInputStream());
            Data d = parse(json);
            d.city = CITY_NAMES[i];
            d.time = System.currentTimeMillis();
            prefs(c).edit().putString(P_JSON, json).putLong(P_TIME, d.time).apply();
            return d;
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return out.toString("UTF-8");
    }

    private static Data parse(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        JSONObject cur = root.getJSONObject("current");
        Data d = new Data();
        d.temp = (int) Math.round(cur.getDouble("temperature_2m"));
        d.feels = (int) Math.round(cur.getDouble("apparent_temperature"));
        d.code = cur.getInt("weather_code");
        d.isDay = cur.optInt("is_day", 1) == 1;
        d.wind = (int) Math.round(cur.optDouble("wind_speed_10m", 0));

        JSONObject daily = root.getJSONObject("daily");
        JSONArray dates = daily.getJSONArray("time");
        JSONArray codes = daily.getJSONArray("weather_code");
        JSONArray max = daily.getJSONArray("temperature_2m_max");
        JSONArray min = daily.getJSONArray("temperature_2m_min");
        d.days = new Day[dates.length()];
        for (int k = 0; k < dates.length(); k++) {
            Day day = new Day();
            String[] ymd = dates.getString(k).split("-");
            Calendar cal = Calendar.getInstance();
            cal.set(Integer.parseInt(ymd[0]), Integer.parseInt(ymd[1]) - 1, Integer.parseInt(ymd[2]));
            day.dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
            day.code = codes.getInt(k);
            day.max = (int) Math.round(max.getDouble(k));
            day.min = (int) Math.round(min.getDouble(k));
            d.days[k] = day;
        }

        // Погодинно (у старому збереженому прогнозі цього ще немає)
        JSONObject hourly = root.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray times = hourly.getJSONArray("time");
            JSONArray ht = hourly.getJSONArray("temperature_2m");
            JSONArray hc = hourly.getJSONArray("weather_code");
            JSONArray hd = hourly.optJSONArray("is_day");
            java.util.List<Hour> list = new java.util.ArrayList<>();
            for (int k = 0; k < times.length(); k++) {
                // «2026-11-14T18:00», час київський
                Hour h = new Hour();
                h.hour = Integer.parseInt(times.getString(k).substring(11, 13));
                h.temp = (int) Math.round(ht.getDouble(k));
                h.code = hc.getInt(k);
                h.isDay = hd == null || hd.optInt(k, 1) == 1;
                h.key = times.getString(k);
                list.add(h);
            }
            d.hours = list.toArray(new Hour[0]);
        }
        return d;
    }

    /**
     * Наступні години з кроком [step], починаючи з найближчої після поточної.
     * Застарілі (якщо прогноз давно збережений) пропускаються.
     */
    static Hour[] upcoming(Data d, int count, int step) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:00", Locale.ROOT);
        f.setTimeZone(java.util.TimeZone.getTimeZone("Europe/Kyiv"));
        String now = f.format(new java.util.Date());
        java.util.List<Hour> out = new java.util.ArrayList<>();
        int start = -1;
        for (int k = 0; k < d.hours.length; k++) {
            if (d.hours[k].key.compareTo(now) > 0) {
                start = k;
                break;
            }
        }
        if (start < 0) {
            return new Hour[0];
        }
        for (int k = start; k < d.hours.length && out.size() < count; k += step) {
            out.add(d.hours[k]);
        }
        return out.toArray(new Hour[0]);
    }

    static String temp(int t) {
        return (t > 0 ? "+" : "") + t + "°";
    }

    static String dayName(int dayOfWeek) {
        switch (dayOfWeek) {
            case Calendar.MONDAY: return "Пн";
            case Calendar.TUESDAY: return "Вт";
            case Calendar.WEDNESDAY: return "Ср";
            case Calendar.THURSDAY: return "Чт";
            case Calendar.FRIDAY: return "Пт";
            case Calendar.SATURDAY: return "Сб";
            default: return "Нд";
        }
    }

    /** Опис погоди за кодом WMO. */
    static String describe(int code) {
        if (code == 0) return "Ясно";
        if (code == 1) return "Переважно ясно";
        if (code == 2) return "Мінлива хмарність";
        if (code == 3) return "Хмарно";
        if (code == 45 || code == 48) return "Туман";
        if (code >= 51 && code <= 57) return "Мряка";
        if (code >= 61 && code <= 65) return "Дощ";
        if (code == 66 || code == 67) return "Крижаний дощ";
        if (code >= 71 && code <= 77) return "Сніг";
        if (code >= 80 && code <= 82) return "Злива";
        if (code == 85 || code == 86) return "Снігопад";
        if (code >= 95) return "Гроза";
        return "—";
    }

    /** Іконка погоди за кодом WMO. */
    static int icon(int code, boolean day) {
        if (code == 0 || code == 1) return day ? R.drawable.ic_w_sun : R.drawable.ic_moon;
        if (code == 2) return day ? R.drawable.ic_w_partly : R.drawable.ic_w_cloud;
        if (code == 3) return R.drawable.ic_w_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_w_fog;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_w_snow;
        if (code >= 95) return R.drawable.ic_w_storm;
        if (code >= 51) return R.drawable.ic_w_rain;
        return R.drawable.ic_w_cloud;
    }
}
