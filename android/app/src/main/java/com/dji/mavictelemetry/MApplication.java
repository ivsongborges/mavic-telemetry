package com.dji.mavictelemetry;

import android.content.Context;

import androidx.multidex.MultiDex;
import androidx.multidex.MultiDexApplication;

import com.secneo.sdk.Helper;

/**
 * O SDK V4 exige Helper.install() em attachBaseContext (carrega as libs protegidas)
 * e a Application deve suportar MultiDex.
 */
public class MApplication extends MultiDexApplication {
    private static TelemetryStreamer streamer;

    /** Um único streamer por processo: sobrevive à recriação da Activity. */
    public static synchronized TelemetryStreamer streamer(Context ctx) {
        if (streamer == null) streamer = new TelemetryStreamer(ctx);
        return streamer;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
        Helper.install(this);
    }
}
