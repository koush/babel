package com.koushikdutta.babel;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Created by koush on 7/7/13.
 */
public class OutgoingSmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d("BABEL", "sms");
        if (context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("account", null) == null)
            return;
        Log.d("BABEL", "processing sms");

        abortBroadcast();
        setResultCode(Activity.RESULT_CANCELED);

        intent.setClass(context, BabelService.class);
        context.startService(intent);
    }
}
