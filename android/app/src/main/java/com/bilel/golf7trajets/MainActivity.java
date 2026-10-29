package com.bilel.golf7trajets;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(AutoTripPlugin.class);
        registerPlugin(DashOcrPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
