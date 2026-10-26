package org.azify.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import java.time.LocalDate;
import java.time.ZoneId;

final class BandwidthUsage {
    private static final String PREFS = "bandwidth-usage";
    private static final String WIFI = "w_";
    private static final String MOBILE = "m_";

    static final class Snapshot {
        final long wifiToday;
        final long mobileToday;
        final long wifiWeek;
        final long mobileWeek;

        Snapshot(long wifiToday, long mobileToday, long wifiWeek, long mobileWeek) {
            this.wifiToday = wifiToday;
            this.mobileToday = mobileToday;
            this.wifiWeek = wifiWeek;
            this.mobileWeek = mobileWeek;
        }
    }

    private BandwidthUsage() { }

    static void recordBytes(Context context, long bytes) {
        if (context == null || bytes <= 0) {
            return;
        }
        String prefix = transportPrefix(context);
        if (prefix == null) {
            return;
        }
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long day = LocalDate.now(ZoneId.systemDefault()).toEpochDay();
        String key = prefix + day;
        long current = prefs.getLong(key, 0L);
        prefs.edit().putLong(key, current + bytes).apply();
    }

    static Snapshot snapshot(Context context) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long today = LocalDate.now(ZoneId.systemDefault()).toEpochDay();
        long wifiToday = prefs.getLong(WIFI + today, 0L);
        long mobileToday = prefs.getLong(MOBILE + today, 0L);
        long wifiWeek = 0L;
        long mobileWeek = 0L;
        for (int i = 0; i < 7; i++) {
            long day = today - i;
            wifiWeek += prefs.getLong(WIFI + day, 0L);
            mobileWeek += prefs.getLong(MOBILE + day, 0L);
        }
        return new Snapshot(wifiToday, mobileToday, wifiWeek, mobileWeek);
    }

    private static String transportPrefix(Context context) {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) {
            return null;
        }
        NetworkCapabilities caps = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        if (caps == null) {
            return null;
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return WIFI;
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return MOBILE;
        }
        return null;
    }
}
