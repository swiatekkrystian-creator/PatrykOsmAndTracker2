package org.owntracks.android.patryktracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.content.SharedPreferences;

import androidx.core.app.ServiceCompat;
import androidx.preference.PreferenceManager;

import net.osmand.aidlapi.IOsmAndAidlInterface;
import net.osmand.aidlapi.map.ALatLon;
import net.osmand.aidlapi.mapmarker.AMapMarker;
import net.osmand.aidlapi.mapmarker.AddMapMarkerParams;
import net.osmand.aidlapi.mapmarker.RemoveMapMarkersParams;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TrackerService extends Service {
    private static final long PERIOD_MILLIS = 5L * 60L * 1000L;
    private static final int OSMAND_BIND_TIMEOUT_SECONDS = 20;
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
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
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
        return builder.setContentTitle("Patryk → OsmAnd").setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation).setOngoing(true).build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Patryk → OsmAnd",
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(service);
        else context.startService(service);
    }

    public static String fetchAndSendOnce(Context context) {
        HttpURLConnection connection = null;
        try {
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
            String configuredUrl = preferences.getString("url", "");
            if (configuredUrl == null || configuredUrl.isBlank()) return "Brak URL w ustawieniach";

            String requestUrl = buildFetchUrl(configuredUrl);
            connection = (HttpURLConnection) new URL(requestUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);

            int code = connection.getResponseCode();
            if (code != 200) return "HTTP " + code;

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
            }

            JSONArray all = new JSONArray(body.toString());
            Map<String, MarkerData> markersByTid = new LinkedHashMap<>();
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
                String title = name + " " + (batt >= 0 ? "🔋 " + batt + "%" : "🔋 --") + " " + time;
                markersByTid.put(tid, new MarkerData(tid, title, lat, lon));
            }

            OsmAndMarker.replaceAll(context, new ArrayList<>(markersByTid.values()));
            return "Pobrano " + all.length() + ", ustawiono flagi " + markersByTid.size();
        } catch (Exception e) {
            return "Błąd: " + e.getClass().getSimpleName() + " " + e.getMessage();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String buildFetchUrl(String baseUrl) {
        if (baseUrl.contains("dod=1")) return baseUrl.replace("dod=1", "spr=1");
        if (baseUrl.contains("spr=1")) return baseUrl;
        return baseUrl + (baseUrl.contains("?") ? "&" : "?") + "spr=1";
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private static final class MarkerData {
        final String tid;
        final String title;
        final double lat;
        final double lon;

        MarkerData(String tid, String title, double lat, double lon) {
            this.tid = tid;
            this.title = title;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private static class OsmAndMarker {
        // All OsmAnd AIDL transactions are serialized on one worker. This is important because
        // bindService() is asynchronous and refreshes can arrive from both OwnTracks uploads
        // and the 5-minute fallback at nearly the same time.
        private static final ExecutorService OSMAND_EXECUTOR = Executors.newSingleThreadExecutor();

        static void replaceAll(Context context, List<MarkerData> markers) {
            OSMAND_EXECUTOR.execute(() -> replaceAllBlocking(context, markers));
        }

        private static void replaceAllBlocking(Context context, List<MarkerData> markers) {
            bindAndRun(context, api -> {
                // The server response is authoritative. Remove every active OsmAnd marker first,
                // then add exactly the markers returned by the current request. This also clears
                // markers left behind by older versions or by a previous URL/user list.
                api.removeAllActiveMapMarkers(new RemoveMapMarkersParams());

                for (MarkerData marker : markers) {
                    AMapMarker next = new AMapMarker(
                            new ALatLon(marker.lat, marker.lon), marker.title);
                    api.addMapMarker(new AddMapMarkerParams(next));
                }
            });
        }

        private interface ApiAction {
            void run(IOsmAndAidlInterface api) throws Exception;
        }

        private static void bindAndRun(Context context, ApiAction action) {
            String[] packages = {"net.osmand.plus", "net.osmand", "net.osmand.dev"};
            String pkg = null;
            for (String candidate : packages) {
                try {
                    context.getPackageManager().getPackageInfo(candidate, 0);
                    pkg = candidate;
                    break;
                } catch (Exception ignored) { }
            }
            if (pkg == null) return;

            Intent intent = new Intent("net.osmand.aidl.OsmandAidlServiceV2");
            intent.setPackage(pkg);
            CountDownLatch connected = new CountDownLatch(1);
            ServiceConnection connection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                    try {
                        action.run(IOsmAndAidlInterface.Stub.asInterface(binder));
                    } catch (Exception ignored) {
                    } finally {
                        connected.countDown();
                        try { context.unbindService(this); } catch (Exception ignored) { }
                    }
                }

                @Override public void onServiceDisconnected(ComponentName name) {
                    connected.countDown();
                }
            };

            try {
                if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) return;
                connected.await(OSMAND_BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                try { context.unbindService(connection); } catch (Exception ignored2) { }
            }
        }
    }
}
