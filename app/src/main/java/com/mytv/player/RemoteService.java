package com.mytv.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import fi.iki.elonen.NanoHTTPD;

/** Keeps the phone web page / remote control running, even while a video plays. */
public class RemoteService extends Service {
    public static final int PORT = 8080;
    public static volatile String error = null;
    public static volatile boolean running = false;

    private WebServer server;

    public static void start(Context c) {
        try {
            Intent i = new Intent(c, RemoteService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            error = "Could not start the phone remote: " + e;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        goForeground();
        Store.init(this);
        try {
            server = new WebServer(this, PORT);
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            running = true;
            error = null;
        } catch (Exception e) {
            server = null;
            running = false;
            error = "Could not start the phone page. Is another app using port " + PORT + "?";
        }
    }

    private void goForeground() {
        try {
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                NotificationChannel ch = new NotificationChannel("remote", "Phone remote",
                        NotificationManager.IMPORTANCE_MIN);
                if (nm != null) nm.createNotificationChannel(ch);
                b = new Notification.Builder(this, "remote");
            } else {
                b = new Notification.Builder(this);
            }
            Notification n = b.setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("My TV Player")
                    .setContentText("Phone remote: http://" + Net.ip() + ":" + PORT)
                    .setOngoing(true)
                    .build();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1, n);
            }
        } catch (Exception ignored) {
            // Still works as a normal service while the app is open.
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        if (server != null) server.stop();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
