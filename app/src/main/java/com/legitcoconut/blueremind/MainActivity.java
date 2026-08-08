package com.legitcoconut.blueremind;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private final List<BluetoothDevice> devices = new ArrayList<>();
    private final DeviceAdapter adapter = new DeviceAdapter();

    /** Decoded custom pictures, keyed by MAC. Absent key = not looked up, null value = no picture. */
    private final Map<String, Bitmap> iconCache = new HashMap<>();

    /** Which device the gallery picker was opened for. */
    private String pendingAddress;

    private ListView list;
    private View emptyBox;
    private TextView emptyText;
    private View connectedSection;
    private GridLayout connectedGrid;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            rebuildConnected();
            ticker.postDelayed(this, 30_000L);
        }
    };

    // Android 13+ uses the system photo picker; below that it falls back to OPEN_DOCUMENT.
    // Either way no storage permission is needed. Must be registered before onStart.
    private final ActivityResultLauncher<PickVisualMediaRequest> picker =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                String address = pendingAddress;
                pendingAddress = null;
                if (uri == null || address == null) {
                    return;
                }
                if (Icons.save(this, address, uri)) {
                    iconCache.remove(address);
                    refresh();
                } else {
                    Toast.makeText(this, R.string.picture_failed, Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);

        list = findViewById(R.id.list);
        emptyBox = findViewById(R.id.empty_box);
        emptyText = findViewById(R.id.empty_text);

        // Header scrolls with the list, so a tall grid doesn't permanently eat the screen.
        View header = getLayoutInflater().inflate(R.layout.header_connected, list, false);
        connectedSection = header.findViewById(R.id.connected_section);
        connectedGrid = header.findViewById(R.id.connected_grid);
        list.addHeaderView(header, null, false);
        list.setAdapter(adapter);

        Drawable d = ((ImageView) findViewById(R.id.empty_icon)).getDrawable();
        if (d instanceof Animatable) {
            ((Animatable) d).start();
        }

        requestMissingPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadDevices(); // picks up devices paired, or Bluetooth toggled, while we were away
        ticker.removeCallbacks(tick);
        ticker.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticker.removeCallbacks(tick);
    }

    private void requestMissingPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            need.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) {
            ActivityCompat.requestPermissions(this, need.toArray(new String[0]), 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        loadDevices();
    }

    private boolean granted(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void loadDevices() {
        devices.clear();
        String problem = null;

        BluetoothManager bm = getSystemService(BluetoothManager.class);
        BluetoothAdapter bt = bm == null ? null : bm.getAdapter();

        if (bt == null) {
            problem = getString(R.string.no_bluetooth);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            problem = getString(R.string.need_permission);
        } else if (!bt.isEnabled()) {
            Prefs.clearAllConnected(this); // adapter off, so nothing is connected; drop stale timers
            problem = getString(R.string.bluetooth_off);
        } else {
            try {
                devices.addAll(bt.getBondedDevices());
            } catch (SecurityException e) {
                problem = getString(R.string.need_permission);
            }
            if (problem == null && devices.isEmpty()) {
                problem = getString(R.string.no_paired_devices);
            }
            if (problem == null) {
                seedConnectedState(bt);
            }
        }

        boolean ok = problem == null;
        emptyText.setText(ok ? "" : problem);
        emptyBox.setVisibility(ok ? View.GONE : View.VISIBLE);
        list.setVisibility(ok ? View.VISIBLE : View.GONE);

        refresh();
        if (ok) {
            list.scheduleLayoutAnimation();
        }
    }

    /**
     * The receiver only learns about connections as they happen, so a device already connected
     * before this app was installed (or before it was switched on for monitoring) would be
     * invisible. Backfill by asking who is live right now.
     */
    private void seedConnectedState(BluetoothAdapter bt) {
        long now = System.currentTimeMillis();
        if (!reconcileViaAclState(now)) {
            seedViaProfileProxies(bt, now);
        }
    }

    /**
     * BluetoothDevice.isConnected() is the only call that answers "is the ACL link up" for any
     * device regardless of profile, watches included. It is hidden, so it goes through
     * reflection and reports failure so the caller can fall back.
     *
     * <p>Unlike the profile proxies this is authoritative both ways, so it also clears timers
     * for devices that dropped while the receiver was not running.
     */
    private boolean reconcileViaAclState(long now) {
        try {
            Method isConnected = BluetoothDevice.class.getMethod("isConnected");
            for (BluetoothDevice d : devices) {
                if (Boolean.TRUE.equals(isConnected.invoke(d))) {
                    Prefs.markConnected(this, d.getAddress(), now);
                } else {
                    Prefs.markDisconnected(this, d.getAddress());
                }
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false; // blocked by non-SDK restrictions on this ROM
        }
    }

    /** Fallback when the hidden call is unavailable. Covers audio gear only, not watches. */
    private void seedViaProfileProxies(BluetoothAdapter bt, long now) {
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        try {
            if (bm != null) {
                for (BluetoothDevice d : bm.getConnectedDevices(BluetoothProfile.GATT)) {
                    Prefs.markConnected(this, d.getAddress(), now);
                }
            }
            BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    try {
                        for (BluetoothDevice d : proxy.getConnectedDevices()) {
                            Prefs.markConnected(MainActivity.this, d.getAddress(), now);
                        }
                    } catch (SecurityException ignored) {
                        // permission revoked between the check and the callback
                    }
                    bt.closeProfileProxy(profile, proxy);
                    rebuildConnected();
                }

                @Override
                public void onServiceDisconnected(int profile) {
                }
            };
            bt.getProfileProxy(this, listener, BluetoothProfile.A2DP);
            bt.getProfileProxy(this, listener, BluetoothProfile.HEADSET);
        } catch (SecurityException ignored) {
            // BLUETOOTH_CONNECT revoked, so the grid just stays empty
        }
    }

    /** Monitored devices that are currently connected, two per row. */
    private void rebuildConnected() {
        if (connectedGrid == null) {
            return;
        }
        List<BluetoothDevice> live = new ArrayList<>();
        for (BluetoothDevice d : devices) {
            String address = d.getAddress();
            if (Prefs.monitored(this).contains(address) && Prefs.connectedSince(this, address) > 0) {
                live.add(d);
            }
        }

        connectedSection.setVisibility(live.isEmpty() ? View.GONE : View.VISIBLE);
        // ponytail: full rebuild every tick. Fine for a handful of cards; diff the text if it ever grows.
        connectedGrid.removeAllViews();

        int gap = Math.round(6 * getResources().getDisplayMetrics().density);
        for (int i = 0; i < live.size(); i++) {
            BluetoothDevice device = live.get(i);
            String address = device.getAddress();

            View card = getLayoutInflater().inflate(R.layout.item_connected, connectedGrid, false);
            ((TextView) card.findViewById(R.id.name)).setText(Prefs.displayName(this, device));
            ((TextView) card.findViewById(R.id.duration))
                    .setText(uptime(Prefs.connectedSince(this, address)));
            bindIcon(card.findViewById(R.id.icon), address, 12);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
            lp.columnSpec = GridLayout.spec(i % 2, 1f);
            lp.rowSpec = GridLayout.spec(i / 2);
            lp.setMargins(gap, gap, gap, gap);
            connectedGrid.addView(card, lp);
        }

        // With a single card, column 1 is never populated and column 0 would swallow the full
        // width. An empty spacer forces the 50/50 split. Odd counts above 1 already split
        // correctly, because an earlier row has given column 1 a width.
        if (live.size() == 1) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = 1;
            lp.columnSpec = GridLayout.spec(1, 1f);
            lp.rowSpec = GridLayout.spec(0);
            connectedGrid.addView(new Space(this), lp);
        }
    }

    private void refresh() {
        sortDevices();
        adapter.notifyDataSetChanged();
        rebuildConnected();
    }

    /** Monitored devices on top, most recently switched on first; everything else by name. */
    private void sortDevices() {
        Set<String> monitored = Prefs.monitored(this);
        Collections.sort(devices, (a, b) -> {
            boolean aOn = monitored.contains(a.getAddress());
            boolean bOn = monitored.contains(b.getAddress());
            if (aOn != bOn) {
                return aOn ? -1 : 1;
            }
            if (aOn) {
                int recency = Long.compare(Prefs.monitoredSince(this, b.getAddress()),
                        Prefs.monitoredSince(this, a.getAddress()));
                if (recency != 0) {
                    return recency;
                }
                // Devices switched on before timestamps were recorded all tie at 0.
            }
            return Prefs.displayName(this, a).compareToIgnoreCase(Prefs.displayName(this, b));
        });
    }

    private String uptime(long since) {
        long minutes = (System.currentTimeMillis() - since) / 60_000L;
        if (minutes < 1) {
            return getString(R.string.dur_just_now); // also covers a clock that jumped backwards
        }
        if (minutes < 60) {
            return getString(R.string.dur_minutes, minutes);
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return getString(R.string.dur_hours, hours, minutes % 60);
        }
        return getString(R.string.dur_days, hours / 24, hours % 24);
    }

    private Bitmap icon(String address) {
        if (!iconCache.containsKey(address)) {
            iconCache.put(address, Icons.load(this, address));
        }
        return iconCache.get(address);
    }

    /** Custom photo if the user picked one, otherwise the tinted Bluetooth glyph inset by padDp. */
    private void bindIcon(ImageView view, String address, int padDp) {
        Bitmap picture = icon(address);
        // FIT_CENTER either way: show the whole picture, letterboxed, never cropped.
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (picture != null) {
            view.setImageTintList(null);
            view.setPadding(0, 0, 0, 0);
            view.setImageBitmap(picture);
        } else {
            view.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(
                    view, com.google.android.material.R.attr.colorPrimary)));
            int pad = Math.round(padDp * getResources().getDisplayMetrics().density);
            view.setPadding(pad, pad, pad, pad);
            view.setImageResource(R.drawable.ic_bt);
        }
    }

    private void showMenu(View anchor, BluetoothDevice device) {
        String address = device.getAddress();
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.inflate(R.menu.device_menu);
        menu.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_rename) {
                showRenameDialog(device);
            } else if (id == R.id.action_picture) {
                pendingAddress = address;
                picker.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build());
            } else if (id == R.id.action_reset) {
                Prefs.setCustomName(this, address, null);
                Icons.delete(this, address);
                iconCache.remove(address);
                refresh();
            }
            return true;
        });
        menu.show();
    }

    private void showRenameDialog(BluetoothDevice device) {
        String address = device.getAddress();
        View view = getLayoutInflater().inflate(R.layout.dialog_rename, null);
        TextInputEditText input = view.findViewById(R.id.name_input);
        input.setText(Prefs.displayName(this, device));
        input.setSelectAllOnFocus(true);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.rename_device)
                .setView(view)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    // A blank name clears the override and falls back to the Bluetooth name.
                    Prefs.setCustomName(this, address,
                            input.getText() == null ? null : input.getText().toString());
                    refresh();
                })
                .show();
    }

    private class DeviceAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return devices.size();
        }

        @Override
        public Object getItem(int i) {
            return devices.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            View row = convert != null ? convert
                    : LayoutInflater.from(MainActivity.this).inflate(R.layout.item_device, parent, false);

            BluetoothDevice device = devices.get(i);
            String address = device.getAddress();

            ((TextView) row.findViewById(R.id.name)).setText(Prefs.displayName(MainActivity.this, device));
            ((TextView) row.findViewById(R.id.address)).setText(address);

            bindIcon(row.findViewById(R.id.icon), address, 8);

            MaterialSwitch sw = row.findViewById(R.id.monitor);
            // Detach before setChecked, or recycling fires the listener for the previous row.
            sw.setOnCheckedChangeListener(null);
            sw.setChecked(Prefs.monitored(MainActivity.this).contains(address));
            sw.setOnCheckedChangeListener((button, on) -> {
                Prefs.setMonitored(MainActivity.this, address, on);
                refresh(); // re-sorts to the top, and it may already be connected
            });

            ImageButton more = row.findViewById(R.id.more);
            more.setOnClickListener(v -> showMenu(v, device));

            row.setOnClickListener(v -> sw.toggle());
            return row;
        }
    }
}
