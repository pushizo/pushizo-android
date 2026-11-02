package com.amazon.device.messaging;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.Intent;

/** Derleme saplaması: Android 8+ için JobScheduler tabanlı mesaj işleyici (ADM 1.1.0 ve sonrası). */
public abstract class ADMMessageHandlerJobBase extends JobService {
    public ADMMessageHandlerJobBase() {
        throw new RuntimeException("Stub");
    }

    protected abstract void onMessage(Context context, Intent intent);

    protected abstract void onRegistrationError(Context context, String errorId);

    protected abstract void onRegistered(Context context, String registrationId);

    protected abstract void onUnregistered(Context context, String registrationId);

    @Override
    public final boolean onStartJob(JobParameters params) {
        throw new RuntimeException("Stub");
    }

    @Override
    public final boolean onStopJob(JobParameters params) {
        throw new RuntimeException("Stub");
    }
}
