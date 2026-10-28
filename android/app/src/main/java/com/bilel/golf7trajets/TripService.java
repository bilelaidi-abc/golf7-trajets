package com.bilel.golf7trajets;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Enregistre un trajet en arrière-plan, écran éteint, sans que l'app soit ouverte.
 * Le trajet terminé est mis en file dans AutoTripStore ; l'app le récupère à sa prochaine ouverture.
 */
public class TripService extends Service implements LocationListener {
    private static final String ACTION_START = "start";
    private static final String ACTION_STOP = "stop";
    private static final String CHANNEL = "auto_trip";
    private static final int NOTIF_ID = 7101;
    private static final int NOTIF_ERROR_ID = 7102;
    /** Une coupure Bluetooth plus courte que ça (calage, Stop & Start) ne coupe pas le trajet. */
    private static final long STOP_DELAY_MS = 60_000;
    private static final float MIN_TRIP_KM = 0.3f;
    private static final int MAX_POINTS = 800;

    static volatile TripService running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable finishRunnable = this::finishTrip;
    private LocationManager locationManager;
    private final List<double[]> points = new ArrayList<>(); // lat, lon, temps, km/h
    private long startMs;
    private double distKm;
    private double maxKmh;
    private Location last;

    static void start(Context c) {
        Intent i = new Intent(c, TripService.class).setAction(ACTION_START);
        try {
            ContextCompat.startForegroundService(c, i);
        } catch (Exception e) {
            notifyError(c);
        }
    }

    static void stop(Context c) {
        TripService s = running;
        if (s != null) s.scheduleFinish();
    }

    /** Résumé du trajet en cours, ou null. */
    static JSONObject status() {
        TripService s = running;
        if (s == null) return null;
        JSONObject o = new JSONObject();
        try {
            o.put("start", s.startMs);
            o.put("distKm", s.distKm);
        } catch (JSONException ignored) {
        }
        return o;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Toujours repasser au premier plan d'abord : Android l'exige après startForegroundService.
        try {
            int type = Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION : 0;
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type);
        } catch (Exception e) {
            notifyError(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            finishTrip();
        } else if (running == this) {
            handler.removeCallbacks(finishRunnable); // reconnexion rapide : on continue le même trajet
        } else {
            beginTrip();
        }
        return START_NOT_STICKY;
    }

    private void beginTrip() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            notifyError(this);
            stopSelf();
            return;
        }
        running = this;
        startMs = System.currentTimeMillis();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 5, this, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException e) {
            notifyError(this);
            running = null;
            stopSelf();
        }
    }

    private void scheduleFinish() {
        handler.removeCallbacks(finishRunnable);
        handler.postDelayed(finishRunnable, STOP_DELAY_MS);
    }

    @Override
    public void onLocationChanged(@NonNull Location loc) {
        if (loc.getAccuracy() > 60) return;
        double kmh = loc.hasSpeed() ? loc.getSpeed() * 3.6 : -1;
        if (last != null) {
            float d = last.distanceTo(loc);
            double dt = (loc.getTime() - last.getTime()) / 1000.0;
            if (kmh < 0 && dt > 0) kmh = d / dt * 3.6;
            if (d < Math.max(4, loc.getAccuracy() * 0.5) && kmh < 5) return; // à l'arrêt : dérive GPS
            if (dt > 0 && d / dt * 3.6 > 250) return; // saut aberrant
            distKm += d / 1000.0;
        }
        kmh = Math.max(0, kmh);
        maxKmh = Math.max(maxKmh, kmh);
        points.add(new double[]{loc.getLatitude(), loc.getLongitude(), loc.getTime(), Math.round(kmh)});
        last = loc;
        if (points.size() % 15 == 0) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification());
        }
    }

    private void finishTrip() {
        handler.removeCallbacks(finishRunnable);
        if (locationManager != null) locationManager.removeUpdates(this);
        if (running == this && distKm >= MIN_TRIP_KM && !points.isEmpty()) {
            try {
                AutoTripStore.addPending(this, toJson());
            } catch (JSONException ignored) {
            }
        }
        running = null;
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private JSONObject toJson() throws JSONException {
        long end = (long) points.get(points.size() - 1)[2];
        JSONArray pts = new JSONArray();
        int step = Math.max(1, (int) Math.ceil(points.size() / (double) MAX_POINTS));
        for (int i = 0; i < points.size(); i++) {
            if (i % step != 0 && i != points.size() - 1) continue;
            double[] p = points.get(i);
            pts.put(new JSONArray().put(Math.round(p[0] * 1e6) / 1e6).put(Math.round(p[1] * 1e6) / 1e6).put((long) p[2]).put((long) p[3]));
        }
        JSONObject t = new JSONObject();
        t.put("id", "a" + startMs);
        t.put("auto", true);
        t.put("start", startMs);
        t.put("end", end);
        t.put("durMs", end - startMs);
        t.put("distKm", Math.round(distKm * 1000) / 1000.0);
        t.put("maxKmh", Math.round(maxKmh));
        t.put("pts", pts);
        t.put("cat", "perso");
        t.put("title", "");
        t.put("note", "");
        t.put("consManual", 0);
        t.put("obdFuelL", 0);
        t.put("obd", JSONObject.NULL);
        return t;
    }

    private Notification buildNotification() {
        ensureChannel(this);
        String text = running == this
                ? String.format(Locale.FRANCE, "%.1f km enregistrés", distKm)
                : "Démarrage du GPS…";
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_trip)
                .setContentTitle("Golf 7 · trajet en cours")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(this))
                .build();
    }

    private static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Trajets automatiques", NotificationManager.IMPORTANCE_LOW));
        }
    }

    /** Prévient que le trajet n'a pas pu démarrer, le plus souvent faute d'autorisation de position « Toujours ». */
    static void notifyError(Context c) {
        ensureChannel(c);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        try {
            nm.notify(NOTIF_ERROR_ID, new NotificationCompat.Builder(c, CHANNEL)
                    .setSmallIcon(R.drawable.ic_trip)
                    .setContentTitle("Trajet automatique non démarré")
                    .setContentText("Autorise la position « Toujours » pour Golf 7 Trajets.")
                    .setAutoCancel(true)
                    .setContentIntent(openApp(c))
                    .build());
        } catch (SecurityException ignored) {
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(finishRunnable);
        if (locationManager != null) locationManager.removeUpdates(this);
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Requis par les anciennes versions d'Android (avant l'API 30, ces méthodes sont abstraites).
    @Override
    public void onStatusChanged(String provider, int status, android.os.Bundle extras) {}

    @Override
    public void onProviderEnabled(@NonNull String provider) {}

    @Override
    public void onProviderDisabled(@NonNull String provider) {}
}
