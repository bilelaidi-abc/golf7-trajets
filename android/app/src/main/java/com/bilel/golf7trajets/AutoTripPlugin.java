package com.bilel.golf7trajets;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

/** Pont entre l'interface web et le démarrage automatique (Bluetooth de la voiture + TripService). */
@CapacitorPlugin(name = "AutoTrip")
public class AutoTripPlugin extends Plugin {

    private boolean granted(String perm) {
        return ContextCompat.checkSelfPermission(getContext(), perm) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean bluetoothGranted() {
        return Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT);
    }

    private boolean backgroundGranted() {
        return Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
    }

    @PluginMethod
    public void getStatus(PluginCall call) {
        JSObject r = new JSObject();
        r.put("carAddress", AutoTripStore.carAddress(getContext()));
        r.put("carName", AutoTripStore.carName(getContext()));
        r.put("bluetooth", bluetoothGranted());
        r.put("location", granted(Manifest.permission.ACCESS_FINE_LOCATION));
        r.put("background", backgroundGranted());
        r.put("notifications", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS));
        JSONObject cur = TripService.status();
        r.put("current", cur != null ? cur : JSONObject.NULL);
        call.resolve(r);
    }

    /** Demande les autorisations une par une ; l'interface rappelle cette méthode jusqu'à ce que tout soit accordé. */
    @PluginMethod
    public void requestNext(PluginCall call) {
        String next = null;
        if (Build.VERSION.SDK_INT >= 31 && !granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            next = Manifest.permission.BLUETOOTH_CONNECT;
        } else if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            next = Manifest.permission.ACCESS_FINE_LOCATION;
        } else if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            next = Manifest.permission.POST_NOTIFICATIONS;
        }
        if (next != null) {
            String[] perms = Manifest.permission.ACCESS_FINE_LOCATION.equals(next)
                    ? new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}
                    : new String[]{next};
            getActivity().requestPermissions(perms, 7201);
            JSObject r = new JSObject();
            r.put("asked", next);
            call.resolve(r);
            return;
        }
        if (!backgroundGranted()) {
            // Depuis Android 11, « Toujours autoriser » ne se choisit que dans les réglages de l'app.
            openAppSettings();
            JSObject r = new JSObject();
            r.put("asked", "background");
            call.resolve(r);
            return;
        }
        JSObject r = new JSObject();
        r.put("asked", (String) null);
        call.resolve(r);
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        openAppSettings();
        call.resolve();
    }

    private void openAppSettings() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getContext().getPackageName(), null));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
    }

    @SuppressLint("MissingPermission")
    @PluginMethod
    public void listPairedDevices(PluginCall call) {
        if (!bluetoothGranted()) {
            call.reject("Autorise d'abord l'accès au Bluetooth.", "NO_BLUETOOTH_PERMISSION");
            return;
        }
        BluetoothManager bm = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
        if (adapter == null) {
            call.reject("Ce téléphone n'a pas de Bluetooth.", "NO_BLUETOOTH");
            return;
        }
        JSArray list = new JSArray();
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            JSObject o = new JSObject();
            o.put("address", d.getAddress());
            o.put("name", d.getName() != null ? d.getName() : d.getAddress());
            list.put(o);
        }
        JSObject r = new JSObject();
        r.put("devices", list);
        call.resolve(r);
    }

    @PluginMethod
    public void setCar(PluginCall call) {
        String address = call.getString("address");
        if (address == null) {
            AutoTripStore.clearCar(getContext());
        } else {
            AutoTripStore.setCar(getContext(), address, call.getString("name", address));
        }
        call.resolve();
    }

    @PluginMethod
    public void takePendingTrips(PluginCall call) {
        JSONArray arr = AutoTripStore.takePending(getContext());
        JSObject r = new JSObject();
        r.put("trips", arr);
        call.resolve(r);
    }
}
