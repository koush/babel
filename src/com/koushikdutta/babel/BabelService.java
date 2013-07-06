package com.koushikdutta.babel;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.android.internal.telephony.ISms;
import com.android.internal.telephony.ISmsMiddleware;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import com.koushikdutta.ion.Ion;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Created by koush on 7/5/13.
 */
public class BabelService extends AccessibilityService {
    private static final String LOGTAG = "Babel";
    private static final char ENABLED_ACCESSIBILITY_SERVICES_SEPARATOR = ':';

    private ISms smsTransport;
    private SharedPreferences settings;


    private Set<ComponentName> getEnabledServicesFromSettings() {
        String enabledServicesSetting = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServicesSetting == null) {
            enabledServicesSetting = "";
        }
        Set<ComponentName> enabledServices = new HashSet<ComponentName>();
        TextUtils.SimpleStringSplitter colonSplitter = new TextUtils.SimpleStringSplitter(ENABLED_ACCESSIBILITY_SERVICES_SEPARATOR);
        colonSplitter.setString(enabledServicesSetting);
        while (colonSplitter.hasNext()) {
            String componentNameString = colonSplitter.next();
            ComponentName enabledService = ComponentName.unflattenFromString(
            componentNameString);
            if (enabledService != null) {
                enabledServices.add(enabledService);
            }
        }
        return enabledServices;
    }

    private void ensureEnabled() {
        Set<ComponentName> enabledServices = getEnabledServicesFromSettings();
        ComponentName me = new ComponentName(this, getClass());
        if (enabledServices.contains(me) && connected)
            return;

        enabledServices.add(me);

        // Update the enabled services setting.
        StringBuilder enabledServicesBuilder = new StringBuilder();
        // Keep the enabled services even if they are not installed since we
        // have no way to know whether the application restore process has
        // completed. In general the system should be responsible for the
        // clean up not settings.
        for (ComponentName enabledService : enabledServices) {
            enabledServicesBuilder.append(enabledService.flattenToString());
            enabledServicesBuilder.append(ENABLED_ACCESSIBILITY_SERVICES_SEPARATOR);
        }
        final int enabledServicesBuilderLength = enabledServicesBuilder.length();
        if (enabledServicesBuilderLength > 0) {
            enabledServicesBuilder.deleteCharAt(enabledServicesBuilderLength - 1);
        }
        Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, enabledServicesBuilder.toString());

        // Update accessibility enabled.
        Settings.Secure.putInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
        Settings.Secure.putInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        settings = getSharedPreferences("settings", MODE_PRIVATE);
        registerSmsMiddleware();
    }

    boolean connected;

    @Override
    public boolean onUnbind(Intent intent) {
        connected = false;
        return super.onUnbind(intent);
    }

    @Override
    protected void onServiceConnected (){
        super.onServiceConnected();
        connected = true;

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        // We are interested in all types of accessibility events.
        info.eventTypes = AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED;
        // We want to provide specific type of feedback.
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        // We want to receive events in a certain interval.
        info.notificationTimeout = 100;
        // We want to receive accessibility events only from certain packages.
        info.packageNames = new String[] { "com.google.android.apps.googlevoice" };
        setServiceInfo(info);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        if (null != settings.getString("account", null)) {
            ensureEnabled();
        }

        return START_STICKY;
    }

    // hook into sms manager to intercept outgoing sms
    private void registerSmsMiddleware() {
        try {
            Class sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            smsTransport = ISms.Stub.asInterface((IBinder)getService.invoke(null, "isms"));
            smsTransport.registerSmsMiddleware("babel", stub);
        }
        catch (Exception e) {
            Log.e(LOGTAG, "register error", e);
        }
    }

    public void fail(List<PendingIntent> sentIntents) {
        if (sentIntents == null)
            return;
        try {
            for (PendingIntent si: sentIntents)
                fail(si);
        }
        catch (Exception e) {
        }
    }

    void fail(PendingIntent sentIntent) {
        if (sentIntent == null)
            return;
        try {
            sentIntent.send();
        }
        catch (Exception e) {
        }
    }

    void fetchInfo(String authToken) throws ExecutionException, InterruptedException {
        JsonObject json = Ion.with(this)
        .load("https://www.google.com/voice/request/user")
        .setHeader("Authorization", "GoogleLogin auth=" + authToken)
        .asJsonObject()
        .get();

        String rnrse = json.get("r").getAsString();

        settings.edit()
        .putString("_rns_se", rnrse)
        .commit();
    }

    public void onSendMultipartText(String destAddr, String scAddr, List<String> texts, final List<PendingIntent> sentIntents, final List<PendingIntent> deliveryIntents, boolean multipart) {
        String rnrse = settings.getString("_rns_se", null);
        String account = settings.getString("account", null);
        String authToken;

        try {
            Bundle bundle = AccountManager.get(this).getAuthToken(new Account(account, "com.google"), "grandcentral", true, null, null).getResult();
            authToken = bundle.getString(AccountManager.KEY_AUTHTOKEN);

            if (rnrse == null) {
                fetchInfo(authToken);

                rnrse = settings.getString("_rns_se", null);
            }
        }
        catch (Exception e) {
            e.printStackTrace();
            fail(sentIntents);
            return;
        }

        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            PendingIntent si;
            if (sentIntents != null)
                si = sentIntents.get(i);
            else
                si = null;

            try {
                send(authToken, rnrse, destAddr, text);
                if (si != null)
                    si.send(Activity.RESULT_OK);

                continue;
            }
            catch (Exception e) {
                Log.d(LOGTAG, "send error", e);
            }

            try {
                // fetch info and try again
                fetchInfo(authToken);

                send(authToken, rnrse, destAddr, text);
                if (si != null)
                    si.send(Activity.RESULT_OK);
            }
            catch (Exception e) {
                Log.d(LOGTAG, "send failure", e);
                fail(si);
            }
        }
    }

    void send(String authToken, String rnrse, String number, String text) throws Exception {
        JsonObject json = Ion.with(this)
        .load("https://www.google.com/voice/sms/send/")
        .setHeader("Authorization", "GoogleLogin auth=" + authToken)
        .setBodyParameter("id", "")
        .setBodyParameter("phoneNumber", number)
        .setBodyParameter("sendErrorSms", "0")
        .setBodyParameter("text", text)
        .setBodyParameter("_rnr_se", rnrse)
        .asJsonObject()
        .get();

        if (!json.get("ok").getAsBoolean())
            throw new Exception(json.toString());
    }

    ISmsMiddleware.Stub stub = new ISmsMiddleware.Stub() {
        @Override
        public boolean onSendText(String destAddr, String scAddr, String text, PendingIntent sentIntent, PendingIntent deliveryIntent) throws RemoteException {
            List<PendingIntent> sentIntents = null;
            List<PendingIntent> deliveryIntents = null;
            if (sentIntent != null) {
                sentIntents = new ArrayList<PendingIntent>();
                sentIntents.add(sentIntent);
            }
            if (deliveryIntent != null) {
                deliveryIntents = new ArrayList<PendingIntent>();
                deliveryIntents.add(deliveryIntent);
            }

            ArrayList<String> texts = new ArrayList<String>();
            texts.add(text);
            return onSendMultipartText(destAddr, scAddr, texts, sentIntents, deliveryIntents, false);
        }

        @Override
        public boolean onSendMultipartText(String destAddr, String scAddr, List<String> texts, final List<PendingIntent> sentIntents, final List<PendingIntent> deliveryIntents) throws RemoteException {
            return onSendMultipartText(destAddr, scAddr, texts, sentIntents, deliveryIntents, true);
        }

        public boolean onSendMultipartText(final String destAddr, final String scAddr, final List<String> texts, final List<PendingIntent> sentIntents, final List<PendingIntent> deliveryIntents, final boolean multipart) throws RemoteException {
            if (settings.getString("account", null) == null)
                return false;

            new Thread() {
                @Override
                public void run() {
                    BabelService.this.onSendMultipartText(destAddr, scAddr, texts, sentIntents, deliveryIntents, multipart);
                }
            }.start();

            return true;
        }
    };

    public static class Payload {
        @SerializedName("messageList")
        public ArrayList<Conversation> conversations = new ArrayList<Conversation>();
    }

    public static class Conversation {
        @SerializedName("children")
        public ArrayList<Message> messages = new ArrayList<Message>();
    }

    public static class Message {
        @SerializedName("startTime")
        public long date;

        @SerializedName("phoneNumber")
        public String phoneNumber;

        @SerializedName("message")
        public String message;

        // 10 is incoming
        // 11 is outgoing
        @SerializedName("type")
        int type;
    }

    private static final int VOICE_INCOMING_SMS = 10;
    private static final int VOICE_OUTGOING_SMS = 11;

    private static final int PROVIDER_INCOMING_SMS = 1;
    private static final int PROVIDER_OUTGOING_SMS = 2;
    void insertMessage(String number, String text, int type, long date) {
        ContentValues values = new ContentValues();
        values.put("address", number);
        values.put("body", text);
        values.put("type", type);
        values.put("date", date);
        values.put("read", 1);
        getContentResolver().insert(Uri.parse("content://sms/sent"), values);
    }

    void refreshMessages() {
        String account = settings.getString("account", null);
        if (account == null)
            return;

        try {
            Bundle bundle = AccountManager.get(this).getAuthToken(new Account(account, "com.google"), "grandcentral", true, null, null).getResult();
            String authToken = bundle.getString(AccountManager.KEY_AUTHTOKEN);

            Payload payload = Ion.with(this)
            .load("https://www.google.com/voice/request/messages")
            .setHeader("Authorization", "GoogleLogin auth=" + authToken)
            .as(Payload.class)
            .get();

            ArrayList<Message> all = new ArrayList<Message>();
            for (Conversation conversation: payload.conversations) {
                for (Message message: conversation.messages)
                    all.add(message);
            }

            Collections.sort(all, new Comparator<Message>() {
                @Override
                public int compare(Message lhs, Message rhs) {
                    if (lhs.date == rhs.date)
                        return 0;
                    if (lhs.date > rhs.date)
                        return 1;
                    return -1;
                }
            });

            long timestamp = settings.getLong("timestamp", 0);
            boolean first = timestamp == 0;
            long max = timestamp;
            for (Message message: all) {
                max = Math.max(max, message.date);
                if (message.phoneNumber == null)
                    continue;
                if (message.date <= timestamp)
                    continue;
                if (message.message == null)
                    continue;

                if (first) {
                    int type;
                    if (message.type == VOICE_INCOMING_SMS)
                        type = PROVIDER_INCOMING_SMS;
                    else if (message.type == VOICE_OUTGOING_SMS)
                        type = PROVIDER_OUTGOING_SMS;
                    else
                        continue;
                    // just populate the content provider and go
                    insertMessage(message.phoneNumber, message.message, type, message.date);
                    continue;
                }

                if (message.type != VOICE_INCOMING_SMS)
                    continue;
                ArrayList<String> list = new ArrayList<String>();
                list.add(message.message);
                try {
                    smsTransport.synthesizeMessages(message.phoneNumber, null, list, message.date);
                }
                catch (Exception e) {
                    e.printStackTrace();;
                }
            }
            settings.edit()
            .putLong("timestamp", max)
            .commit();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        Log.d(LOGTAG, event.toString());
        if (event.getEventType() != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)
            return;

        if (!"com.google.android.apps.googlevoice".equals(event.getPackageName()))
            return;

        new Thread() {
            @Override
            public void run() {
                refreshMessages();
            }
        }.start();
    }

    @Override
    public void onInterrupt() {
    }
}
