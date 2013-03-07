
package com.mattprecious.notisync.service;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.telephony.PhoneNumberUtils;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.mattprecious.notisync.R;
import com.mattprecious.notisync.activity.MainActivity;
import com.mattprecious.notisync.bluetooth.BluetoothService;
import com.mattprecious.notisync.db.DbAdapter;
import com.mattprecious.notisync.devtools.DevToolsActivity;
import com.mattprecious.notisync.message.BaseMessage;
import com.mattprecious.notisync.message.ClearMessage;
import com.mattprecious.notisync.message.CustomMessage;
import com.mattprecious.notisync.message.GtalkMessage;
import com.mattprecious.notisync.message.PhoneCallMessage;
import com.mattprecious.notisync.message.TextMessage;
import com.mattprecious.notisync.model.SecondaryProfile;
import com.mattprecious.notisync.util.ContactHelper;
import com.mattprecious.notisync.util.MyLog;
import com.mattprecious.notisync.util.Preferences;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

public class SecondaryService extends Service {
    private final static String TAG = "SecondaryService";

    private static boolean running = false;

    private final String ACTION_TEXT_NOTIFICATION_DELETED =
            "notisync.service.SecondaryService.ACTION_TEXT_NOTIFICATION_DELETED";
    private final String ACTION_GTALK_NOTIFICATION_DELETED =
            "notisync.service.SecondaryService.ACTION_GTALK_NOTIFICATION_DELETED";

    private final int NOTIFICATION_ID_RUNNING = 1;
    private final int NOTIFICATION_ID_TEXT = 2;
    private final int NOTIFICATION_ID_PHONE_INCOMING = 3;
    private final int NOTIFICATION_ID_PHONE_MISSED = 4;
    private final int NOTIFICATION_ID_GTALK = 5;
    private final int NOTIFICATION_ID_CUSTOM = 6; // this must be the highest id

    private final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

    private DbAdapter dbAdapter;
    private LocalBroadcastManager broadcastManager;
    private NotificationManager notificationManager;
    private BluetoothService bluetoothService;
    private String connectedDeviceName;
    private Timer timer;

    private Map<String, List<TextMessage>> textMessages;
    private Map<String, List<GtalkMessage>> gtalkMessages;
    private PhoneCallMessage incomingCallMessage;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        broadcastManager = LocalBroadcastManager.getInstance(this);
        textMessages = Maps.newHashMap();
        gtalkMessages = Maps.newHashMap();

        running = true;
        broadcastManager.sendBroadcast(new Intent(ServiceActions.ACTION_SERVICE_STARTED));

        if (bluetoothAdapter == null) {
            stopSelf();
            return;
        }

        dbAdapter = new DbAdapter(this);
        connectedDeviceName = null;

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        startForeground(NOTIFICATION_ID_RUNNING, buildRunningNotification());

        bluetoothService = new BluetoothService(this, new SecondaryHandler(this), true);

        broadcastManager.registerReceiver(timerReceiver, new IntentFilter(
                ServiceActions.ACTION_UPDATE_TIMER));
        broadcastManager.registerReceiver(textNotificationDeletedReceiver, new IntentFilter(
                ACTION_TEXT_NOTIFICATION_DELETED));
        broadcastManager.registerReceiver(gtalkNotificationDeletedReceiver, new IntentFilter(
                ACTION_GTALK_NOTIFICATION_DELETED));
        broadcastManager.registerReceiver(devToolsMessageReceiver,
                new IntentFilter(DevToolsActivity.ACTION_RECEIVE_MESSAGE));

        registerReceiver(bluetoothStateReceiver, new IntentFilter(
                BluetoothAdapter.ACTION_STATE_CHANGED));

        updateTimer();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        running = false;
        broadcastManager.sendBroadcast(new Intent(ServiceActions.ACTION_SERVICE_STOPPED));

        if (timer != null) {
            timer.cancel();
        }

        try {
            broadcastManager.unregisterReceiver(timerReceiver);
            broadcastManager.unregisterReceiver(textNotificationDeletedReceiver);
            broadcastManager.unregisterReceiver(gtalkNotificationDeletedReceiver);
            broadcastManager.unregisterReceiver(devToolsMessageReceiver);

            unregisterReceiver(bluetoothStateReceiver);
        } catch (IllegalArgumentException e) {

        }

        if (bluetoothService != null) {
            bluetoothService.stop();
        }

        stopForeground(true);
    }

    public static boolean isRunning() {
        return running;
    }

    private void updateTimer() {
        if (timer != null) {
            timer.cancel();
        }

        timer = new Timer();
        timer.schedule(new TimerTask() {

            @Override
            public void run() {
                if (bluetoothAdapter.isEnabled()) {
                    if (bluetoothService != null
                            && bluetoothService.getState() == BluetoothService.STATE_NONE) {
                        bluetoothService.start();
                    }
                }
            }
        }, 0, Preferences.getPrimaryReconnectDelay(this) * 60000);
    }

    private void updateRunningNotification() {
        if (!running) {
            return;
        }

        notificationManager.notify(NOTIFICATION_ID_RUNNING, buildRunningNotification());
    }

    private Notification buildRunningNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        builder.setPriority(NotificationCompat.PRIORITY_MIN);
        builder.setSmallIcon(R.drawable.ic_stat_logo);
        builder.setContentTitle(getString(R.string.app_name));

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 0);
        builder.setContentIntent(pendingIntent);

        if (!bluetoothAdapter.isEnabled()) {
            builder.setContentText(getString(R.string.noti_bt_not_enabled));

            PendingIntent bluetoothIntent = PendingIntent.getActivity(this, 0, new Intent(
                    BluetoothAdapter.ACTION_REQUEST_ENABLE), 0);
            builder.addAction(R.drawable.ic_action_bluetooth,
                    getString(R.string.noti_action_enable_bt), bluetoothIntent);
        } else if (connectedDeviceName == null) {
            builder.setContentText(getString(R.string.noti_not_connected));
        } else {
            builder.setContentText(getString(R.string.noti_connected_to, connectedDeviceName));
        }

        return builder.build();
    }

    private void receiveMessage(BaseMessage message) {
        if (message instanceof TextMessage) {
            MyLog.d(TAG, "handling message of type: TextMessage");

            TextMessage textMessage = (TextMessage) message;
            handleTextMessage(textMessage);
        } else if (message instanceof PhoneCallMessage) {
            MyLog.d(TAG, "handing message of type: PhoneCallMessage");

            PhoneCallMessage phoneMessage = (PhoneCallMessage) message;
            handlePhoneCallMessage(phoneMessage);
        } else if (message instanceof GtalkMessage) {
            MyLog.d(TAG, "handling message of type: GtalkMessage");

            GtalkMessage gtalkMessage = (GtalkMessage) message;
            handleGtalkMessage(gtalkMessage);
        } else if (message instanceof CustomMessage) {
            MyLog.d(TAG, "handling message of type: CustomMessage");

            CustomMessage customMessage = (CustomMessage) message;
            handleCustomMessage(customMessage);
        } else if (message instanceof ClearMessage) {
            MyLog.d(TAG, "handling message of type: ClearMessage");

            ClearMessage clearMessage = (ClearMessage) message;
            handleClearMessage(clearMessage);
        } else {
            MyLog.e(TAG, "no handler for message: " + message);
        }
    }

    private void handleTextMessage(TextMessage message) {
        if (!Preferences.getSecondaryTextMessageEnabled(this)) {
            return;
        }

        if (textMessages.containsKey(message.number)) {
            textMessages.get(message.number).add(message);
        } else {
            List<TextMessage> list = Lists.newArrayList();
            list.add(message);

            textMessages.put(message.number, list);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        builder.setSmallIcon(R.drawable.ic_stat_sms);
        builder.setSound(getRingtoneUri(Preferences.getSecondaryTextMessageRingtone(this)));
        builder.setAutoCancel(true);

        int defaults = 0;
        if (Preferences.getSecondaryTextMessageVibrate(this)) {
            defaults |= Notification.DEFAULT_VIBRATE;
        }

        if (Preferences.getSecondaryTextMessageLights(this)) {
            defaults |= Notification.DEFAULT_LIGHTS;
        }

        builder.setDefaults(defaults);

        PendingIntent deleteIntent = PendingIntent.getActivity(this, 0, new Intent(
                ACTION_TEXT_NOTIFICATION_DELETED), 0);
        builder.setContentIntent(deleteIntent);
        builder.setDeleteIntent(deleteIntent);

        Notification notification = null;

        // only one thread
        if (textMessages.size() == 1) {
            String title;
            if (message.name != null) {
                title = message.name;
            } else {
                title = PhoneNumberUtils.formatNumber(message.number);
            }

            builder.setContentTitle(title);

            List<TextMessage> messageList = textMessages.get(message.number);

            // only one message, display the whole thing
            if (messageList.size() == 1) {
                // remove extra newlines
                String messageStr = !TextUtils.isEmpty(message.message) ? message.message
                        .replaceAll("\\n\\s+", "\n") : "";

                builder.setContentText(message.message);

                Bitmap photo = ContactHelper.getContactPhoto(this, message.number);
                if (photo != null) {
                    final int idealIconHeight =
                            getResources().getDimensionPixelSize(
                                    android.R.dimen.notification_large_icon_height);
                    final int idealIconWidth =
                            getResources().getDimensionPixelSize(
                                    android.R.dimen.notification_large_icon_width);
                    if (photo.getHeight() < idealIconHeight) {
                        // Scale this image to fit the intended size
                        photo = Bitmap.createScaledBitmap(
                                photo, idealIconWidth, idealIconHeight, true);
                    }
                    if (photo != null) {
                        builder.setLargeIcon(photo);
                    }
                }

                notification = new NotificationCompat.BigTextStyle(builder).bigText(messageStr)
                        .setSummaryText((photo == null) ? null : " ").build();
            } else {
                // multiple messages for the same thread
                SpannableStringBuilder buf = new SpannableStringBuilder();

                boolean first = true;
                for (TextMessage textMessage : messageList) {
                    // remove extra newlines
                    String messageStr = !TextUtils.isEmpty(textMessage.message) ? textMessage.message
                            .replaceAll("\\n\\s+", "\n")
                            : "";
                    buf.append(messageStr);

                    if (first) {
                        first = false;
                        buf.append('\n');
                    }
                }

                builder.setContentText(messageList.size() + " new message(s)");

                Bitmap photo = ContactHelper.getContactPhoto(this, message.number);
                if (photo != null) {
                    final int idealIconHeight =
                            getResources().getDimensionPixelSize(
                                    android.R.dimen.notification_large_icon_height);
                    final int idealIconWidth =
                            getResources().getDimensionPixelSize(
                                    android.R.dimen.notification_large_icon_width);
                    if (photo.getHeight() < idealIconHeight) {
                        // Scale this image to fit the intended size
                        photo = Bitmap.createScaledBitmap(
                                photo, idealIconWidth, idealIconHeight, true);
                    }
                    if (photo != null) {
                        builder.setLargeIcon(photo);
                    }
                }

                // Show a single notification -- big style with the text of all
                // the messages
                notification = new NotificationCompat.BigTextStyle(builder)
                        .bigText(buf)
                        // Forcibly show the last line, with the app's smallIcon
                        // in it, if we kicked the smallIcon out with a photo
                        .setSummaryText((photo == null) ? null : " ")
                        .build();
            }
        } else {
            // multiple threads

            String separator = ", ";
            SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder();

            boolean first = true;
            int totalMessageCount = 0;
            for (List<TextMessage> messageList : textMessages.values()) {
                totalMessageCount += messageList.size();

                if (first) {
                    first = false;
                } else {
                    spannableStringBuilder.append(separator);
                }

                TextMessage firstMessage = messageList.get(0);
                if (firstMessage.name != null) {
                    spannableStringBuilder.append(firstMessage.name);
                } else {
                    spannableStringBuilder.append(PhoneNumberUtils
                            .formatNumber(firstMessage.number));
                }

            }

            builder.setContentTitle(String.format("%d new messages", totalMessageCount));
            builder.setContentText(spannableStringBuilder);

            NotificationCompat.InboxStyle inboxStyle = new NotificationCompat.InboxStyle(builder);

            // We have to set the summary text to non-empty so the content text
            // doesn't show
            // up when expanded.
            inboxStyle.setSummaryText(" ");

            // At this point we've got multiple messages in multiple threads. We
            // only
            // want to show the most recent message per thread, which are in
            // mostRecentNotifPerThread.
            int c = 0;
            for (List<TextMessage> messageList : textMessages.values()) {
                if (c == 8)
                    break;

                TextMessage lastMessage = messageList.get(messageList.size() - 1);
                // remove extra newlines
                String messageStr = !TextUtils.isEmpty(lastMessage.message) ? lastMessage.message
                        .replaceAll("\\n\\s+", "\n") : "";

                SpannableStringBuilder spannableStringBuilder2 = new SpannableStringBuilder();
                if (lastMessage.name != null) {
                    spannableStringBuilder2.append(lastMessage.name).append(": ");
                } else {
                    spannableStringBuilder2.append(PhoneNumberUtils
                            .formatNumber(lastMessage.number)).append(": ");
                }

                spannableStringBuilder2.append(messageStr);
                inboxStyle.addLine(spannableStringBuilder2);
            }

            notification = inboxStyle.build();
        }

        notificationManager.notify(NOTIFICATION_ID_TEXT, notification);
    }

    private void handlePhoneCallMessage(PhoneCallMessage message) {
        if (!Preferences.getSecondaryPhoneCallEnabled(this)) {
            return;
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        builder.setSound(getRingtoneUri(Preferences.getSecondaryPhoneCallRingtone(this)));
        builder.setAutoCancel(true);

        int defaults = 0;
        if (Preferences.getSecondaryPhoneCallVibrate(this)) {
            defaults |= Notification.DEFAULT_VIBRATE;
        }

        if (Preferences.getSecondaryPhoneCallLights(this)) {
            defaults |= Notification.DEFAULT_LIGHTS;
        }

        builder.setDefaults(defaults);

        PendingIntent intent = PendingIntent.getActivity(this, 0, new Intent(), 0);
        builder.setContentIntent(intent);

        int notificationId;
        if (message.type == PhoneCallMessage.Type.INCOMING) {
            incomingCallMessage = message;

            notificationId = NOTIFICATION_ID_PHONE_INCOMING;
            builder.setContentTitle(getString(R.string.noti_title_incoming_call));
            builder.setSmallIcon(R.drawable.ic_stat_incoming);
        } else if (message.type == PhoneCallMessage.Type.MISSED) {
            notificationId = NOTIFICATION_ID_PHONE_MISSED;
            builder.setContentTitle(getString(R.string.noti_title_missed_call));
            builder.setSmallIcon(R.drawable.ic_stat_missed_call);

            if (incomingCallMessage != null && message.number != null
                    && message.number.equals(incomingCallMessage.number)) {
                notificationManager.cancel(NOTIFICATION_ID_PHONE_INCOMING);
                incomingCallMessage = null;
            }
        } else {
            return;
        }

        String text;
        if (message.name != null) {
            text = message.name;
        } else {
            text = PhoneNumberUtils.formatNumber(message.number);
        }

        builder.setContentText(text);

        Notification notification = builder.build();
        notificationManager.notify(notificationId, notification);
    }

    private void handleClearPhoneCallMessage(PhoneCallMessage message) {
        if (message.type == PhoneCallMessage.Type.INCOMING && message.equals(incomingCallMessage)) {
            notificationManager.cancel(NOTIFICATION_ID_PHONE_INCOMING);
            incomingCallMessage = null;
        }
    }

    private void handleGtalkMessage(GtalkMessage message) {
        if (!Preferences.getSecondaryGtalkEnabled(this)) {
            return;
        }

        if (Preferences.getSecondaryGtalkUnconnectedOnly(this)) {
            if (isNetworkAvailable()) {
                return;
            }
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        builder.setContentTitle(message.sender);
        builder.setContentText(message.message);
        builder.setSmallIcon(R.drawable.ic_stat_chat);
        builder.setSound(getRingtoneUri(Preferences.getSecondaryGtalkRingtone(this)));
        builder.setAutoCancel(true);

        int defaults = 0;
        if (Preferences.getSecondaryGtalkVibrate(this)) {
            defaults |= Notification.DEFAULT_VIBRATE;
        }

        if (Preferences.getSecondaryGtalkLights(this)) {
            defaults |= Notification.DEFAULT_LIGHTS;
        }

        builder.setDefaults(defaults);

        PendingIntent intent = PendingIntent.getActivity(this, 0, new Intent(), 0);
        builder.setContentIntent(intent);

        Notification notification = new NotificationCompat.BigTextStyle(builder).bigText(
                message.message).build();
        notificationManager.notify(NOTIFICATION_ID_GTALK, notification);
    }

    private void handleCustomMessage(CustomMessage message) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
        builder.setSmallIcon(R.drawable.ic_stat_logo);
        builder.setContentTitle(message.title);
        builder.setContentText(message.body);
        builder.setAutoCancel(true);

        SecondaryProfile profile = null;
        if (message.tag != null) {
            dbAdapter.openReadable();
            profile = dbAdapter.getSecondaryProfileByTag(message.tag);
            dbAdapter.close();

            if (profile != null) {
                if (!profile.isEnabled()) {
                    return;
                }

                if (profile.isUnconnectedOnly()) {
                    if (isNetworkAvailable()) {
                        return;
                    }
                }

                int defaults = 0;
                if (profile.isVibrate()) {
                    defaults |= Notification.DEFAULT_VIBRATE;
                }

                if (profile.isLed()) {
                    defaults |= Notification.DEFAULT_LIGHTS;
                }

                builder.setDefaults(defaults);
                builder.setSound(getRingtoneUri(profile.getRingtone()));
            }
        }

        if (profile == null) {
            builder.setDefaults(Notification.DEFAULT_ALL);
        }

        PendingIntent intent = PendingIntent.getActivity(this, 0, new Intent(), 0);
        builder.setContentIntent(intent);

        Notification notification = new NotificationCompat.BigTextStyle(builder).bigText(
                message.body).build();

        // use the profile ID to determine the notification ID
        // TODO: note that if somehow a profile ID is ~2^32, we're overwriting
        // notifications...
        notificationManager.notify(NOTIFICATION_ID_CUSTOM + profile.getId(), notification);
    }

    private void handleClearMessage(ClearMessage message) {
        MyLog.d(TAG, message.message.toString());
        if (message.message instanceof PhoneCallMessage) {
            PhoneCallMessage phoneMessage = (PhoneCallMessage) message.message;
            handleClearPhoneCallMessage(phoneMessage);
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    private Uri getRingtoneUri(String ringtone) {
        return (ringtone == null) ? null : Uri.parse(ringtone);
    }

    private static class SecondaryHandler extends Handler {
        private final WeakReference<SecondaryService> weakService;

        public SecondaryHandler(SecondaryService service) {
            weakService = new WeakReference<SecondaryService>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            SecondaryService service = weakService.get();
            switch (msg.what) {
                case BluetoothService.MESSAGE_CONNECTED:
                    service.connectedDeviceName = msg.getData().getString(
                            BluetoothService.EXTRA_DEVICE_NAME);
                    service.updateRunningNotification();
                    break;
                case BluetoothService.MESSAGE_DISCONNECTED:
                    service.connectedDeviceName = null;
                    service.updateRunningNotification();
                    break;
                case BluetoothService.MESSAGE_READ:
                    // construct a string from the valid bytes in the buffer
                    String readMessage = (String) msg.obj;
                    MyLog.d("DEBUG", "Received: " + readMessage);

                    service.receiveMessage(BaseMessage.fromJsonString(readMessage));
                    break;
            }
        }
    };

    private BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON);
            if (state == BluetoothAdapter.STATE_ON) {
                if (bluetoothService != null) {
                    bluetoothService.start();
                }
            } else if (state == BluetoothAdapter.STATE_TURNING_OFF
                    || state == BluetoothAdapter.STATE_OFF) {
                if (bluetoothService != null) {
                    bluetoothService.stop();
                }
            }

            updateRunningNotification();
        }
    };

    private final BroadcastReceiver timerReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            updateTimer();
        }

    };

    private final BroadcastReceiver textNotificationDeletedReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            textMessages.clear();
        }
    };

    private final BroadcastReceiver gtalkNotificationDeletedReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            gtalkMessages.clear();
        }
    };

    private final BroadcastReceiver devToolsMessageReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra("message");
            receiveMessage(BaseMessage.fromJsonString(message));
        }

    };

}
