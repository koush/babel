package org.cyanogenmod.babel;

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
    private static final char ENABLED_ACCESSIBILITY_SERVICES_SEPARATOR = ':';

    private ISms smsTransport;
    private SharedPreferences settings;

    // check which accessibility services are enabled
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

    // ensure that this accessibility service is enabled.
    // the service watches for google voice notifications to know when to check for new
    // messages.
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

    // hook into sms manager to be able to synthesize SMS events.
    // new messages from google voice get mocked out as real SMS events in Android.
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

    // set the accessibility filter to
    // watch only for google voice notifications
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
        info.packageNames = new String[] { Helper.GOOGLE_VOICE_PACKAGE };
        setServiceInfo(info);
    }

    // parse out the intent extras from android.intent.action.NEW_OUTGOING_SMS
    // and send it off via google voice
    void handleOutgoingSms(Intent intent) {
        boolean multipart = intent.getBooleanExtra("multipart", false);
        String destAddr = intent.getStringExtra("destAddr");
        String scAddr = intent.getStringExtra("scAddr");
        ArrayList<String> parts = intent.getStringArrayListExtra("parts");
        ArrayList<PendingIntent> sentIntents = intent.getParcelableArrayListExtra("sentIntents");
        ArrayList<PendingIntent> deliveryIntents = intent.getParcelableArrayListExtra("deliveryIntents");

        onSendMultipartText(destAddr, scAddr, parts, sentIntents, deliveryIntents, multipart);
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        if (null != settings.getString("account", null)) {
            ensureEnabled();
        }

        if (intent == null)
            return START_STICKY;

        // handle an outgoing sms on a background thread.
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

    // mark all sent intents as failures
    public void fail(List<PendingIntent> sentIntents) {
        if (sentIntents == null)
            return;
        for (PendingIntent si: sentIntents) {
            if (si == null)
                continue;
            try {
                si.send();
            }
            catch (Exception e) {
            }
        }
    }

    // mark all sent intents as successfully sent
    public void success(List<PendingIntent> sentIntents) {
        if (sentIntents == null)
            return;
        for (PendingIntent si: sentIntents) {
            if (si == null)
                continue;
            try {
                si.send(Activity.RESULT_OK);
            }
            catch (Exception e) {
            }
        }
    }

    // fetch the weirdo opaque token google voice needs...
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

    // mark an outgoing text as recently sent, so if it comes in via
    // round trip, we ignore it.
    PriorityQueue<String> recentSent = new PriorityQueue<String>();
    private void addRecent(String text) {
        while (recentSent.size() > 20)
            recentSent.remove();
        recentSent.add(text);
    }

    // send an outgoing sms event via google voice
    public void onSendMultipartText(String destAddr, String scAddr, List<String> texts, final List<PendingIntent> sentIntents, final List<PendingIntent> deliveryIntents, boolean multipart) {
        // grab the account and wacko opaque routing token thing
        String rnrse = settings.getString("_rns_se", null);
        String account = settings.getString("account", null);
        String authToken;

        try {
            // grab the auth token
            Bundle bundle = AccountManager.get(this).getAuthToken(new Account(account, "com.google"), "grandcentral", true, null, null).getResult();
            authToken = bundle.getString(AccountManager.KEY_AUTHTOKEN);

            if (rnrse == null) {
                fetchRnrSe(authToken);
                rnrse = settings.getString("_rns_se", null);
            }
        }
        catch (Exception e) {
            Log.e(LOGTAG, "Error fetching tokens", e);
            fail(sentIntents);
            return;
        }

        // combine the multipart text into one string
        StringBuilder textBuilder = new StringBuilder();
        for (String text: texts) {
            textBuilder.append(text);
        }
        String text = textBuilder.toString();

        try {
            // send it off, and note that we recently sent this message
            // for round trip tracking
            sendRnrSe(authToken, rnrse, destAddr, text);
            addRecent(text);
            success(sentIntents);
            return;
        }
        catch (Exception e) {
            Log.d(LOGTAG, "send error", e);
        }

        try {
            // on failure, fetch info and try again
            fetchRnrSe(authToken);
            rnrse = settings.getString("_rns_se", null);
            sendRnrSe(authToken, rnrse, destAddr, text);
            addRecent(text);
            success(sentIntents);
        }
        catch (Exception e) {
            Log.d(LOGTAG, "send failure", e);
            fail(sentIntents);
        }
    }

    // hit the google voice api to send a text
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
    // insert a message into the sms/mms provider.
    // we do this in the case of outgoing messages
    // that were not sent via this phone, and also on initial
    // message sync.
    void insertMessage(String number, String text, int type, long date) {
        ContentValues values = new ContentValues();
        values.put("address", number);
        values.put("body", text);
        values.put("type", type);
        values.put("date", date);
        values.put("read", 1);
        getContentResolver().insert(Uri.parse("content://sms/sent"), values);
    }

    // refresh the messages that were on the server
    void refreshMessages() {
        String account = settings.getString("account", null);
        if (account == null)
            return;

        try {
            // tokens!
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

            // sort by date order so the events get added in the same order
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

                // sync up outgoing messages
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
                    // synthesize a BROADCAST_SMS event
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

    // clear the google voice notification so the user doesn't get double notified.
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
                cancelAllNotifications.invoke(internalNotificationService, Helper.GOOGLE_VOICE_PACKAGE, userId);
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

        if (!Helper.GOOGLE_VOICE_PACKAGE.equals(event.getPackageName()))
            return;

        clearGoogleVoiceNotifications();

        startRefresh();
    }

    @Override
    public void onInterrupt() {
    }
}
