package com.bilel.golf7trajets;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Démarre le trajet quand le téléphone se connecte au Bluetooth de la voiture, l'arrête à la déconnexion. */
public class BtReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String car = AutoTripStore.carAddress(context);
        if (car == null) return;
        BluetoothDevice device = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
                : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null || !car.equalsIgnoreCase(device.getAddress())) return;

        String action = intent.getAction();
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
            TripService.start(context);
        } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            TripService.stop(context);
        }
    }
}
