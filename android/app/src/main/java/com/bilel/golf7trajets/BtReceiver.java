package com.bilel.golf7trajets;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Démarre le trajet quand le téléphone se connecte au Bluetooth de la voiture, l'arrête à la déconnexion.
 * Android réveille l'app pour ces événements même quand elle est fermée.
 */
public class BtReceiver extends BroadcastReceiver {
    private static final String A2DP_STATE = "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";
    private static final String HEADSET_STATE = "android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED";

    @Override
    public void onReceive(Context context, Intent intent) {
        String car = AutoTripStore.carAddress(context);
        if (car == null) return;
        BluetoothDevice device = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
                : intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (device == null || !car.equalsIgnoreCase(device.getAddress())) return;

        String action = String.valueOf(intent.getAction());
        boolean connected;
        switch (action) {
            case BluetoothDevice.ACTION_ACL_CONNECTED:
                connected = true;
                break;
            case BluetoothDevice.ACTION_ACL_DISCONNECTED:
                connected = false;
                break;
            case A2DP_STATE:
            case HEADSET_STATE: {
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                if (state == BluetoothProfile.STATE_CONNECTED) connected = true;
                else if (state == BluetoothProfile.STATE_DISCONNECTED) connected = false;
                else return;
                break;
            }
            default:
                return;
        }
        if (connected) TripService.start(context, true);
        else TripService.stopSoon();
    }
}
