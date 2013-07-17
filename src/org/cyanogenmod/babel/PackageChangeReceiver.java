package org.cyanogenmod.babel;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/**
 * Created by koush on 7/17/13.
 */
public class PackageChangeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null)
            return;

        PackageManager pm = context.getPackageManager();
        if (pm == null)
            return;

        ComponentName service = new ComponentName(context, BabelService.class);
        ComponentName receiver = new ComponentName(context, OutgoingSmsReceiver.class);
        ComponentName activity = new ComponentName(context, Babel.class);

        try {
            PackageInfo pkg = pm.getPackageInfo(Helper.GOOGLE_VOICE_PACKAGE, 0);
            if (pkg == null)
                throw new Exception();

            pm.setComponentEnabledSetting(activity, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0);
            pm.setComponentEnabledSetting(service, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0);
            pm.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, 0);
        }
        catch (Exception e) {
            pm.setComponentEnabledSetting(activity, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0);
            pm.setComponentEnabledSetting(service, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0);
            pm.setComponentEnabledSetting(receiver, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, 0);
        }
    }
}
