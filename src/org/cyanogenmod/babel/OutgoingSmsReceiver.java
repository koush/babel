package org.cyanogenmod.babel;

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
        if (context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("account", null) == null)
            return;

        abortBroadcast();
        setResultCode(Activity.RESULT_CANCELED);

        intent.setClass(context, BabelService.class);
        context.startService(intent);
    }
}
