package com.sportmock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MockService extends Service {

    private static final String CH = "sportmock";
    private static final double STRAIGHT = 84.39;
    private static final double RADIUS = 36.80;
    private static final double JITTER = 2.5;
    private static final long INTERVAL = 1000L;

    private LocationManager lm;
    private volatile boolean running = false;
    private Thread worker;
    private final Random rnd = new Random();
    private final List<String> providers = new ArrayList<String>();

    // custom route, WGS-84
    private double[][] route;
    private double[] cum;
    private double routeLen;

    // GCJ-02 -> WGS-84
    private static final double A = 6378245.0;
    private static final double EE = 0.00669342162296594323;

    private static boolean outOfChina(double lat, double lon) {
        return !(lon > 73.66 && lon < 135.05 && lat > 3.86 && lat < 53.55);
    }

    private static double tfLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
                + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI)
                + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * Math.PI)
                + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * Math.PI)
                + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double tfLon(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
                + 0.1 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * Math.PI)
                + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * Math.PI)
                + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * Math.PI)
                + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0;
        return ret;
    }

    private static double[] wgs2gcj(double lat, double lon) {
        double dLat = tfLat(lon - 105.0, lat - 35.0);
        double dLon = tfLon(lon - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * Math.PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * Math.PI);
        dLon = (dLon * 180.0) / (A / sqrtMagic * Math.cos(radLat) * Math.PI);
        return new double[]{lat + dLat, lon + dLon};
    }

    private static double[] gcj2wgs(double lat, double lon) {
        if (outOfChina(lat, lon)) return new double[]{lat, lon};
        double wLat = lat, wLon = lon;
        for (int i = 0; i < 12; i++) {
            double[] g = wgs2gcj(wLat, wLon);
            double dLat = g[0] - lat;
            double dLon = g[1] - lon;
            wLat -= dLat;
            wLon -= dLon;
            if (Math.abs(dLat) < 1e-10 && Math.abs(dLon) < 1e-10) break;
        }
        return new double[]{wLat, wLon};
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        double rawLat = intent == null ? 31.0 : intent.getDoubleExtra("lat", 31.0);
        double rawLon = intent == null ? 121.0 : intent.getDoubleExtra("lon", 121.0);
        final double laps = intent == null ? 4.0 : intent.getDoubleExtra("laps", 4.0);
        final double pace = intent == null ? 390.0 : intent.getDoubleExtra("pace", 390.0);
        String pathSpec = intent == null ? null : intent.getStringExtra("path");

        buildRoute(pathSpec);

        final double lat0;
        final double lon0;
        if (route != null) {
            lat0 = 0;
            lon0 = 0;
        } else {
            double[] wgs = gcj2wgs(rawLat, rawLon);
            lat0 = wgs[0];
            lon0 = wgs[1];
        }

        startForegroundCompat();

        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (!prepareProvider()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        running = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                runTrack(lat0, lon0, laps, pace);
            }
        }, "mock-track");
        worker.start();
        return START_STICKY;
    }

    private void buildRoute(String spec) {
        route = null;
        cum = null;
        routeLen = 0;
        if (spec == null) return;
        String s = spec.replace("\n", ";").replace("\r", ";")
                .replace(" ", "").replace("；", ";").trim();
        if (s.length() == 0) return;
        List<double[]> list = new ArrayList<double[]>();
        for (String p : s.split(";")) {
            if (p.length() == 0) continue;
            String[] kv = p.split(",");
            if (kv.length < 2) continue;
            try {
                double la = Double.parseDouble(kv[0]);
                double lo = Double.parseDouble(kv[1]);
                list.add(gcj2wgs(la, lo));
            } catch (Exception ignored) {
            }
        }
        if (list.size() < 2) return;

        route = list.toArray(new double[0][]);
        cum = new double[route.length + 1];
        for (int i = 0; i < route.length; i++) {
            cum[i + 1] = cum[i] + dist(route[i], route[(i + 1) % route.length]);
        }
        routeLen = cum[route.length];
    }

    private double dist(double[] a, double[] b) {
        double R = 6371000.0;
        double p1 = Math.toRadians(a[0]), p2 = Math.toRadians(b[0]);
        double dp = p2 - p1;
        double dl = Math.toRadians(b[1] - a[1]);
        double x = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * R * Math.asin(Math.sqrt(x));
    }

    private double[] at(double s) {
        double x = s % routeLen;
        if (x < 0) x += routeLen;
        int i = 0;
        while (i < route.length && cum[i + 1] < x) i++;
        if (i >= route.length) i = route.length - 1;
        double seg = cum[i + 1] - cum[i];
        double f = seg <= 0 ? 0 : (x - cum[i]) / seg;
        double[] a = route[i];
        double[] b = route[(i + 1) % route.length];
        return new double[]{a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f};
    }

    private void startForegroundCompat() {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CH, "location mock", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CH)
                .setContentTitle("SportMock running")
                .setContentText("Replaying track")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(1, n);
        }
    }

    private boolean prepareProvider() {
        String[] names = new String[]{
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                "fused",
                LocationManager.PASSIVE_PROVIDER
        };
        providers.clear();
        for (String name : names) {
            try {
                try {
                    lm.removeTestProvider(name);
                } catch (Exception ignored) {
                }
                lm.addTestProvider(name,
                        false, true, false, false, true, true, true, 1, 1);
                lm.setTestProviderEnabled(name, true);
                providers.add(name);
            } catch (Exception ignored) {
            }
        }
        return !providers.isEmpty();
    }

    private void releaseProvider() {
        for (String name : providers) {
            try {
                lm.setTestProviderEnabled(name, false);
            } catch (Exception ignored) {
            }
            try {
                lm.removeTestProvider(name);
            } catch (Exception ignored) {
            }
        }
        providers.clear();
    }

    private void runTrack(double lat0, double lon0, double laps, double pace) {
        double mLat = 111320.0;
        double speed = 1000.0 / pace;
        double loop = route != null ? routeLen : 400.0;
        double total = laps * loop;
        long t0 = SystemClock.elapsedRealtime();

        try {
            while (running) {
                double elapsed = (SystemClock.elapsedRealtime() - t0) / 1000.0;
                double s = elapsed * speed;
                if (s > total) break;

                double jLat, jLon, bx, by;

                if (route != null) {
                    double[] p = at(s);
                    double[] p2 = at(s + 2.0);
                    jLat = p[0] + gauss() / mLat;
                    jLon = p[1] + gauss() / (mLat * Math.cos(Math.toRadians(p[0])));
                    by = p2[0] - p[0];
                    bx = p2[1] - p[1];
                } else {
                    double[] off = ovalXY(s);
                    double[] off2 = ovalXY(s + 2.0);
                    jLat = lat0 + (off[1] + gauss()) / mLat;
                    jLon = lon0 + (off[0] + gauss()) / (mLat * Math.cos(Math.toRadians(lat0)));
                    by = off2[1] - off[1];
                    bx = off2[0] - off[0];
                }

                float bearing = (float) Math.toDegrees(Math.atan2(bx, by));
                if (bearing < 0f) bearing += 360f;

                long now = System.currentTimeMillis();
                long nowNs = SystemClock.elapsedRealtimeNanos();

                for (String name : providers) {
                    Location loc = new Location(name);
                    loc.setLatitude(jLat);
                    loc.setLongitude(jLon);
                    loc.setAccuracy(3.0f);
                    loc.setSpeed((float) speed);
                    loc.setBearing(bearing);
                    loc.setAltitude(20.0 + (rnd.nextDouble() * 2.0 - 1.0));
                    loc.setTime(now);
                    loc.setElapsedRealtimeNanos(nowNs);
                    lm.setTestProviderLocation(name, loc);
                }
                Thread.sleep(INTERVAL);
            }
        } catch (InterruptedException ignored) {
        } catch (SecurityException ignored) {
        } finally {
            releaseProvider();
        }
    }

    private double gauss() {
        return rnd.nextGaussian() * JITTER;
    }

    private double[] ovalXY(double sMeters) {
        double lap = sMeters % 400.0;
        if (lap < 0) lap += 400.0;

        if (lap < STRAIGHT) {
            return new double[]{-STRAIGHT / 2 + lap, -RADIUS};
        }
        lap -= STRAIGHT;
        if (lap < Math.PI * RADIUS) {
            double a = -Math.PI / 2 + lap / RADIUS;
            return new double[]{STRAIGHT / 2 + RADIUS * Math.cos(a),
                    RADIUS * Math.sin(a)};
        }
        lap -= Math.PI * RADIUS;
        if (lap < STRAIGHT) {
            return new double[]{STRAIGHT / 2 - lap, RADIUS};
        }
        lap -= STRAIGHT;
        double a = Math.PI / 2 + lap / RADIUS;
        return new double[]{-STRAIGHT / 2 + RADIUS * Math.cos(a),
                RADIUS * Math.sin(a)};
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        releaseProvider();
        super.onDestroy();
    }
}
