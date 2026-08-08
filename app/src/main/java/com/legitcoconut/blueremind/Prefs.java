package com.legitcoconut.blueremind;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Watched MAC addresses and per-device renames. Read by the UI and the receiver. */
final class Prefs {
    private static final String FILE = "blueremind";
    private static final String KEY_MONITORED = "monitored";
    private static final String KEY_NAME_PREFIX = "name_";
    private static final String KEY_SINCE_PREFIX = "since_";
    private static final String KEY_ENABLED_PREFIX = "on_";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Always returns a mutable copy. The set handed back by getStringSet must not be edited. */
    static Set<String> monitored(Context c) {
        return new HashSet<>(sp(c).getStringSet(KEY_MONITORED, Collections.emptySet()));
    }

    static void setMonitored(Context c, String address, boolean on) {
        Set<String> s = monitored(c);
        SharedPreferences.Editor e = sp(c).edit();
        if (on) {
            s.add(address);
            // Overwritten on every switch-on, so re-enabling floats a device back to the top.
            e.putLong(KEY_ENABLED_PREFIX + address, System.currentTimeMillis());
        } else {
            s.remove(address);
            e.remove(KEY_ENABLED_PREFIX + address);
        }
        e.putStringSet(KEY_MONITORED, s).apply();
    }

    /** When monitoring was switched on, for ordering the list. 0 if never, or switched on before
     *  this was recorded. */
    static long monitoredSince(Context c, String address) {
        return sp(c).getLong(KEY_ENABLED_PREFIX + address, 0L);
    }

    static String customName(Context c, String address) {
        return sp(c).getString(KEY_NAME_PREFIX + address, null);
    }

    /** Pass null or blank to fall back to the name Bluetooth reports. */
    static void setCustomName(Context c, String address, String name) {
        SharedPreferences.Editor e = sp(c).edit();
        if (name == null || name.trim().isEmpty()) {
            e.remove(KEY_NAME_PREFIX + address);
        } else {
            e.putString(KEY_NAME_PREFIX + address, name.trim());
        }
        e.apply();
    }

    /** Wall-clock millis the device connected, or 0 if we believe it is disconnected. */
    static long connectedSince(Context c, String address) {
        return sp(c).getLong(KEY_SINCE_PREFIX + address, 0L);
    }

    /** First writer wins, so reopening the app doesn't restart the timer. */
    static void markConnected(Context c, String address, long whenMillis) {
        if (connectedSince(c, address) == 0L) {
            sp(c).edit().putLong(KEY_SINCE_PREFIX + address, whenMillis).apply();
        }
    }

    static void markDisconnected(Context c, String address) {
        sp(c).edit().remove(KEY_SINCE_PREFIX + address).apply();
    }

    /** Called when the adapter is off. Nothing can be connected, so drop any stale timers. */
    static void clearAllConnected(Context c) {
        SharedPreferences.Editor e = sp(c).edit();
        for (String key : sp(c).getAll().keySet()) {
            if (key.startsWith(KEY_SINCE_PREFIX)) {
                e.remove(key);
            }
        }
        e.apply();
    }

    /** Single source of truth for what a device is called, for both the list and the alerts. */
    static String displayName(Context c, BluetoothDevice d) {
        String custom = customName(c, d.getAddress());
        if (custom != null && !custom.isEmpty()) {
            return custom;
        }
        try {
            String n = d.getName();
            return n == null || n.isEmpty() ? d.getAddress() : n;
        } catch (SecurityException e) {
            return d.getAddress(); // BLUETOOTH_CONNECT revoked
        }
    }
}
