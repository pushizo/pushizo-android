package com.amazon.device.messaging;

import android.app.IntentService;
import android.content.Intent;

/** Derleme saplaması: eski (Android 8 öncesi / eski ADM) mesaj işleyici servisi. */
@SuppressWarnings("deprecation")
public abstract class ADMMessageHandlerBase extends IntentService {
    public ADMMessageHandlerBase(String className) {
        super(className);
        throw new RuntimeException("Stub");
    }

    protected abstract void onMessage(Intent intent);

    protected abstract void onRegistrationError(String errorId);

    protected abstract void onRegistered(String registrationId);

    protected abstract void onUnregistered(String registrationId);

    @Override
    protected final void onHandleIntent(Intent intent) {
        throw new RuntimeException("Stub");
    }
}
