package com.bilel.golf7trajets;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Réglages du démarrage automatique et trajets enregistrés en attendant que l'app les récupère. */
final class AutoTripStore {
    private static final String PREFS = "auto_trip";
    private static final String CAR_ADDRESS = "car_address";
    private static final String CAR_NAME = "car_name";
    private static final String PENDING = "pending_trips";

    private AutoTripStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String carAddress(Context c) { return prefs(c).getString(CAR_ADDRESS, null); }

    static String carName(Context c) { return prefs(c).getString(CAR_NAME, null); }

    static void setCar(Context c, String address, String name) {
        prefs(c).edit().putString(CAR_ADDRESS, address).putString(CAR_NAME, name).apply();
    }

    static void clearCar(Context c) {
        prefs(c).edit().remove(CAR_ADDRESS).remove(CAR_NAME).apply();
    }

    static synchronized void addPending(Context c, JSONObject trip) {
        JSONArray arr = readPending(c);
        arr.put(trip);
        prefs(c).edit().putString(PENDING, arr.toString()).commit();
    }

    /** Rend les trajets en attente et vide la file. */
    static synchronized JSONArray takePending(Context c) {
        JSONArray arr = readPending(c);
        prefs(c).edit().remove(PENDING).commit();
        return arr;
    }

    private static JSONArray readPending(Context c) {
        try {
            return new JSONArray(prefs(c).getString(PENDING, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }
}
