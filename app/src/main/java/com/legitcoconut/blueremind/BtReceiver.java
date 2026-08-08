package com.legitcoconut.blueremind;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

public class BtReceiver extends BroadcastReceiver {

    private static final String CH_CONNECTED = "connected";
    private static final String CH_DISCONNECTED = "disconnected";

    /** Buzz-pause-buzz-pause-long-buzz. Hard to miss in a pocket. */
    private static final long[] PATTERN = {0, 400, 200, 400, 200, 800};

    @Override
    public void onReceive(Context ctx, Intent intent) {
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null) {
            return;
        }
        String address = device.getAddress();
        boolean connected = BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction());

        // Track every device, not just monitored ones. Otherwise switching monitoring on for an
        // already-connected device would show no uptime until it next reconnects.
        if (connected) {
            Prefs.markConnected(ctx, address, System.currentTimeMillis());
        } else {
            Prefs.markDisconnected(ctx, address);
        }

        if (!Prefs.monitored(ctx).contains(address)) {
            return; // not a device the user selected
        }

        String name = Prefs.displayName(ctx, device);

        ensureChannels(ctx);

        if (!connected) {
            vibrate(ctx);
        }

        PendingIntent open = PendingIntent.getActivity(
                ctx, 0, new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder n = new NotificationCompat.Builder(
                ctx, connected ? CH_CONNECTED : CH_DISCONNECTED)
                .setSmallIcon(R.drawable.ic_bt)
                .setContentTitle(connected ? name + " connected" : name + " disconnected")
                .setContentText(connected
                        ? "BlueRemind is watching this device."
                        : "You may have left it behind.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(connected
                        ? NotificationCompat.CATEGORY_STATUS
                        : NotificationCompat.CATEGORY_ALARM)
                .setPriority(connected
                        ? NotificationCompat.PRIORITY_DEFAULT
                        : NotificationCompat.PRIORITY_HIGH);

        Bitmap picture = Icons.load(ctx, address);
        if (picture != null) {
            n.setLargeIcon(picture);
        }

        try {
            // One notification slot per device, so two devices don't overwrite each other.
            NotificationManagerCompat.from(ctx).notify(address.hashCode(), n.build());
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS denied. The vibration above still fired.
        }
    }

    private static void vibrate(Context ctx) {
        Vibrator v;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = ctx.getSystemService(VibratorManager.class);
            v = vm == null ? null : vm.getDefaultVibrator();
        } else {
            v = ctx.getSystemService(Vibrator.class);
        }
        if (v == null || !v.hasVibrator()) {
            return;
        }
        v.vibrate(VibrationEffect.createWaveform(PATTERN, -1));
    }

    /** Cheap and idempotent. The system ignores a channel that already exists. */
    private static void ensureChannels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }

        NotificationChannel connected = new NotificationChannel(
                CH_CONNECTED, "Device connected", NotificationManager.IMPORTANCE_DEFAULT);
        connected.setDescription("A device you monitor came back into range.");
        connected.enableVibration(false);

        NotificationChannel disconnected = new NotificationChannel(
                CH_DISCONNECTED, "Device disconnected", NotificationManager.IMPORTANCE_HIGH);
        disconnected.setDescription("A device you monitor dropped its connection.");
        disconnected.enableVibration(true);
        disconnected.setVibrationPattern(PATTERN);

        nm.createNotificationChannel(connected);
        nm.createNotificationChannel(disconnected);
    }
}
