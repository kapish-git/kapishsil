package com.kapish.bridgeshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Foreground service: keeps the web server (and optional hotspot) alive while the screen is off. */
public class ShareService extends Service {

    public static final String ACTION_START = "com.kapish.bridgeshare.START";
    public static final String ACTION_STOP = "com.kapish.bridgeshare.STOP";
    public static final String EXTRA_HOTSPOT = "hotspot";

    private static final String CHANNEL = "bridgeshare";
    private static final int NOTIF_ID = 1;

    /** Snapshot of what the UI should show. Only touched on the main thread. */
    public static final class State {
        public boolean running;
        public boolean hotspotMode;
        public boolean hotspotStarting;
        public String hotspotSsid;
        public String hotspotPass;
        public String url;
        public String error;
        public String lastEvent;
    }

    public static final State STATE = new State();
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    public static void addListener(Runnable r) {
        LISTENERS.add(r);
    }

    public static void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }

    private static void notifyListeners() {
        for (Runnable r : LISTENERS) r.run();
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebServer server;
    private String token;
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refreshUrl();
            main.postDelayed(this, 4000);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            shutdown();
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }
        boolean hotspot = intent != null && intent.getBooleanExtra(EXTRA_HOTSPOT, false);
        startForegroundCompat(buildNotification("Starting…"));

        STATE.error = null;
        if (server == null) {
            token = randomToken();
            server = new WebServer(this, token, deviceName(), msg -> main.post(() -> {
                STATE.lastEvent = msg;
                updateNotification(msg);
                notifyListeners();
            }));
            try {
                server.start();
            } catch (Exception e) {
                server = null;
                STATE.running = false;
                STATE.error = "Could not start the server: " + e.getMessage();
                notifyListeners();
                stopForegroundCompat();
                stopSelf();
                return START_NOT_STICKY;
            }
            acquireLocks();
        }
        STATE.running = true;

        if (hotspot && reservation == null) {
            startHotspot();
        } else if (!hotspot && reservation != null) {
            stopHotspot();
        }
        STATE.hotspotMode = hotspot;

        main.removeCallbacks(refresher);
        main.post(refresher);
        notifyListeners();
        return START_NOT_STICKY;
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        // Android 15 limits dataSync services to 6 hours per day.
        shutdown();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        main.removeCallbacks(refresher);
        if (server != null) {
            server.stop();
            server = null;
        }
        stopHotspot();
        releaseLocks();
        STATE.running = false;
        STATE.url = null;
        STATE.hotspotMode = false;
        notifyListeners();
    }

    // ---------------------------------------------------------------- hotspot

    @SuppressWarnings("deprecation")
    private void startHotspot() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        STATE.hotspotStarting = true;
        try {
            wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation res) {
                    reservation = res;
                    STATE.hotspotStarting = false;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        SoftApConfiguration c = res.getSoftApConfiguration();
                        STATE.hotspotSsid = c.getSsid();
                        STATE.hotspotPass = c.getPassphrase();
                    } else {
                        WifiConfiguration c = res.getWifiConfiguration();
                        if (c != null) {
                            STATE.hotspotSsid = c.SSID;
                            STATE.hotspotPass = c.preSharedKey;
                        }
                    }
                    if (STATE.hotspotSsid != null && STATE.hotspotSsid.startsWith("\"")
                            && STATE.hotspotSsid.endsWith("\"") && STATE.hotspotSsid.length() > 1) {
                        STATE.hotspotSsid = STATE.hotspotSsid.substring(1, STATE.hotspotSsid.length() - 1);
                    }
                    refreshUrl();
                    notifyListeners();
                }

                @Override
                public void onStopped() {
                    reservation = null;
                    STATE.hotspotSsid = null;
                    STATE.hotspotPass = null;
                    notifyListeners();
                }

                @Override
                public void onFailed(int reason) {
                    STATE.hotspotStarting = false;
                    STATE.error = hotspotError(reason);
                    notifyListeners();
                }
            }, main);
        } catch (SecurityException e) {
            STATE.hotspotStarting = false;
            STATE.error = "Hotspot needs the Nearby devices / Location permission.";
        } catch (IllegalStateException e) {
            STATE.hotspotStarting = false;
            STATE.error = "A hotspot is already running. Turn off the system hotspot and try again.";
        }
    }

    private static String hotspotError(int reason) {
        switch (reason) {
            case WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED:
                return "This phone does not allow hotspots (carrier or admin restriction). Use same Wi-Fi mode.";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE:
                return "Hotspot can't start right now. Turn off the system hotspot and turn on Location, then try again.";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL:
                return "No free Wi-Fi channel for the hotspot. Try again or use same Wi-Fi mode.";
            default:
                return "Hotspot failed to start (code " + reason + "). Make sure Location is on, or use same Wi-Fi mode.";
        }
    }

    private void stopHotspot() {
        if (reservation != null) {
            try {
                reservation.close();
            } catch (Exception ignored) {
            }
            reservation = null;
        }
        STATE.hotspotSsid = null;
        STATE.hotspotPass = null;
        STATE.hotspotStarting = false;
    }

    // ---------------------------------------------------------------- url / notification

    private void refreshUrl() {
        if (server == null) return;
        String ip = NetUtil.pickAddress(reservation != null);
        String url = ip == null ? null : "http://" + ip + ":" + server.port() + "/?k=" + token;
        boolean changed = url == null ? STATE.url != null : !url.equals(STATE.url);
        if (changed) {
            STATE.url = url;
            updateNotification(url != null ? "Open on iPhone: " + url : "Waiting for Wi-Fi…");
            notifyListeners();
        }
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Sharing",
                    NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ShareService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("BridgeShare is sharing")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .build();
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    // ---------------------------------------------------------------- misc

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
        wifiLock = wm.createWifiLock(mode, "BridgeShare:wifi");
        wifiLock.acquire();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BridgeShare:cpu");
        wakeLock.acquire(6 * 60 * 60 * 1000L);
    }

    private void releaseLocks() {
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wifiLock = null;
        wakeLock = null;
    }

    private String deviceName() {
        String n = Settings.Global.getString(getContentResolver(), "device_name");
        if (n == null || n.isEmpty()) n = Build.MANUFACTURER + " " + Build.MODEL;
        return n;
    }

    private static String randomToken() {
        String abc = "abcdefghjkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) b.append(abc.charAt(r.nextInt(abc.length())));
        return b.toString();
    }
}
