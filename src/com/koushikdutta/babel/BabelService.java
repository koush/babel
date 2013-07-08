package com.koushikdutta.babel;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.android.internal.telephony.ISms;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import com.koushikdutta.async.http.Multimap;
import com.koushikdutta.ion.Ion;
import com.koushikdutta.ion.Response;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Created by koush on 7/5/13.
 */
public class BabelService extends AccessibilityService {
    private static final String LOGTAG = "Babel";
    private static final String GOOGLE_VOICE_PACKAGE = "com.google.android.apps.googlevoice";
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

    // hook into sms manager to intercept outgoing sms
    private void registerSmsMiddleware() {
        try {
            Class sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            smsTransport = ISms.Stub.asInterface((IBinder)getService.invoke(null, "isms"));
        }
        catch (Exception e) {
            Log.e(LOGTAG, "register error", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        settings = getSharedPreferences("settings", MODE_PRIVATE);

        registerSmsMiddleware();
        clearGoogleVoiceNotifications();
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
        info.packageNames = new String[] { GOOGLE_VOICE_PACKAGE };
        setServiceInfo(info);
    }

    void handleOutgoingSms(Intent intent) {
        boolean multipart = intent.getBooleanExtra("multipart", false);
        if (!multipart) {
            String destAddr = intent.getStringExtra("destAddr");
            String scAddr = intent.getStringExtra("scAddr");
            String text = intent.getStringExtra("text");
            PendingIntent sentIntent = intent.getParcelableExtra("sentIntent");
            PendingIntent deliveryIntent = intent.getParcelableExtra("deliveryIntent");

            List<PendingIntent> sentIntents = null;
            List<PendingIntent> deliveryIntents = null;
            ArrayList<String> texts = new ArrayList<String>();
            texts.add(text);
            if (sentIntent != null) {
                sentIntents = new ArrayList<PendingIntent>();
                sentIntents.add(sentIntent);
            }
            if (deliveryIntent != null) {
                deliveryIntents = new ArrayList<PendingIntent>();
                deliveryIntents.add(deliveryIntent);
            }

            onSendMultipartText(destAddr, scAddr, texts, sentIntents, deliveryIntents, false);
        }
        else {
            String destAddr = intent.getStringExtra("destAddr");
            String scAddr = intent.getStringExtra("scAddr");
            ArrayList<String> parts = intent.getStringArrayListExtra("parts");
            ArrayList<PendingIntent> sentIntents = intent.getParcelableArrayListExtra("sentIntent");
            ArrayList<PendingIntent> deliveryIntents = intent.getParcelableArrayListExtra("deliveryIntents");

            onSendMultipartText(destAddr, scAddr, parts, sentIntents, deliveryIntents, false);
        }
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        if (null != settings.getString("account", null)) {
            ensureEnabled();
        }

        if (intent == null)
            return START_STICKY;

        if (intent.getAction() == "android.intent.action.NEW_OUTGOING_SMS") {
            new Thread() {
                @Override
                public void run() {
                    handleOutgoingSms(intent);
                }
            }.start();
        }

        return START_STICKY;
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

    void fetchRnrSe(String authToken) throws ExecutionException, InterruptedException {
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

    private static final String MOBILE_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 5_0 like Mac OS X) AppleWebKit/534.46 (KHTML, like Gecko) Version/5.1 Mobile/9A334 Safari/7534.48.3";

    void fetchGvx(String authToken) throws ExecutionException, InterruptedException {
        Response<String> response = Ion.with(this)
        .load("https://www.google.com/voice/m?initialauth&pli=1")
        .followRedirect(false)
        .userAgent(MOBILE_AGENT)
        .setHeader("Authorization", "GoogleLogin auth=" + authToken)
        .asString()
        .withResponse()
        .get();

        Multimap cookies = Multimap.parseHeader(response.getHeaders().get("Set-Cookie"));
        String gvx = cookies.getString("gvx");
        if (gvx == null)
            throw new ExecutionException(new Exception("unable to retrieve gvx"));
        settings.edit()
        .putString("gvx", gvx)
        .commit();
    }

    private void addRecent(String text) {
        while (recentSent.size() > 20)
            recentSent.remove();
        recentSent.add(text);
    }

    PriorityQueue<String> recentSent = new PriorityQueue<String>();
    final static boolean useGvx = false;
    public void onSendMultipartText(String destAddr, String scAddr, List<String> texts, final List<PendingIntent> sentIntents, final List<PendingIntent> deliveryIntents, boolean multipart) {
        String rnrse = settings.getString("_rns_se", null);
        String gvx = settings.getString("gvx", null);
        String account = settings.getString("account", null);
        String authToken;

        try {
            Bundle bundle = AccountManager.get(this).getAuthToken(new Account(account, "com.google"), "grandcentral", true, null, null).getResult();
            authToken = bundle.getString(AccountManager.KEY_AUTHTOKEN);

            if (useGvx) {
                if (gvx == null) {
                    fetchGvx(authToken);
                    gvx = settings.getString("gvx", null);
                }
            }
            else {
                if (rnrse == null) {
                    fetchRnrSe(authToken);
                    rnrse = settings.getString("_rns_se", null);
                }
            }
        }
        catch (Exception e) {
            Log.e(LOGTAG, "Error fetching tokens", e);
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
                if (useGvx)
                    sendGvx(authToken, gvx, destAddr, text);
                else
                    sendRnrSe(authToken, rnrse, destAddr, text);
                addRecent(text);
                if (si != null)
                    si.send(Activity.RESULT_OK);
                continue;
            }
            catch (Exception e) {
                Log.d(LOGTAG, "send error", e);
            }

            try {
                if (useGvx) {
                    fetchGvx(authToken);
                    gvx = settings.getString("gvx", null);
                    sendGvx(authToken, gvx, destAddr, text);
                }
                else {
                    // fetch info and try again
                    fetchRnrSe(authToken);
                    rnrse = settings.getString("_rns_se", null);
                    sendRnrSe(authToken, rnrse, destAddr, text);
                }
                addRecent(text);
                if (si != null)
                    si.send(Activity.RESULT_OK);
            }
            catch (Exception e) {
                Log.d(LOGTAG, "send failure", e);
                fail(si);
            }
        }
    }

    void sendRnrSe(String authToken, String rnrse, String number, String text) throws Exception {
        JsonObject json = Ion.with(this)
        .load("https://www.google.com/voice/sms/send/")
        .setHeader("Authorization", "GoogleLogin auth=" + authToken)
        .setBodyParameter("phoneNumber", number)
        .setBodyParameter("sendErrorSms", "0")
        .setBodyParameter("text", text)
        .setBodyParameter("_rnr_se", rnrse)
        .asJsonObject()
        .get();

        if (!json.get("ok").getAsBoolean())
            throw new Exception(json.toString());
    }

    void sendGvx(String authToken, String gvx, String number, String text) throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("gvx", gvx);

        Response<String> response = Ion.with(this)
        .load("https://www.google.com/voice/m/x")
        .setHeader("Authorization", "GoogleLogin auth=" + authToken)
        .userAgent(MOBILE_AGENT)
        .followRedirect(false)
        .addQuery("m", "sms")
        .addQuery("n", number)
        .addQuery("txt", text)
        .setJsonObjectBody(json)
        .asString()
        .withResponse()
        .get();
    }


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

                // on first sync, just populate the mms provider...
                // don't send any broadcasts.
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

                // sync up outgoing messages?
                if (message.type == VOICE_OUTGOING_SMS) {
                    boolean found = false;
                    for (String recent: recentSent) {
                        if (TextUtils.equals(recent, message.message)) {
                            recentSent.remove(message.message);
                            found = true;
                            break;
                        }
                    }
                    if (!found)
                        insertMessage(message.phoneNumber, message.message, PROVIDER_OUTGOING_SMS, message.date);
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
            Log.e(LOGTAG, "Error refreshing messages", e);
        }
    }

    Object internalNotificationService;
    Method cancelAllNotifications;
    int userId;
    private void clearGoogleVoiceNotifications() {
        try {
            if (cancelAllNotifications == null) {
                // run this to get the internal service to populate
                NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
                nm.cancelAll();

                Field f = NotificationManager.class.getDeclaredField("sService");
                f.setAccessible(true);
                internalNotificationService = f.get(null);
                cancelAllNotifications = internalNotificationService.getClass().getDeclaredMethod("cancelAllNotifications", String.class, int.class);
                userId = (Integer)UserHandle.class.getDeclaredMethod("myUserId").invoke(null);
            }
            if (cancelAllNotifications != null)
                cancelAllNotifications.invoke(internalNotificationService, GOOGLE_VOICE_PACKAGE, userId);
        }
        catch (Exception e) {
            Log.d(LOGTAG, "Error clearing GoogleVoice notifications", e);
        }
    }

    void startRefresh() {
        needsRefresh = true;

        // if a sync is in progress, dont start another
        if (refreshThread != null && refreshThread.isAlive())
            return;

        refreshThread = new Thread() {
            @Override
            public void run() {
                while (needsRefresh) {
                    needsRefresh = false;
                    refreshMessages();
                }
            }
        };

        refreshThread.start();
    }

    boolean needsRefresh;
    Thread refreshThread;
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)
            return;

        if (!GOOGLE_VOICE_PACKAGE.equals(event.getPackageName()))
            return;

        clearGoogleVoiceNotifications();

        startRefresh();
    }

    @Override
    public void onInterrupt() {
    }
}
