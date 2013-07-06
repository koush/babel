package com.koushikdutta.babel;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
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
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Created by koush on 7/5/13.
 */
public class BabelService extends AccessibilityService {
    private static final String LOGTAG = "Babel";
    private ISms smsTransport;
    private SharedPreferences settings;

    @Override
    public void onCreate() {
        super.onCreate();

        settings = getSharedPreferences("settings", MODE_PRIVATE);
        registerSmsMiddleware();
    }

    boolean connected;

    void broadcast() {
        Intent i = new Intent("com.koushikdutta.babel.STATUS");
        i.putExtra("connected", connected);
        sendBroadcast(i);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        connected = false;
        broadcast();
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

        broadcast();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        broadcast();

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
            long max = timestamp;
            for (Message message: all) {
                max = Math.max(max, message.date);
                if (message.phoneNumber == null)
                    continue;
                if (message.date <= timestamp)
                    continue;
                if (message.message == null)
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
