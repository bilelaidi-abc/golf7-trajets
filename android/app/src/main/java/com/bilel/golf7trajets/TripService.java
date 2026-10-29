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
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

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
    private static final String EXTRA_AUTO = "auto";
    private static final String CHANNEL = "auto_trip";
    private static final int NOTIF_ID = 7101;
    private static final int NOTIF_ERROR_ID = 7102;
    /** Délai entre la coupure du Bluetooth (voiture arrêtée) et la fin du trajet. */
    private static final long STOP_DELAY_MS = 5_000;
    private static final int MAX_POINTS = 800;
    /** Masse de la Golf 7 avec conducteur, en kg (énergie dépensée à chaque accélération). */
    private static final double MASS_KG = 1430;
    /** Hausse de vitesse (m/s) à partir de laquelle on compte une accélération, pour ignorer le bruit GPS. */
    private static final double RISE_MS = 3;

    static volatile TripService running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable finishRunnable = this::finishTrip;
    private LocationManager locationManager;
    private final List<double[]> points = new ArrayList<>(); // lat, lon, temps, km/h
    private boolean auto;
    private long startMs;
    private double distKm;
    private double maxKmh;
    private double lastKmh;
    private Location last;
    // Modèle de consommation : temps au ralenti, carburant de croisière (base essence), énergie des accélérations
    private double movingS;
    private double cruiseL;
    private double accelJ;
    private double ema = -1, riseLo, riseHi;

    static void start(Context c, boolean auto) {
        Intent i = new Intent(c, TripService.class).setAction(ACTION_START).putExtra(EXTRA_AUTO, auto);
        try {
            ContextCompat.startForegroundService(c, i);
        } catch (Exception e) {
            notifyError(c);
        }
    }

    /** Fin de trajet différée : une reconnexion rapide du Bluetooth continue le même trajet. */
    static void stopSoon() {
        TripService s = running;
        if (s != null) s.scheduleFinish();
    }

    static void stopNow() {
        TripService s = running;
        if (s != null) s.finishTrip();
    }

    /** Résumé du trajet en cours, ou null. */
    static JSONObject status() {
        TripService s = running;
        if (s == null) return null;
        JSONObject o = new JSONObject();
        try {
            o.put("start", s.startMs);
            o.put("distKm", s.distKm);
            o.put("kmh", Math.round(s.lastKmh));
            o.put("maxKmh", Math.round(s.maxKmh));
            o.put("auto", s.auto);
            o.put("gps", s.last != null);
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
        if (running == this) {
            handler.removeCallbacks(finishRunnable); // reconnexion rapide : on continue le même trajet
        } else {
            beginTrip(intent != null && intent.getBooleanExtra(EXTRA_AUTO, false));
        }
        return START_NOT_STICKY;
    }

    private void beginTrip(boolean isAuto) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            notifyError(this);
            stopSelf();
            return;
        }
        running = this;
        auto = isAuto;
        startMs = System.currentTimeMillis();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException e) {
            notifyError(this);
            running = null;
            stopSelf();
            return;
        }
        vibrate(this, new long[]{0, 600});
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
            double dt = (loc.getTime() - last.getTime()) / 1000.0;
            if (dt <= 0) return;
            float d = last.distanceTo(loc);
            if (kmh < 0) kmh = d / dt * 3.6;
            if (d / dt * 3.6 > 250) return; // saut aberrant
            trackSpeed(Math.max(0, kmh) / 3.6);
            if (d < Math.max(4, loc.getAccuracy() * 0.5) && kmh < 5) { // à l'arrêt : on ignore la dérive GPS
                lastKmh = Math.max(0, kmh);
                return;
            }
            double v = d / dt;
            if (v >= 1.4) { // en mouvement ; sinon le temps compte comme ralenti
                movingS += dt;
                cruiseL += d / 1000.0 * cruiseL100(v * 3.6) / 100.0;
            }
            distKm += d / 1000.0;
        }
        kmh = Math.max(0, kmh);
        lastKmh = kmh;
        maxKmh = Math.max(maxKmh, kmh);
        points.add(new double[]{loc.getLatitude(), loc.getLongitude(), loc.getTime(), Math.round(kmh)});
        last = loc;
        if (points.size() % 10 == 0) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification());
        }
    }

    /** Consommation de croisière d'une Golf 7 essence à vitesse stable (L/100 km). Même courbe que dans l'app. */
    static double cruiseL100(double kmh) {
        double s = Math.max(8, kmh);
        return 2.2 + 70 / s + 0.00028 * s * s;
    }

    /** Compte l'énergie de chaque accélération réelle (montée de vitesse de plus de 3 m/s, lissée). */
    private void trackSpeed(double ms) {
        ema = ema < 0 ? ms : ema * 0.6 + ms * 0.4;
        if (ema > riseHi) riseHi = ema;
        if (riseHi - ema > RISE_MS) {
            closeRise();
            riseLo = riseHi = ema;
        } else if (ema < riseLo) {
            riseLo = riseHi = ema;
        }
    }

    private void closeRise() {
        if (riseHi - riseLo > RISE_MS) accelJ += 0.5 * MASS_KG * (riseHi * riseHi - riseLo * riseLo);
    }

    private void finishTrip() {
        handler.removeCallbacks(finishRunnable);
        if (locationManager != null) locationManager.removeUpdates(this);
        if (running == this) {
            closeRise();
            try {
                AutoTripStore.addPending(this, toJson());
            } catch (JSONException ignored) {
            }
            vibrate(this, new long[]{0, 250, 200, 250});
        }
        running = null;
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private JSONObject toJson() throws JSONException {
        long end = System.currentTimeMillis();
        JSONArray pts = new JSONArray();
        int step = Math.max(1, (int) Math.ceil(points.size() / (double) MAX_POINTS));
        for (int i = 0; i < points.size(); i++) {
            if (i % step != 0 && i != points.size() - 1) continue;
            double[] p = points.get(i);
            pts.put(new JSONArray().put(Math.round(p[0] * 1e6) / 1e6).put(Math.round(p[1] * 1e6) / 1e6).put((long) p[2]).put((long) p[3]));
        }
        JSONObject model = new JSONObject();
        model.put("idleS", Math.round(Math.max(0, (end - startMs) / 1000.0 - movingS)));
        model.put("cruiseL", Math.round(cruiseL * 10000) / 10000.0);
        model.put("accelKJ", Math.round(accelJ / 1000));
        JSONObject t = new JSONObject();
        t.put("id", (auto ? "a" : "t") + startMs);
        t.put("auto", auto);
        t.put("start", startMs);
        t.put("end", end);
        t.put("durMs", end - startMs);
        t.put("distKm", Math.round(distKm * 1000) / 1000.0);
        t.put("maxKmh", Math.round(maxKmh));
        t.put("pts", pts);
        t.put("model", model);
        t.put("cat", "perso");
        t.put("title", "");
        t.put("note", "");
        t.put("consManual", 0);
        t.put("obdFuelL", 0);
        t.put("obd", JSONObject.NULL);
        return t;
    }

    static void vibrate(Context c, long[] pattern) {
        Vibrator v;
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = c.getSystemService(VibratorManager.class);
            v = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            v = (Vibrator) c.getSystemService(VIBRATOR_SERVICE);
        }
        if (v == null || !v.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        else v.vibrate(pattern, -1);
    }

    private Notification buildNotification() {
        ensureChannel(this);
        String text = running != this ? "Démarrage du GPS…"
                : last == null ? "Recherche du signal GPS…"
                : String.format(Locale.FRANCE, "%.1f km · %d km/h", distKm, Math.round(lastKmh));
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_trip)
                .setContentTitle(auto ? "Golf 7 · trajet automatique" : "Golf 7 · trajet en cours")
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
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Trajets", NotificationManager.IMPORTANCE_LOW));
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
                    .setContentTitle("Trajet non démarré")
                    .setContentText("Ouvre l'app : il manque une autorisation (position « Toujours »).")
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
