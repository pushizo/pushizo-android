package com.amazon.device.messaging;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Derleme saplaması: ADM yayın alıcısı; gelen niyeti işleyici servise (eski) veya işe (Android 8+) aktarır. */
public class ADMMessageReceiver extends BroadcastReceiver {
    public ADMMessageReceiver(Class<?> serviceClass) {
        throw new RuntimeException("Stub");
    }

    public void registerJobServiceClass(Class<?> jobServiceClass, int jobId) {
        throw new RuntimeException("Stub");
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        throw new RuntimeException("Stub");
    }
}
