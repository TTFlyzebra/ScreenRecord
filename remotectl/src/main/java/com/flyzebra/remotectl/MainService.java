package com.flyzebra.remotectl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.IBinder;
import android.provider.Settings;
import android.support.v4.app.NotificationCompat.Builder;
import android.widget.RemoteViews;

import com.flyzebra.remotectl.connect.PCSocketConnect;
import com.flyzebra.remotectl.model.FlvRtmpClient;
import com.flyzebra.utils.FlyLog;
import com.flyzebra.utils.SPUtil;


public class MainService extends Service  {
    private RemoteViews remoteviews = null;
    private final int NOTIFICATION_ID = 1;
    private Notification noti = null;
    private ServiceBroadCast broadcastreceiver = new ServiceBroadCast();
    public static final String MAIN_ACTION_BROADCAST_EXIT = "MAIN_ACTION_BROADCAST_EXIT";
    private String CHANNEL_ONE_ID = "com.flyzebra.record" ;

    private PCSocketConnect recvSocketTask;

    @Override
    public void onCreate() {
        super.onCreate();
        FlvRtmpClient.VIDEO_WIDTH = (int) SPUtil.get(this,"VIDEO_WIDTH",400);
        FlvRtmpClient.VIDEO_HEIGHT =  (int) SPUtil.get(this,"VIDEO_HEIGHT",712);
        FlvRtmpClient.VIDEO_BITRATE = (int) SPUtil.get(this,"VIDEO_BITRATE",1000000);
        FlvRtmpClient.VIDEO_IFRAME_INTERVAL = (int) SPUtil.get(this,"VIDEO_IFRAME_INTERVAL",5);
        FlvRtmpClient.VIDEO_FPS = (int) SPUtil.get(this,"VIDEO_FPS",24);
        recvSocketTask = new PCSocketConnect();
        recvSocketTask.start();
        /* 注册广播 */
        broadcastreceiver = new ServiceBroadCast();
        IntentFilter filter = new IntentFilter();
        filter.addAction(MAIN_ACTION_BROADCAST_EXIT);

        registerReceiver(broadcastreceiver, filter);

        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent PdIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Uri mUri = Settings.System.DEFAULT_NOTIFICATION_URI;
            NotificationChannel mChannel = new NotificationChannel(CHANNEL_ONE_ID, "driver", NotificationManager.IMPORTANCE_LOW);
            mChannel.setDescription("description");
            mChannel.setSound(mUri, Notification.AUDIO_ATTRIBUTES_DEFAULT);
            manager.createNotificationChannel(mChannel);
            noti = new Builder(this,CHANNEL_ONE_ID)
                    .setContentText("正在录像.....")
                    .setContentIntent(PdIntent)
                    .setOngoing(true)
                    .build();
        } else {
            // 提升应用权限
            noti = new Builder(this)
                    .setContentText("正在录像.....")
                    .setContentIntent(PdIntent)
                    .setOngoing(true)
                    .build();
        }
        noti.bigContentView = remoteviews;
        noti.icon = android.R.drawable.ic_media_play;
        startForeground(NOTIFICATION_ID, noti);
    }

    @Override
    public void onDestroy() {
        recvSocketTask.stop();
        stopForeground(true);
        unregisterReceiver(broadcastreceiver);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }


    private class ServiceBroadCast extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            FlyLog.d("onReceive="+intent);
            String action = intent.getAction();
            if (MAIN_ACTION_BROADCAST_EXIT.equals(action)) {
                stopSelf();
            }
        }
    }

}
