package org.owntracks.android.patryktracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import androidx.core.app.ServiceCompat;

import net.osmand.aidlapi.IOsmAndAidlInterface;
import net.osmand.aidlapi.map.ALatLon;
import net.osmand.aidlapi.mapmarker.AMapMarker;
import net.osmand.aidlapi.mapmarker.AddMapMarkerParams;
import net.osmand.aidlapi.mapmarker.UpdateMapMarkerParams;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import org.owntracks.android.BuildConfig;

public class TrackerService extends Service {
    private static final long PERIOD_MILLIS = 5L * 60L * 1000L;
    private static final String CHANNEL = "patryk_tracker";
    private static final int NOTIFICATION_ID = 1001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            new Thread(() -> {
                String result = fetchAndSendOnce(TrackerService.this);
                handler.post(() -> {
                    updateNotification(result);
                    handler.postDelayed(refreshRunnable, PERIOD_MILLIS);
                });
            }, "PatrykTrackerRefresh").start();
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        Notification notification = buildNotification("Aktywne — odświeżanie co 5 min");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        handler.removeCallbacks(refreshRunnable);
        refreshRunnable.run();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(refreshRunnable);
        super.onDestroy();
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("Patryk → OsmAnd")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL,
                    "Patryk → OsmAnd",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    public static void cancelSchedule(Context context) {
        context.stopService(new Intent(context, TrackerService.class));
    }

    public static void scheduleNext(Context context, long delayMillis) {
        Intent service = new Intent(context, TrackerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(service);
        } else {
            context.startService(service);
        }
    }

    public static String fetchAndSendOnce(Context context) {
        HttpURLConnection connection = null;
        try {
            String requestUrl = buildFetchUrl(BuildConfig.PATRYK_TRACKER_URL);
            connection = (HttpURLConnection) new URL(requestUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);

            int code = connection.getResponseCode();
            if (code != 200) return "HTTP " + code;

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }

            JSONArray all = new JSONArray(body.toString());
            int sent = 0;
            for (int i = 0; i < all.length(); i++) {
                JSONObject object = all.getJSONObject(i);
                if (!object.has("lat") || !object.has("lon") || !object.has("tid")) continue;

                String tid = object.optString("tid");
                if (tid.isEmpty()) continue;

                double lat = object.getDouble("lat");
                double lon = object.getDouble("lon");
                String name = object.optString("name", tid);
                int batt = object.optInt("batt", -1);
                long tst = object.optLong("tst", 0);
                String time = tst > 0
                        ? new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(tst * 1000L))
                        : "--:--";
                String title = name + " "
                        + (batt >= 0 ? "🔋 " + batt + "%" : "🔋 --")
                        + " " + time;

                OsmAndMarker.set(context, title, lat, lon);
                sent++;
            }
            return "Pobrano " + all.length() + ", wysłano do OsmAnda " + sent;
        } catch (Exception e) {
            return "Błąd: " + e.getClass().getSimpleName() + " " + e.getMessage();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String buildFetchUrl(String baseUrl) {
        String requestUrl = baseUrl.replace("dod=1&", "");
        return requestUrl + (requestUrl.contains("?") ? "&" : "?") + "spr=1";
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static class OsmAndMarker {
        static void set(Context context, String title, double lat, double lon) {
            bind(context, api -> {
                AMapMarker next = new AMapMarker(new ALatLon(lat, lon), title);
                AMapMarker previous = new AMapMarker(new ALatLon(0, 0), title);
                boolean updated = api.updateMapMarker(
                        new UpdateMapMarkerParams(previous, next, true));
                if (!updated) api.addMapMarker(new AddMapMarkerParams(next));
            });
        }

        private interface ApiAction {
            void run(IOsmAndAidlInterface api) throws RemoteException;
        }

        private static void bind(Context context, ApiAction action) {
            String[] packages = {"net.osmand.plus", "net.osmand", "net.osmand.dev"};
            String pkg = null;
            for (String candidate : packages) {
                try {
                    context.getPackageManager().getPackageInfo(candidate, 0);
                    pkg = candidate;
                    break;
                } catch (Exception ignored) {
                }
            }
            if (pkg == null) return;

            Intent intent = new Intent("net.osmand.aidl.OsmandAidlServiceV2");
            intent.setPackage(pkg);
            ServiceConnection connection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                    try {
                        action.run(IOsmAndAidlInterface.Stub.asInterface(binder));
                    } catch (Exception ignored) {
                    } finally {
                        try { context.unbindService(this); } catch (Exception ignored) { }
                    }
                }

                @Override public void onServiceDisconnected(ComponentName name) { }
            };
            try {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            } catch (Exception ignored) {
            }
        }
    }
}
