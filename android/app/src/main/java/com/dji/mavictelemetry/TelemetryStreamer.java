package com.dji.mavictelemetry;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import dji.common.battery.BatteryState;
import dji.common.error.DJIError;
import dji.common.flightcontroller.FlightControllerState;
import dji.common.flightcontroller.GoHomeAssessment;
import dji.common.flightcontroller.ObstacleDetectionSector;
import dji.common.flightcontroller.VisionDetectionState;
import dji.common.gimbal.GimbalState;
import dji.common.remotecontroller.HardwareState;
import dji.common.util.CommonCallbacks;
import dji.sdk.airlink.AirLink;
import dji.sdk.base.BaseProduct;
import dji.sdk.battery.Battery;
import dji.sdk.flightcontroller.Compass;
import dji.sdk.flightcontroller.FlightAssistant;
import dji.sdk.flightcontroller.FlightController;
import dji.sdk.products.Aircraft;
import dji.sdk.remotecontroller.RemoteController;

/**
 * Assina os callbacks de estado do Mavic Pro, mantém o último snapshot e o envia
 * como JSON (uma linha por datagrama UDP) a taxa fixa.
 */
public class TelemetryStreamer {
    private static final String TAG = "TelemetryStreamer";
    private static final int SEND_HZ = 10;

    public interface Listener {
        void onSnapshot(String json);
        void onError(String message);
    }

    private final Object lock = new Object();
    private final JSONObject snap = new JSONObject();
    private volatile Listener listener;

    private ScheduledExecutorService scheduler;
    private DatagramSocket socket;
    private InetAddress target;
    private int port;
    private volatile boolean sending;
    private volatile FlightController flightController;
    private volatile Battery battery;
    private int tick;
    private long seq;
    private volatile long sentOk;
    private volatile long sendErrors;
    private volatile String lastError = "";

    public long getSentOk() { return sentOk; }

    public long getSendErrors() { return sendErrors; }

    public String getLastError() { return lastError; }

    private final Context context;

    public TelemetryStreamer(Context context) {
        this.context = context.getApplicationContext();
    }

    /** A Activity pode ser recriada; o streamer sobrevive e só troca o ouvinte da UI. */
    public void setListener(Listener l) {
        this.listener = l;
    }

    private void error(String msg) {
        Listener l = listener;
        if (l != null) l.onError(msg);
    }

    /** Rede Wi-Fi (ou Ethernet) ativa; evita que o Android roteie pela interface USB do RC. */
    private Network findLanNetwork() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                return n;
            }
        }
        return null;
    }

    /** Registra os callbacks do SDK. Chamar quando o produto conectar. */
    public void attach(BaseProduct product) {
        if (!(product instanceof Aircraft)) return;
        Aircraft aircraft = (Aircraft) product;

        FlightController fc = aircraft.getFlightController();
        flightController = fc;
        if (fc != null) {
            fc.setStateCallback(this::onFlightState);
            FlightAssistant assistant = fc.getFlightAssistant();
            if (assistant != null) {
                assistant.setVisionDetectionStateUpdatedCallback(this::onVisionState);
            }
        }
        battery = aircraft.getBattery();
        if (battery != null) {
            battery.setStateCallback(this::onBatteryState);
        }
        if (aircraft.getGimbal() != null) {
            aircraft.getGimbal().setStateCallback(this::onGimbalState);
        }
        AirLink airLink = aircraft.getAirLink();
        if (airLink != null) {
            airLink.setUplinkSignalQualityCallback(q -> putSync("uplink_pct", q));
            airLink.setDownlinkSignalQualityCallback(q -> putSync("downlink_pct", q));
        }
        RemoteController rc = aircraft.getRemoteController();
        if (rc != null) {
            rc.setHardwareStateCallback(this::onRcState);
        }
    }

    /** Remove os callbacks (produto desconectado). */
    public void detach(BaseProduct product) {
        if (!(product instanceof Aircraft)) return;
        Aircraft aircraft = (Aircraft) product;
        if (aircraft.getFlightController() != null) aircraft.getFlightController().setStateCallback(null);
        if (aircraft.getBattery() != null) aircraft.getBattery().setStateCallback(null);
        if (aircraft.getGimbal() != null) aircraft.getGimbal().setStateCallback(null);
        if (aircraft.getAirLink() != null) {
            aircraft.getAirLink().setUplinkSignalQualityCallback(null);
            aircraft.getAirLink().setDownlinkSignalQualityCallback(null);
        }
        if (aircraft.getRemoteController() != null) aircraft.getRemoteController().setHardwareStateCallback(null);
        flightController = null;
        battery = null;
    }

    public synchronized void start(String host, int udpPort) {
        stop();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        // Resolve DNS/abre socket fora da main thread (NetworkOnMainThreadException).
        scheduler.execute(() -> {
            try {
                target = InetAddress.getByName(host);
                port = udpPort;
                bindToWifi = true;
                fallbackTried = false;
                openSocket();
                sending = true;
            } catch (Exception e) {
                error("Falha ao abrir UDP: " + e.getMessage());
            }
        });
        scheduler.scheduleAtFixedRate(this::sendTick, 500, 1000 / SEND_HZ, TimeUnit.MILLISECONDS);
        registerNetworkWatcher();
    }

    private final ConnectivityManager.NetworkCallback netCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            reopenAsync();
        }

        @Override
        public void onLost(Network network) {
            error("Wi-Fi perdido; aguardando reconexão...");
        }
    };
    private boolean watcherRegistered;

    /** Reabre o socket quando o Wi-Fi reconecta: um socket fixado numa rede antiga descarta pacotes. */
    private void registerNetworkWatcher() {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null || watcherRegistered) return;
        try {
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), netCallback);
            watcherRegistered = true;
        } catch (Exception e) {
            Log.w(TAG, "registerNetworkCallback failed", e);
        }
    }

    private void unregisterNetworkWatcher() {
        if (!watcherRegistered) return;
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            if (cm != null) cm.unregisterNetworkCallback(netCallback);
        } catch (Exception ignored) {
        }
        watcherRegistered = false;
    }

    private void reopenAsync() {
        ScheduledExecutorService sch = scheduler;
        if (sch == null || !sending) return;
        try {
            sch.execute(() -> {
                try {
                    openSocket();
                    error("Wi-Fi conectado: socket reaberto");
                } catch (Exception e) {
                    error("Falha ao reabrir UDP: " + e.getMessage());
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private volatile boolean bindToWifi = true;
    private boolean fallbackTried;

    /** Abre o socket UDP; com bindToWifi fixa o tráfego na rede Wi-Fi (evita a interface USB do RC). */
    private void openSocket() throws Exception {
        DatagramSocket old = socket;
        if (old != null) old.close();
        DatagramSocket sk = new DatagramSocket();
        sk.setBroadcast(true);
        if (bindToWifi) {
            Network lan = findLanNetwork();
            if (lan != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) lan.bindSocket(sk);
            } else {
                error("Sem Wi-Fi ativo: conecte o celular a uma rede Wi-Fi");
            }
        }
        socket = sk;
    }

    public synchronized void stop() {
        sending = false;
        unregisterNetworkWatcher();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (socket != null) {
            socket.close();
            socket = null;
        }
    }

    public boolean isSending() {
        return sending;
    }

    private void sendTick() {
        DatagramSocket sk = socket;
        if (!sending || sk == null || sk.isClosed()) return;
        if (tick++ % SEND_HZ == 0) pollSlowData();
        String json;
        synchronized (lock) {
            put("ts", System.currentTimeMillis());
            put("seq", seq++);
            json = snap.toString();
        }
        try {
            byte[] data = (json + "\n").getBytes(StandardCharsets.UTF_8);
            sk.send(new DatagramPacket(data, data.length, target, port));
            sentOk++;
            Listener l = listener;
            if (l != null) l.onSnapshot(json);
        } catch (Exception e) {
            // Envio em andamento durante stop(): não é erro.
            if (!sending || sk.isClosed()) return;
            sendErrors++;
            lastError = String.valueOf(e.getMessage());
            Log.w(TAG, "send failed", e);
            String msg = String.valueOf(e.getMessage());
            boolean eperm = msg.contains("EPERM") || msg.contains("not permitted");
            if (eperm && !fallbackTried) {
                // Tenta uma vez com o modo de rede oposto (com/sem fixar no Wi-Fi).
                fallbackTried = true;
                bindToWifi = !bindToWifi;
                try {
                    openSocket();
                    error("EPERM: tentando " + (bindToWifi ? "com" : "sem") + " Wi-Fi fixado...");
                    return;
                } catch (Exception ignored) {
                }
            }
            error("Erro ao enviar para " + target.getHostAddress() + ":" + port
                    + " (Wi-Fi fixado: " + (bindToWifi ? "sim" : "não") + "): " + msg
                    + (eperm ? "\nBloqueio de rede do Android: veja VPN, economia de dados e "
                    + "permissão de Wi-Fi do app nas configurações." : ""));
        }
    }

    // ---- callbacks do SDK (rodam em threads do SDK) ----

    /** Dados sem callback: lidos ~1x por segundo. */
    private void pollSlowData() {
        FlightController fc = flightController;
        if (fc != null && fc.getCompass() != null) {
            Compass compass = fc.getCompass();
            putSync("compass_heading", compass.getHeading());
            putSync("compass_error", compass.hasError());
        }
        Battery b = battery;
        if (b != null) {
            b.getCellVoltages(new CommonCallbacks.CompletionCallbackWith<Integer[]>() {
                @Override
                public void onSuccess(Integer[] v) {
                    if (v == null || v.length == 0) return;
                    int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
                    synchronized (lock) {
                        for (int i = 0; i < v.length; i++) {
                            put("cell" + (i + 1) + "_mv", v[i]);
                            min = Math.min(min, v[i]);
                            max = Math.max(max, v[i]);
                        }
                        put("cell_delta_mv", max - min);
                    }
                }

                @Override
                public void onFailure(DJIError error) {
                }
            });
        }
    }

    private void onFlightState(FlightControllerState s) {
        synchronized (lock) {
            put("lat", s.getAircraftLocation().getLatitude());
            put("lon", s.getAircraftLocation().getLongitude());
            put("alt", s.getAircraftLocation().getAltitude());
            put("vx", s.getVelocityX());
            put("vy", s.getVelocityY());
            put("vz", s.getVelocityZ());
            put("speed_h", Math.hypot(s.getVelocityX(), s.getVelocityY()));
            put("speed_3d", Math.sqrt(s.getVelocityX() * s.getVelocityX()
                    + s.getVelocityY() * s.getVelocityY() + s.getVelocityZ() * s.getVelocityZ()));
            put("pitch", s.getAttitude().pitch);
            put("roll", s.getAttitude().roll);
            put("yaw", s.getAttitude().yaw);
            put("head_dir", s.getAircraftHeadDirection());
            put("sats", s.getSatelliteCount());
            put("gps_level", String.valueOf(s.getGPSSignalLevel()));
            put("flight_mode", String.valueOf(s.getFlightMode()));
            put("is_flying", s.isFlying());
            put("motors_on", s.areMotorsOn());
            put("flight_time_s", s.getFlightTimeInSeconds());
            put("flight_count", s.getFlightCount());
            put("ultrasonic_m", s.getUltrasonicHeightInMeters());
            put("ultrasonic_used", s.isUltrasonicBeingUsed());
            put("vision_pos_used", s.isVisionPositioningSensorBeingUsed());
            put("takeoff_alt", s.getTakeoffLocationAltitude());
            put("home_set", s.isHomeLocationSet());
            if (s.isHomeLocationSet()) {
                put("home_lat", s.getHomeLocation().getLatitude());
                put("home_lon", s.getHomeLocation().getLongitude());
            }
            put("wind_warning", String.valueOf(s.getFlightWindWarning()));
            put("going_home", s.isGoingHome());
            put("gohome_state", String.valueOf(s.getGoHomeExecutionState()));
            put("gohome_height", s.getGoHomeHeight());
            put("imu_preheating", s.isIMUPreheating());
            put("bat_low_warn", s.isLowerThanBatteryWarningThreshold());
            put("bat_serious_warn", s.isLowerThanSeriousBatteryWarningThreshold());
            put("max_height_reached", s.hasReachedMaxFlightHeight());
            put("max_radius_reached", s.hasReachedMaxFlightRadius());
            GoHomeAssessment a = s.getGoHomeAssessment();
            if (a != null) {
                put("remaining_flight_s", a.getRemainingFlightTime());
                put("time_to_home_s", a.getTimeNeededToGoHome());
                put("bat_needed_home_pct", a.getBatteryPercentageNeededToGoHome());
                put("max_radius_home_m", a.getMaxRadiusAircraftCanFlyAndGoHome());
            }
        }
    }

    private void onBatteryState(BatteryState s) {
        synchronized (lock) {
            put("bat_pct", s.getChargeRemainingInPercent());
            put("bat_v_mv", s.getVoltage());
            put("bat_a_ma", s.getCurrent());
            put("bat_temp_c", s.getTemperature());
            put("bat_remaining_mah", s.getChargeRemaining());
            put("bat_full_mah", s.getFullChargeCapacity());
            put("bat_design_mah", s.getDesignCapacity());
            put("bat_discharges", s.getNumberOfDischarges());
            put("bat_life_pct", s.getLifetimeRemaining());
        }
    }

    private void onGimbalState(GimbalState s) {
        synchronized (lock) {
            put("gimbal_pitch", s.getAttitudeInDegrees().getPitch());
            put("gimbal_roll", s.getAttitudeInDegrees().getRoll());
            put("gimbal_yaw", s.getAttitudeInDegrees().getYaw());
        }
    }

    /** Distância mínima a obstáculos por sensor (nose/tail/right/left...). */
    private void onVisionState(VisionDetectionState s) {
        ObstacleDetectionSector[] sectors = s.getDetectionSectors();
        if (sectors == null || sectors.length == 0) return;
        double min = Double.MAX_VALUE;
        for (ObstacleDetectionSector sec : sectors) {
            double d = sec.getObstacleDistanceInMeters();
            if (d > 0 && d < min) min = d;
        }
        synchronized (lock) {
            put("obs_" + String.valueOf(s.getPosition()).toLowerCase(),
                    min == Double.MAX_VALUE ? JSONObject.NULL : (Object) min);
        }
    }

    private void onRcState(HardwareState s) {
        synchronized (lock) {
            if (s.getLeftStick() != null) {
                put("rc_lx", s.getLeftStick().getHorizontalPosition());
                put("rc_ly", s.getLeftStick().getVerticalPosition());
            }
            if (s.getRightStick() != null) {
                put("rc_rx", s.getRightStick().getHorizontalPosition());
                put("rc_ry", s.getRightStick().getVerticalPosition());
            }
            put("rc_left_dial", s.getLeftDial());
            put("rc_flight_switch", String.valueOf(s.getFlightModeSwitch()));
            if (s.getGoHomeButton() != null) put("rc_gohome_btn", s.getGoHomeButton().isClicked());
            if (s.getPauseButton() != null) put("rc_pause_btn", s.getPauseButton().isClicked());
        }
    }

    private void putSync(String key, Object value) {
        synchronized (lock) {
            put(key, value);
        }
    }

    private void put(String key, Object value) {
        try {
            if (value instanceof Double && (((Double) value).isNaN() || ((Double) value).isInfinite())) {
                snap.put(key, JSONObject.NULL);
            } else if (value instanceof Float && (((Float) value).isNaN() || ((Float) value).isInfinite())) {
                snap.put(key, JSONObject.NULL);
            } else {
                snap.put(key, value);
            }
        } catch (JSONException ignored) {
        }
    }
}
