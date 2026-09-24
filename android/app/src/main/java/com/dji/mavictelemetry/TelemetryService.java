package com.dji.mavictelemetry;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

/**
 * Serviço em primeiro plano: mantém o processo vivo, a CPU acordada e o Wi-Fi ativo
 * para que o envio de telemetria continue com o app em segundo plano ou a tela apagada.
 * O envio em si é feito pelo TelemetryStreamer (singleton em MApplication).
 */
public class TelemetryService extends Service {
    public static final String ACTION_STOP = "com.dji.mavictelemetry.STOP";
    private static final String CHANNEL_ID = "telemetry";
    private static final int NOTIF_ID = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification());
            handler.postDelayed(this, 2000);
        }
    };

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            MApplication.streamer(this).stop();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification());
        acquireLocks();
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, 2000);
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Telemetria", NotificationManager.IMPORTANCE_LOW));
        }
        int immutable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), immutable);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, TelemetryService.class).setAction(ACTION_STOP), immutable);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Mavic Telemetry")
                .setContentText(statusText())
                .setOnlyAlertOnce(true)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(0, "Parar", stop)
                .build();
    }

    private String statusText() {
        TelemetryStreamer st = MApplication.streamer(this);
        String t = "Enviados: " + st.getSentOk() + " | falhas: " + st.getSendErrors();
        if (st.getSendErrors() > 0) t += " (" + st.getLastError() + ")";
        return t;
    }

    private void acquireLocks() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mavictelemetry:stream");
                wakeLock.acquire();
            }
        }
        if (wifiLock == null) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int mode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wm.createWifiLock(mode, "mavictelemetry:wifi");
                wifiLock.acquire();
            }
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(refresh);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        wakeLock = null;
        wifiLock = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
