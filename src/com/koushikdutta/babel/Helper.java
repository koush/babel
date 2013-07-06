package com.koushikdutta.babel;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.ContactsContract;
import android.util.Base64;

import java.security.MessageDigest;
import java.util.HashSet;

/**
 * Created by koush on 6/23/13.
 */
public class Helper {
    static PowerManager.WakeLock wakeLock;
    static WifiManager.WifiLock wifiLock;
    public static void acquireTemporaryWakelocks(Context context, long timeout) {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "PushSMS");
        }
        wakeLock.acquire(timeout);

        if (wifiLock == null) {
            WifiManager wm = (WifiManager)context.getSystemService(Context.WIFI_SERVICE);
            wifiLock = wm.createWifiLock("PushSMS");
        }


        wifiLock.acquire();
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                wifiLock.release();
            }
        }, timeout);
    }
}
