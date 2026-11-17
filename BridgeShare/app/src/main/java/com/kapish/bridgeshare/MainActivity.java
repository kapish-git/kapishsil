package com.kapish.bridgeshare;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final int REQ_PERMS = 2;
    private static final String SAMSUNG_QUICK_SHARE = "com.samsung.android.app.sharelive";

    private TextView status, error, wifiText, urlTitle, urlText, lastEvent, outgoingEmpty, incomingEmpty;
    private ImageView wifiQr, urlQr;
    private View wifiBox, urlBox;
    private Switch hotspotSwitch;
    private Button startStop;
    private LinearLayout outgoingList, incomingList;
    private SharedPreferences prefs;

    private String shownUrl, shownWifi;
    private final Runnable refresh = this::render;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);

        status = findViewById(R.id.status);
        error = findViewById(R.id.error);
        wifiBox = findViewById(R.id.wifiBox);
        wifiQr = findViewById(R.id.wifiQr);
        wifiText = findViewById(R.id.wifiText);
        urlBox = findViewById(R.id.urlBox);
        urlTitle = findViewById(R.id.urlTitle);
        urlQr = findViewById(R.id.urlQr);
        urlText = findViewById(R.id.urlText);
        lastEvent = findViewById(R.id.lastEvent);
        hotspotSwitch = findViewById(R.id.hotspotSwitch);
        startStop = findViewById(R.id.startStop);
        outgoingList = findViewById(R.id.outgoingList);
        incomingList = findViewById(R.id.incomingList);
        outgoingEmpty = findViewById(R.id.outgoingEmpty);
        incomingEmpty = findViewById(R.id.incomingEmpty);

        hotspotSwitch.setChecked(prefs.getBoolean("hotspot", false));
        hotspotSwitch.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean("hotspot", checked).apply();
            if (ShareService.STATE.running) requestStart(); // switch mode live
        });
        startStop.setOnClickListener(v -> {
            if (ShareService.STATE.running) {
                startService(new Intent(this, ShareService.class).setAction(ShareService.ACTION_STOP));
            } else {
                requestStart();
            }
        });
        findViewById(R.id.airdrop).setOnClickListener(v -> sendViaQuickShare());
        findViewById(R.id.addFiles).setOnClickListener(v -> pickFiles());
        findViewById(R.id.clearFiles).setOnClickListener(v -> FileStore.get().clearOutgoing());

        handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShareIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ShareService.addListener(refresh);
        FileStore.get().addListener(refresh);
        render();
    }

    @Override
    protected void onStop() {
        ShareService.removeListener(refresh);
        FileStore.get().removeListener(refresh);
        super.onStop();
    }

    // ---------------------------------------------------------------- start / permissions

    private void requestStart() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("askedNotif", false)) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
            prefs.edit().putBoolean("askedNotif", true).apply();
        }
        if (hotspotSwitch.isChecked()) {
            String p = Build.VERSION.SDK_INT >= 33
                    ? Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION;
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                need.add(p);
                if (Build.VERSION.SDK_INT < 33) need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }
        }
        if (need.isEmpty()) {
            startSharing();
        } else {
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERMS) return;
        for (int i = 0; i < permissions.length; i++) {
            boolean hotspotPerm = permissions[i].equals(Manifest.permission.ACCESS_FINE_LOCATION)
                    || permissions[i].equals("android.permission.NEARBY_WIFI_DEVICES");
            if (hotspotPerm && results[i] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Without this permission the app can't create its own Wi-Fi. "
                        + "Using same Wi-Fi mode instead.", Toast.LENGTH_LONG).show();
                hotspotSwitch.setChecked(false);
            }
        }
        startSharing();
    }

    private void startSharing() {
        Intent i = new Intent(this, ShareService.class)
                .setAction(ShareService.ACTION_START)
                .putExtra(ShareService.EXTRA_HOTSPOT, hotspotSwitch.isChecked());
        try {
            startForegroundService(i);
        } catch (Exception e) {
            Toast.makeText(this, "Could not start: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- files

    private void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file picker found on this phone", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        for (Uri u : uris) {
            try {
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Exception ignored) {
            }
            FileStore.get().addOutgoing(this, u);
        }
        autoStart();
    }

    @SuppressWarnings("deprecation")
    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        } else {
            return;
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "Only files can be shared", Toast.LENGTH_SHORT).show();
            return;
        }
        for (Uri u : uris) FileStore.get().addOutgoing(this, u);
        // Prevent re-adding on rotation.
        intent.setAction(Intent.ACTION_MAIN);
        autoStart();
    }

    private void autoStart() {
        if (!ShareService.STATE.running) requestStart();
    }

    // ---------------------------------------------------------------- AirDrop via Quick Share

    /**
     * Hands the files to the phone's built-in Quick Share. On phones where Quick Share has
     * "Share with Apple devices" (e.g. Galaxy S24 with One UI 8.5, Pixel 10), the iPhone
     * appears there as an AirDrop target. Apps can't speak AirDrop themselves.
     */
    private void sendViaQuickShare() {
        List<FileStore.Item> items = FileStore.get().outgoing();
        if (items.isEmpty()) {
            Toast.makeText(this, "Add files first", Toast.LENGTH_SHORT).show();
            pickFiles();
            return;
        }
        Intent send = buildSendIntent(items);
        ComponentName quickShare = findQuickShare(send);
        if (quickShare != null) {
            try {
                startActivity(new Intent(send).setComponent(quickShare));
                return;
            } catch (Exception ignored) {
                // fall through to the chooser
            }
        }
        Toast.makeText(this, "Pick Quick Share in the list", Toast.LENGTH_LONG).show();
        Intent chooser = Intent.createChooser(send, "Send with Quick Share");
        chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,
                new ComponentName[]{new ComponentName(this, MainActivity.class)});
        startActivity(chooser);
    }

    private Intent buildSendIntent(List<FileStore.Item> items) {
        Intent send;
        if (items.size() == 1) {
            send = new Intent(Intent.ACTION_SEND)
                    .setType(items.get(0).mime)
                    .putExtra(Intent.EXTRA_STREAM, items.get(0).uri);
        } else {
            ArrayList<Uri> uris = new ArrayList<>();
            String type = null;
            for (FileStore.Item i : items) {
                uris.add(i.uri);
                String group = i.mime.contains("/") ? i.mime.substring(0, i.mime.indexOf('/')) : "*";
                if (type == null) type = i.mime;
                else if (!type.equals(i.mime)) {
                    type = type.startsWith(group + "/") ? group + "/*" : "*/*";
                }
            }
            send = new Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setType(type)
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        ClipData clip = null;
        for (FileStore.Item i : items) {
            if (clip == null) clip = ClipData.newRawUri(i.name, i.uri);
            else clip.addItem(new ClipData.Item(i.uri));
        }
        send.setClipData(clip);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return send;
    }

    private ComponentName findQuickShare(Intent send) {
        List<ResolveInfo> targets = getPackageManager().queryIntentActivities(send, 0);
        // Samsung Quick Share first, then Google Quick Share (part of Play services).
        for (ResolveInfo r : targets) {
            if (SAMSUNG_QUICK_SHARE.equals(r.activityInfo.packageName)) {
                return new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
            }
        }
        for (ResolveInfo r : targets) {
            String name = r.activityInfo.name.toLowerCase(Locale.ROOT);
            if ("com.google.android.gms".equals(r.activityInfo.packageName)
                    && (name.contains("nearby.sharing") || name.contains("quickshare"))) {
                return new ComponentName(r.activityInfo.packageName, r.activityInfo.name);
            }
        }
        return null;
    }

    private void openReceived(FileStore.Item item) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Saved in " + item.location, Toast.LENGTH_LONG).show();
            return;
        }
        Intent v = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(item.uri, item.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(v);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app can open this file. It is in " + item.location, Toast.LENGTH_LONG).show();
        }
    }

    // ---------------------------------------------------------------- rendering

    private void render() {
        ShareService.State s = ShareService.STATE;
        startStop.setText(s.running ? "Stop sharing" : "Start sharing");

        if (!s.running) {
            status.setText("Not sharing");
        } else if (s.hotspotMode && s.hotspotStarting) {
            status.setText("Creating Wi-Fi…");
        } else if (s.url == null) {
            status.setText("Waiting for Wi-Fi…");
        } else {
            status.setText("Ready for iPhone");
        }

        error.setVisibility(s.error != null ? View.VISIBLE : View.GONE);
        error.setText(s.error);

        // Step 1: hotspot QR
        boolean showWifi = s.running && s.hotspotSsid != null;
        wifiBox.setVisibility(showWifi ? View.VISIBLE : View.GONE);
        if (showWifi) {
            String payload = QrUtil.wifiPayload(s.hotspotSsid, s.hotspotPass == null ? "" : s.hotspotPass);
            if (!payload.equals(shownWifi)) {
                shownWifi = payload;
                wifiQr.setImageBitmap(QrUtil.make(payload, dp(220)));
            }
            wifiText.setText("Wi-Fi: " + s.hotspotSsid + "\nPassword: " + s.hotspotPass);
        } else {
            shownWifi = null;
        }

        // Step 2: page QR
        boolean showUrl = s.running && s.url != null;
        urlBox.setVisibility(showUrl ? View.VISIBLE : View.GONE);
        urlTitle.setText(showWifi ? "Step 2 · Then scan this code to open BridgeShare in Safari"
                : "On iPhone, open Camera and scan this code");
        if (showUrl && !s.url.equals(shownUrl)) {
            shownUrl = s.url;
            Bitmap b = QrUtil.make(s.url, dp(220));
            urlQr.setImageBitmap(b);
            urlText.setText(s.url);
        }

        lastEvent.setVisibility(s.running && s.lastEvent != null ? View.VISIBLE : View.GONE);
        lastEvent.setText(s.lastEvent);

        // Outgoing list
        outgoingList.removeAllViews();
        List<FileStore.Item> out = FileStore.get().outgoing();
        outgoingEmpty.setVisibility(out.isEmpty() ? View.VISIBLE : View.GONE);
        for (FileStore.Item item : out) {
            outgoingList.addView(row(item, "✕", () -> FileStore.get().removeOutgoing(item.id), null));
        }

        // Incoming list
        incomingList.removeAllViews();
        List<FileStore.Item> in = FileStore.get().incoming();
        incomingEmpty.setVisibility(in.isEmpty() ? View.VISIBLE : View.GONE);
        for (FileStore.Item item : in) {
            incomingList.addView(row(item, "Open", () -> openReceived(item), () -> openReceived(item)));
        }
    }

    private View row(FileStore.Item item, String actionLabel, Runnable action, Runnable onTap) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(6), 0, dp(6));

        TextView t = new TextView(this);
        String size = item.size >= 0 ? "  ·  " + Formatter.formatShortFileSize(this, item.size) : "";
        t.setText(item.name + size);
        t.setTextColor(0xFF1A1D21);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setMaxLines(2);
        r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (onTap != null) t.setOnClickListener(v -> onTap.run());

        Button b = new Button(this);
        b.setText(actionLabel);
        b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        r.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
