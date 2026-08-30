package com.iris.assistant;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

/**
 * Fetches current weather and a short forecast from Open-Meteo — a free,
 * keyless weather API (https://open-meteo.com). Network call runs on a
 * background thread; result is delivered on the main thread.
 */
public final class WeatherService {
    private static final Handler main = new Handler(Looper.getMainLooper());

    public interface WeatherListener {
        void onResult(String spoken);
        void onError(String message);
    }

    private WeatherService() { }

    /**
     * @param includeForecast when true, appends today's high/low and tomorrow's outlook.
     * @param place            optional place name to mention (may be null).
     */
    public static void fetch(double lat, double lon, boolean includeForecast,
                             String place, WeatherListener listener) {
        new Thread(() -> {
            try {
                String url = "https://api.open-meteo.com/v1/forecast"
                        + "?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                        + "&timezone=auto&forecast_days=2";
                String json = httpGet(url);
                JSONObject root = new JSONObject(json);

                JSONObject cur = root.getJSONObject("current");
                double temp = cur.optDouble("temperature_2m", Double.NaN);
                double feels = cur.optDouble("apparent_temperature", Double.NaN);
                int code = cur.optInt("weather_code", -1);

                StringBuilder sb = new StringBuilder();
                String where = (place != null && !place.isEmpty()) ? " in " + place : "";
                sb.append("Right now").append(where).append(", it's ")
                        .append(Math.round(temp)).append(" degrees");
                if (!Double.isNaN(feels) && Math.abs(feels - temp) >= 2) {
                    sb.append(" (feels like ").append(Math.round(feels)).append(")");
                }
                sb.append(" with ").append(describe(code)).append(".");

                if (includeForecast) {
                    JSONObject daily = root.optJSONObject("daily");
                    if (daily != null) {
                        double hi = daily.getJSONArray("temperature_2m_max").optDouble(0, Double.NaN);
                        double lo = daily.getJSONArray("temperature_2m_min").optDouble(0, Double.NaN);
                        int pop = daily.getJSONArray("precipitation_probability_max").optInt(0, -1);
                        if (!Double.isNaN(hi) && !Double.isNaN(lo)) {
                            sb.append(" Today's high is ").append(Math.round(hi))
                              .append(" and low ").append(Math.round(lo)).append(".");
                        }
                        if (pop >= 0) sb.append(" Chance of rain ").append(pop).append(" percent.");
                        // Tomorrow
                        int tCode = daily.getJSONArray("weather_code").optInt(1, -1);
                        double tHi = daily.getJSONArray("temperature_2m_max").optDouble(1, Double.NaN);
                        double tLo = daily.getJSONArray("temperature_2m_min").optDouble(1, Double.NaN);
                        if (tCode >= 0 && !Double.isNaN(tHi)) {
                            sb.append(" Tomorrow: ").append(describe(tCode))
                              .append(", ").append(Math.round(tLo)).append(" to ")
                              .append(Math.round(tHi)).append(" degrees.");
                        }
                    }
                }
                final String out = sb.toString();
                main.post(() -> listener.onResult(out));
            } catch (Exception e) {
                main.post(() -> listener.onError(e.getMessage()));
            }
        }, "IRIS-Weather").start();
    }

    private static String httpGet(String urlStr) throws Exception {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "IRIS-Android");
        conn.connect();
        if (conn.getResponseCode() / 100 != 2) throw new Exception("HTTP " + conn.getResponseCode());
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    /** Map WMO weather codes to a spoken description. */
    private static String describe(int code) {
        switch (code) {
            case 0: return "clear skies";
            case 1: return "mostly clear";
            case 2: return "partly cloudy";
            case 3: return "overcast skies";
            case 45: case 48: return "fog";
            case 51: case 53: case 55: return "drizzle";
            case 56: case 57: return "freezing drizzle";
            case 61: case 63: case 65: return "rain";
            case 66: case 67: return "freezing rain";
            case 71: case 73: case 75: return "snow";
            case 77: return "snow grains";
            case 80: case 81: case 82: return "rain showers";
            case 85: case 86: return "snow showers";
            case 95: return "a thunderstorm";
            case 96: case 99: return "a thunderstorm with hail";
            default: return "changing conditions";
        }
    }
}
